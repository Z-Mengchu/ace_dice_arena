package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.domain.BattleReport;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.PerformanceRecordRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class ParallelTournamentService {
    private static final BigDecimal GMV_PER_ACCUMULATION_ROLL = BigDecimal.valueOf(100_000L);
    private static final long VOTE_DURATION_MS = 19_500L;
    private static final long PROPHET_DURATION_MS = 30_000L;
    /** 队长选阵容的时限；超时由系统按在场队员自动补齐。 */
    private static final long LINEUP_DURATION_MS = 60_000L;
    /** 五人备战准备的时限；超时直接开放点击，不再等不到场的人。 */
    private static final long PREPARE_DURATION_MS = 60_000L;
    /** 口令下达后五人同步点击的时限；超时按已到位的点击判定，同步增益失效。 */
    private static final long ROLL_DURATION_MS = 30_000L;
    /** 王牌投手最终投骰的时限；超时由服务端代投。 */
    private static final long PITCHER_DURATION_MS = 30_000L;
    /** 攻擂开始后，非核心角色且未出战的在线队员可领取攻击力盲盒。 */
    private static final long ATTACK_BOOST_DURATION_MS = 20_000L;
    private static final List<String> ROLE_VOTE_ORDER = List.of("captain", "strategist", "pitcher");
    private final GameStateRepository states;
    private final UserAccountRepository users;
    private final PerformanceRecordRepository performances;
    private final GameControlRepository controls;
    private final ObjectMapper mapper;
    private final LobbyEventService events;
    private final long resultDisplayMs;
    private final BattleReportRepository reports;
    private final OnlineGameService online;
    /** 单线程保证兜底动作按触发顺序执行，同时把它们移出定时扫描的事务。 */
    private final java.util.concurrent.ExecutorService timeoutExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "arena-timeout-dispatcher");
                thread.setDaemon(true);
                return thread;
            });

    public ParallelTournamentService(GameStateRepository states, UserAccountRepository users,
                                     PerformanceRecordRepository performances,
                                     GameControlRepository controls, ObjectMapper mapper, LobbyEventService events,
                                     @Value("${app.game.result-display-ms:16000}") long resultDisplayMs,
                                     BattleReportRepository reports, OnlineGameService online) {
        this.states = states; this.users = users; this.performances = performances;
        this.controls = controls; this.mapper = mapper; this.events = events;
        this.resultDisplayMs = Math.max(0, resultDisplayMs);
        this.reports = reports;
        this.online = online;
    }

    @Transactional
    public void start(String username) {
        GameStateRecord record = states.findById(1L).orElse(null);
        ObjectNode previous = readState(record);
        ObjectNode dayResults = previous != null && previous.path("dayResults").isObject()
                ? ((ObjectNode) previous.path("dayResults")).deepCopy() : mapper.createObjectNode();
        int day = dayResults.has("day1") ? 2 : 1;
        if (dayResults.has("day2")) throw new IllegalStateException("两天赛事已经结束，请先重置整届比赛");
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 4); root.put("mode", "parallel"); root.put("day", day);
        long startedAt = System.currentTimeMillis();
        root.put("startedAt", startedAt); root.put("stage", "ROLE_VOTE");
        root.set("dayResults", dayResults);
        ArrayNode teams = root.putArray("teams");
        List<UserAccount> accounts = users.findAll().stream()
                .filter(u -> "USER".equals(u.getRole()) && AccountService.hasUsableDepartment(u.getDepartment()))
                .sorted(Comparator.comparing(UserAccount::getId)).toList();
        for (int i = 0; i < LobbyService.TEAM_IDS.size(); i++) {
            String teamId = LobbyService.TEAM_IDS.get(i);
            ObjectNode team = teams.addObject(); team.put("id", teamId); team.put("name", LobbyService.TEAM_NAMES.get(i));
            team.put("shortName", LobbyService.TEAM_NAMES.get(i).replace("战区", ""));
            BigDecimal teamGmv = accounts.stream().filter(u -> teamId.equals(u.getTeamId()))
                    .map(UserAccount::getGmv).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal lastWeekGmv = performances.findAll().stream()
                    .filter(p -> "MATCHED".equals(p.getMatchStatus()) && p.getMatchedUserId() != null)
                    .filter(p -> accounts.stream().anyMatch(u -> u.getId().equals(p.getMatchedUserId())
                            && teamId.equals(u.getTeamId())))
                    .map(p -> p.getLastWeekSalesAmount() == null ? BigDecimal.ZERO : p.getLastWeekSalesAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal growthCoefficient = growthCoefficient(teamGmv, lastWeekGmv);
            BigDecimal growthRate = growthCoefficient.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
            int accumulationRolls = teamGmv.divide(GMV_PER_ACCUMULATION_ROLL, 0, RoundingMode.FLOOR).intValue();
            team.put("gmv", teamGmv); team.put("lastWeekGmv", lastWeekGmv);
            team.put("growthRate", growthRate); team.put("growthCoefficient", growthCoefficient);
            team.put("accumulationQuota", accumulationRolls); team.put("accumulationRolled", 0);
            team.put("accumulationPoints", 0); team.putArray("accumulationDice");
            ArrayNode players = team.putArray("players");
            accounts.stream().filter(u -> teamId.equals(u.getTeamId())).forEach(u -> {
                ObjectNode player = players.addObject(); player.put("id", "u" + u.getId()); player.put("name", u.getDisplayName());
                player.put("department", u.getDepartment());
                player.put("role", u.isFrontEnd() ? "front" : "back");
                player.put("standIn", LobbyService.isStandIn(u));
                player.put("afk", u.isAfk());
                player.put("managed", LobbyService.isStandIn(u) || u.isAfk());
            });
        }
        ObjectNode matches = root.putObject("matches");
        for (int i = 0; i < 4; i++) {
            createMatch(matches, "g" + (i + 1), "t" + (i * 2 + 1), "t" + (i * 2 + 2));
        }
        initializeRoleVoting(root, startedAt);
        if (record == null) record = new GameStateRecord(1L, root.toString(), username);
        else record.update(root.toString(), username);
        states.save(record); events.gameChanged();
    }

    private ObjectNode readState(GameStateRecord record) {
        if (record == null) return null;
        try { return (ObjectNode) mapper.readTree(record.getContent()); }
        catch (Exception ignored) { return null; }
    }

    static BigDecimal growthCoefficient(BigDecimal currentGmv, BigDecimal lastWeekGmv) {
        if (currentGmv == null || lastWeekGmv == null || lastWeekGmv.signum() <= 0) return BigDecimal.ONE;
        return currentGmv.divide(lastWeekGmv, 4, RoundingMode.HALF_UP);
    }

    @Transactional
    public void configureSandboxPlayers(List<SandboxAssignment> assignments) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("请先建立管理员沙盘"));
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            root.remove("sandboxSolo");
            ArrayNode configured = root.putArray("sandboxPlayers");
            for (SandboxAssignment assignment : assignments) {
                UserAccount player = assignment.player();
                ObjectNode entry = configured.addObject();
                entry.put("username", player.getUsername()); entry.put("displayName", player.getDisplayName());
                entry.put("teamId", assignment.teamId()); entry.put("playerId", "u" + player.getId());
                entry.put("identity", assignment.identity());
                ObjectNode team = findTeam(root, assignment.teamId());
                ArrayNode players = (ArrayNode) team.path("players");
                String replacedId = "u" + assignment.replaced().getId();
                for (int index = players.size() - 1; index >= 0; index--) {
                    if (replacedId.equals(players.get(index).path("id").asText())) players.remove(index);
                }
                ObjectNode node = players.addObject(); node.put("id", "u" + player.getId());
                node.put("name", player.getDisplayName()); node.put("department", player.getDepartment());
                node.put("role", assignment.identity()); node.put("standIn", false);
                node.put("afk", false); node.put("managed", false);
            }
            // 积累期内只替换沙盘玩家，不提前建立角色选举状态。
            // 若管理员在攻擂阶段才指定玩家，则立即为沙盘补齐选举数据以兼容已有流程。
            if ("ROLE_VOTE".equals(root.path("stage").asText())) {
                prepareSandboxRoleVotes(root);
                startAccumulationIfReady(root);
            }
            record.update(root.toString(), "system"); states.save(record); events.gameChanged();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法建立双人沙盘模式", e);
        }
    }

    public boolean isSandboxPlayer(ObjectNode root, String username) {
        return sandboxPlayer(root, username) != null;
    }

    @Transactional
    public void restoreAfkPlayer(UserAccount user, String username) {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        if (record == null) return;
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            String playerId = "u" + user.getId();
            boolean changed = false;
            for (JsonNode teamNode : root.path("teams")) {
                if (!user.getTeamId().equals(teamNode.path("id").asText())) continue;
                for (JsonNode playerNode : teamNode.path("players")) {
                    if (!playerId.equals(playerNode.path("id").asText())) continue;
                    ObjectNode player = (ObjectNode) playerNode;
                    if (player.path("afk").asBoolean()) {
                        player.put("afk", false);
                        player.put("managed", player.path("standIn").asBoolean(false));
                        changed = true;
                    }
                }
            }
            if (!changed) return;
            record.update(root.toString(), username);
            states.save(record);
            events.teamGameChanged(user.getTeamId());
        } catch (Exception e) {
            throw new IllegalStateException("取消挂机状态失败", e);
        }
    }

    public void submitSandboxAction(ObjectNode root, UserAccount player, String type, List<String> values) {
        JsonNode configuredPlayer = sandboxPlayer(root, player.getUsername());
        if (configuredPlayer == null) throw new IllegalStateException("当前账号不是沙盘正式玩家");
        String teamId = configuredPlayer.path("teamId").asText();
        String action = type == null ? "" : type;
        if ("accumulation-roll".equals(action)) {
            submitAccumulation(root, player);
            driveSandboxAccumulation(root);
            return;
        }
        if ("role-vote".equals(action)) {
            submitRoleVote(root, player, values);
            return;
        }
        requireAttackStage(root);
        ObjectNode lead = activeMatchFor(root, teamId);
        String playerSide = teamId.equals(lead.path("a").asText()) ? "A" : "B";
        List<ObjectNode> active = activeMatches(root);
        Set<String> controlledTeams = sandboxTeams(root);
        switch (action) {
            case "prophet" -> {
                requireRole(root, player, "strategist", "只有当选军师可以提交预言");
                if (!"PROPHET".equals(lead.path("phase").asText())) throw new IllegalStateException("当前不接受预言");
                requireProphetOpen(lead);
                if (!values.isEmpty() && values.size() != 5) throw new IllegalArgumentException("预言必须选择 5 人或放弃");
                String opponent = "A".equals(playerSide) ? lead.path("b").asText() : lead.path("a").asText();
                validateSelection(root, opponent, values, false);
                setArray(lead.withObject("/prophet"), playerSide, values);
                lead.withObject("/submitted").put("prophet" + playerSide, true);
                String otherSide = "A".equals(playerSide) ? "B" : "A";
                if (!controlledTeams.contains(teamForSide(lead, otherSide))) {
                    lead.withObject("/prophet").putArray(otherSide);
                    lead.withObject("/submitted").put("prophet" + otherSide, true);
                }
                driveSandboxAutomation(root, lead, active);
            }
            case "lineup" -> {
                if (!"LINEUP".equals(lead.path("phase").asText())) throw new IllegalStateException("当前不接受阵容");
                submitCaptainLineup(root, lead, player, playerSide, values);
                driveSandboxAutomation(root, lead, active);
            }
            case "sandbox-ready" -> {
                requireAttackSidePhase(lead, playerSide, "PREPARING");
                if (values.size() != 1) throw new IllegalArgumentException("每次只能准备一个出战席位");
                String readyPlayer = values.getFirst();
                if (!contains(lead.at("/lineups/" + playerSide), readyPlayer))
                    throw new IllegalArgumentException("该队员不在本局出战阵容中");
                ArrayNode ready = lead.withObject("/sandboxReady").withArray(playerSide);
                if (!contains(ready, readyPlayer)) ready.add(readyPlayer);
                driveSandboxAutomation(root, lead, active);
            }
            case "captain-command" -> {
                requireRole(root, player, "captain", "只有当选队长可以发号施令");
                requireAttackSidePhase(lead, playerSide, "PREPARING");
                if (lead.at("/sandboxReady/" + playerSide).size() != 5)
                    throw new IllegalStateException("必须等待五名出战队员全部准备");
                long countdownUntil = System.currentTimeMillis() + 3_000L;
                active.forEach(match -> {
                    setAttackSidePhase(match, playerSide, "COUNTDOWN");
                    match.withObject("/countdownUntil").put(playerSide, countdownUntil);
                });
            }
            case "sandbox-roll" -> {
                requireAttackSidePhase(lead, playerSide, "ROLL");
                if (values.size() != 1) throw new IllegalArgumentException("每次只能由一名出战队员掷骰");
                String roller = values.getFirst();
                JsonNode lineup = lead.at("/lineups/" + playerSide);
                boolean selected = false;
                for (JsonNode id : lineup) if (roller.equals(id.asText())) { selected = true; break; }
                if (!selected) throw new IllegalArgumentException("该队员不在本局出战阵容中");
                ArrayNode rolled = lead.withObject("/sandboxRolled").withArray(playerSide);
                for (JsonNode id : rolled) if (roller.equals(id.asText())) throw new IllegalStateException("该队员已经掷过骰子");
                rolled.add(roller);
                if (rolled.size() == 5) {
                    active.forEach(match -> {
                        match.withObject("/timing").put("syncOk" + playerSide, true).put("spreadMs" + playerSide, 0);
                        setAttackSidePhase(match, playerSide, "PITCHER_ROLL");
                    });
                }
                driveSandboxAutomation(root, lead, active);
            }
            case "pitcher-roll" -> {
                requireRole(root, player, "pitcher", "只有当选王牌投手可以完成最终投骰");
                requireAttackSidePhase(lead, playerSide, "PITCHER_ROLL");
                active.forEach(match -> {
                    simulateRoll(root, match, playerSide);
                    setAttackSidePhase(match, playerSide, "WAITING");
                });
                if (lead.at("/rolls/A/dice").isArray() && lead.at("/rolls/B/dice").isArray())
                    settleSandboxRound(root, active, controlledTeams);
                driveSandboxAutomation(root, lead, active);
            }
            default -> throw new IllegalArgumentException("未知的双人沙盘操作");
        }
    }

    public void submitAccumulation(ObjectNode root, UserAccount player) {
        if (!"ACCUMULATION".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在积累期");
        ObjectNode team = findTeam(root, player.getTeamId());
        rollAccumulation(root, team, "u" + player.getId(), player.getDisplayName());
        finishAccumulationIfReady(root);
    }

    @Transactional
    public AdminAccumulationResult rollRemainingAccumulation(String teamId, String adminUsername) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("比赛尚未开始"));
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"ACCUMULATION".equals(root.path("stage").asText()))
                throw new IllegalStateException("当前不在积累期");
            ObjectNode team = findTeam(root, teamId);
            if (team.path("accumulationRolling").isObject())
                throw new IllegalStateException("该队有玩家正在掷积累骰，请等待本次结果");
            int before = team.path("accumulationRolled").asInt();
            int quota = team.path("accumulationQuota").asInt();
            if (before >= quota) throw new IllegalStateException("该队积累期已经完成");
            while (team.path("accumulationRolled").asInt() < quota) {
                rollAccumulation(root, team, "admin", "管理员代投");
            }
            finishAccumulationIfReady(root);
            record.update(root.toString(), adminUsername);
            states.save(record);
            events.gameChanged();
            return new AdminAccumulationResult(teamId, quota - before,
                    team.path("accumulationPoints").asInt(), root.path("stage").asText());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("管理员代投积累骰失败", e);
        }
    }

    /** 正式比赛积累骰分两阶段处理：先广播掷骰中状态，再由定时扫描揭示结果。 */
    public void beginAccumulation(ObjectNode root, UserAccount player) {
        if (!"ACCUMULATION".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在积累期");
        ObjectNode team = findTeam(root, player.getTeamId());
        if (team.path("accumulationRolling").isObject())
            throw new IllegalStateException("队友正在掷积累骰，请等待本次结果");
        if (team.path("accumulationRolled").asInt() >= team.path("accumulationQuota").asInt())
            throw new IllegalStateException("本队积累期掷骰机会已经全部使用");
        long revealAt = System.currentTimeMillis() + ThreadLocalRandom.current().nextLong(1_000L, 1_501L);
        ObjectNode rolling = team.putObject("accumulationRolling");
        rolling.put("playerId", "u" + player.getId());
        rolling.put("playerName", player.getDisplayName());
        rolling.put("revealAt", revealAt);
    }

    private void rollAccumulation(ObjectNode root, ObjectNode team, String playerId, String playerName) {
        int quota = team.path("accumulationQuota").asInt();
        int rolled = team.path("accumulationRolled").asInt();
        if (rolled >= quota) throw new IllegalStateException("本队积累期掷骰机会已经全部使用");
        int die = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 7);
        team.withArray("accumulationDice").add(die);
        team.put("accumulationRolled", rolled + 1);
        team.put("accumulationPoints", team.path("accumulationPoints").asInt() + die);
        ObjectNode history = root.withArray("accumulationHistory").addObject();
        history.put("teamId", team.path("id").asText()); history.put("playerId", playerId);
        history.put("playerName", playerName); history.put("die", die); history.put("createdAt", System.currentTimeMillis());
    }

    private void fillAccumulation(ObjectNode root, ObjectNode team) {
        while (team.path("accumulationRolled").asInt() < team.path("accumulationQuota").asInt())
            rollAccumulation(root, team, "system", "系统模拟");
    }

    private void finishAccumulationIfReady(ObjectNode root) {
        for (JsonNode team : root.path("teams"))
            if (team.path("accumulationRolled").asInt() < team.path("accumulationQuota").asInt()) return;
        long now = System.currentTimeMillis();
        root.put("stage", "ATTACK"); root.put("attackStartedAt", now);
        root.path("matches").forEach(match -> {
            if ("active".equals(match.path("status").asText())
                    && "PROPHET".equals(match.path("phase").asText())) {
                ((ObjectNode) match).put("prophetDeadlineAt", now + PROPHET_DURATION_MS);
            }
        });
    }

    private void initializeRoleVoting(ObjectNode root, long now) {
        root.path("teams").forEach(teamNode -> {
            ObjectNode team = (ObjectNode) teamNode;
            team.put("roleVoteStage", "captain");
            team.putObject("roles"); team.putObject("roleVotes");
            team.put("roleVoteCount", 0);
            team.put("roleVoteDeadlineAt", now + VOTE_DURATION_MS);
        });
    }

    private boolean startAccumulationIfReady(ObjectNode root) {
        if (!"ROLE_VOTE".equals(root.path("stage").asText())) return false;
        for (JsonNode team : root.path("teams")) if (!roleElectionComplete((ObjectNode) team)) return false;
        root.put("stage", "ACCUMULATION");
        root.put("accumulationStartedAt", System.currentTimeMillis());
        finishAccumulationIfReady(root);
        return true;
    }

    boolean revealDueAccumulation(ObjectNode root, long now) {
        if (!"ACCUMULATION".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode teamNode : root.path("teams")) {
            ObjectNode team = (ObjectNode) teamNode;
            JsonNode rolling = team.path("accumulationRolling");
            if (!rolling.isObject() || rolling.path("revealAt").asLong(Long.MAX_VALUE) > now) continue;
            rollAccumulation(root, team, rolling.path("playerId").asText(), rolling.path("playerName").asText());
            team.remove("accumulationRolling");
            changed = true;
        }
        if (changed) finishAccumulationIfReady(root);
        return changed;
    }

    private void driveSandboxAccumulation(ObjectNode root) {
        Set<String> controlled = sandboxTeams(root);
        for (JsonNode team : root.path("teams"))
            if (!controlled.contains(team.path("id").asText())) fillAccumulation(root, (ObjectNode) team);
        finishAccumulationIfReady(root);
    }

    private void requireAttackStage(ObjectNode root) {
        if (!"ATTACK".equals(root.path("stage").asText()))
            throw new IllegalStateException("必须先完成角色投票和八支队伍的积累期全部掷骰");
    }

    private void driveSandboxAutomation(ObjectNode root, ObjectNode lead, List<ObjectNode> active) {
        if (!roleElectionComplete(findTeam(root, lead.path("a").asText()))
                || !roleElectionComplete(findTeam(root, lead.path("b").asText()))) return;
        while (true) {
            String phase = lead.path("phase").asText();
            if ("PROPHET".equals(phase)) {
                for (String side : List.of("A", "B")) {
                    if (lead.at("/submitted/prophet" + side).asBoolean()) continue;
                    if (sandboxControlsRole(root, teamForSide(lead, side), "strategist")) return;
                    lead.withObject("/prophet").putArray(side);
                    lead.withObject("/submitted").put("prophet" + side, true);
                }
                active.stream().filter(match -> match != lead).forEach(this::simulateProphet);
                startLineupVoting(lead);
                continue;
            }
            if ("LINEUP".equals(phase)) {
                for (String side : List.of("A", "B")) {
                    if (lead.at("/submitted/lineup" + side).asBoolean()) continue;
                    String lineupTeam = teamForSide(lead, side);
                    if (sandboxControlsRole(root, lineupTeam, "captain")) return;
                    lead.withObject("/lineups").set(side, simulatedLineup(root, lineupTeam));
                    lead.withObject("/submitted").put("lineup" + side, true);
                }
                active.stream().filter(match -> match != lead).forEach(match -> simulateLineup(root, match));
                startParallelAttack(lead);
                continue;
            }
            if (!"ATTACKING".equals(phase)) return;
            boolean progressed = false;
            for (String side : List.of("A", "B")) {
                String teamId = teamForSide(lead, side);
                String sidePhase = attackSidePhase(lead, side);
                if ("PREPARING".equals(sidePhase) || "CONFIRM".equals(sidePhase)) {
                    if (sandboxTeams(root).contains(teamId)) continue;
                    active.forEach(match -> setAttackSidePhase(match, side, "ROLL"));
                    progressed = true;
                } else if ("COUNTDOWN".equals(sidePhase)) {
                    return;
                } else if ("ROLL".equals(sidePhase)) {
                    if (sandboxTeams(root).contains(teamId)) continue;
                    active.forEach(match -> {
                        match.withObject("/timing").put("syncOk" + side, false).put("spreadMs" + side, 501);
                        setAttackSidePhase(match, side, "PITCHER_ROLL");
                    });
                    progressed = true;
                } else if ("PITCHER_ROLL".equals(sidePhase)) {
                    if (sandboxControlsRole(root, teamId, "pitcher")) continue;
                    active.forEach(match -> {
                        simulateRoll(root, match, side);
                        setAttackSidePhase(match, side, "WAITING");
                    });
                    progressed = true;
                }
            }
            if (lead.at("/rolls/A/dice").isArray() && lead.at("/rolls/B/dice").isArray()) {
                settleSandboxRound(root, active, sandboxTeams(root));
                return;
            }
            if (!progressed) return;
        }
    }

    private boolean sandboxControlsRole(ObjectNode root, String teamId, String role) {
        String playerId = sandboxRoleController(root, teamId, role);
        if (playerId == null) return true;
        for (JsonNode player : root.path("sandboxPlayers"))
            if (teamId.equals(player.path("teamId").asText()) && playerId.equals(player.path("playerId").asText())) return true;
        return false;
    }

    private String sandboxRoleController(ObjectNode root, String teamId, String role) {
        String elected = findTeam(root, teamId).at("/roles/" + role).asText(null);
        if (elected == null) return null;
        JsonNode firstAssigned = null;
        for (JsonNode player : root.path("sandboxPlayers")) {
            if (!teamId.equals(player.path("teamId").asText())) continue;
            if (firstAssigned == null) firstAssigned = player;
            if (elected.equals(player.path("playerId").asText())) return elected;
        }
        return firstAssigned == null ? elected : firstAssigned.path("playerId").asText();
    }

    public void submitRoleVote(ObjectNode root, UserAccount voter, List<String> values) {
        if (!"ROLE_VOTE".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在核心角色投票阶段");
        if (values.size() != 1) throw new IllegalArgumentException("每次只能为当前角色选择一名候选人");
        ObjectNode team = findTeam(root, voter.getTeamId());
        String role = team.path("roleVoteStage").asText("captain");
        if (!ROLE_VOTE_ORDER.contains(role) || roleElectionComplete(team))
            throw new IllegalStateException("本队核心角色投票已经完成");
        if (team.path("roleVoteDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("角色投票时间已经结束");
        validateRoleCandidate(team, role, values.getFirst());
        String voterId = "u" + voter.getId();
        if (!isEligibleRoleVoter(team, voterId))
            throw new IllegalStateException("托管沙盘队友不能参与角色投票");
        ObjectNode votes = currentRoleVotes(team);
        if (votes.has(voterId)) throw new IllegalStateException("你已经提交过当前角色的选票");
        votes.put(voterId, values.getFirst());
        int validVotes = eligibleRoleVoteCount(team, votes);
        team.put("roleVoteCount", validVotes);
        if (validVotes >= eligibleRoleVoterCount(team)) {
            completeRoleVoteStage(team);
            startAccumulationIfReady(root);
        }
    }

    @Transactional
    public AdminRoleAssignment assignCurrentRole(String teamId, String role, String playerId, String adminUsername) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("比赛尚未开始"));
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"ROLE_VOTE".equals(root.path("stage").asText()))
                throw new IllegalStateException("当前不在核心角色投票阶段");
            ObjectNode team = findTeam(root, teamId);
            String currentRole = team.path("roleVoteStage").asText();
            if (!ROLE_VOTE_ORDER.contains(currentRole) || roleElectionComplete(team))
                throw new IllegalStateException("本队核心角色投票已经完成");
            if (!currentRole.equals(role))
                throw new IllegalStateException("当前正在选出" + roleLabel(currentRole) + "，请刷新页面后重试");
            validateAdminRoleCandidate(team, playerId);
            completeRoleVoteStage(team, playerId);
            startAccumulationIfReady(root);
            record.update(root.toString(), adminUsername);
            states.save(record);
            if (!"ROLE_VOTE".equals(root.path("stage").asText())) events.gameChanged();
            else events.teamGameChanged(teamId);
            return new AdminRoleAssignment(teamId, role, playerId,
                    team.path("roleVoteStage").asText(), root.path("stage").asText());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("管理员指定核心角色失败", e);
        }
    }

    private String roleLabel(String role) {
        return switch (role) {
            case "captain" -> "队长";
            case "strategist" -> "军师";
            case "pitcher" -> "王牌投手";
            default -> "当前角色";
        };
    }

    private void validateAdminRoleCandidate(ObjectNode team, String candidate) {
        boolean found = false;
        for (JsonNode player : team.path("players")) {
            if (!player.path("managed").asBoolean()
                    && candidate != null && candidate.equals(player.path("id").asText())) {
                found = true;
                break;
            }
        }
        if (!found) throw new IllegalArgumentException("候选人不在本队");
        for (JsonNode elected : team.path("roles"))
            if (candidate.equals(elected.asText())) throw new IllegalArgumentException("三名核心角色不能由同一人兼任");
    }

    public void requireRole(ObjectNode root, UserAccount user, String role, String message) {
        ObjectNode team = findTeam(root, user.getTeamId());
        if (!team.path("roles").hasNonNull(role)) throw new IllegalStateException("请等待全队完成角色投票");
        if (!("u" + user.getId()).equals(sandboxRoleController(root, user.getTeamId(), role)))
            throw new IllegalStateException(message);
    }

    private void validateRoleCandidate(ObjectNode team, String role, String candidate) {
        Set<String> all = new HashSet<>(), backs = new HashSet<>(), fronts = new HashSet<>();
        for (JsonNode player : team.path("players")) {
            if (player.path("managed").asBoolean()) continue;
            String id = player.path("id").asText(); all.add(id);
            if ("back".equals(player.path("role").asText())) backs.add(id); else fronts.add(id);
        }
        if (!all.contains(candidate)) throw new IllegalArgumentException("候选人不在本队");
        if ("captain".equals(role) && !fronts.contains(candidate))
            throw new IllegalArgumentException("队长必须选择前端队员");
        if ("pitcher".equals(role) && !backs.contains(candidate))
            throw new IllegalArgumentException("王牌投手必须选择后端队员");
        for (JsonNode elected : team.path("roles"))
            if (candidate.equals(elected.asText())) throw new IllegalArgumentException("三名核心角色不能由同一人兼任");
    }

    private void completeRoleVoteStage(ObjectNode team) {
        String role = team.path("roleVoteStage").asText("captain");
        ObjectNode roles = team.withObject("/roles");
        Set<String> excluded = new HashSet<>(); roles.elements().forEachRemaining(value -> excluded.add(value.asText()));
        completeRoleVoteStage(team, electionWinner(team, role, excluded));
    }

    private void completeRoleVoteStage(ObjectNode team, String winner) {
        String role = team.path("roleVoteStage").asText("captain");
        int completedVoteCount = eligibleRoleVoteCount(team, currentRoleVotes(team));
        team.withObject("/roles").put(role, winner);
        int next = ROLE_VOTE_ORDER.indexOf(role) + 1;
        if (next >= ROLE_VOTE_ORDER.size()) {
            team.put("roleVoteStage", "complete"); team.remove("roleVoteDeadlineAt");
            team.put("roleVoteCount", completedVoteCount);
            return;
        }
        team.put("roleVoteStage", ROLE_VOTE_ORDER.get(next));
        team.put("roleVoteDeadlineAt", System.currentTimeMillis() + VOTE_DURATION_MS);
        team.put("roleVoteCount", currentRoleVotes(team).size());
    }

    private String electionWinner(ObjectNode team, String role, Set<String> excluded) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        currentRoleVotes(team).fields().forEachRemaining(vote -> {
            if (isEligibleRoleVoter(team, vote.getKey())) counts.merge(vote.getValue().asText(), 1, Integer::sum);
        });
        String winner = null; int best = -1;
        for (JsonNode player : team.path("players")) {
            if (player.path("managed").asBoolean()) continue;
            if ("captain".equals(role) && !"front".equals(player.path("role").asText())) continue;
            if ("pitcher".equals(role) && !"back".equals(player.path("role").asText())) continue;
            String id = player.path("id").asText(); int count = counts.getOrDefault(id, 0);
            if (excluded.contains(id)) continue;
            if (count > best) { winner = id; best = count; }
        }
        if (winner == null) throw new IllegalStateException("队伍缺少符合角色条件的成员");
        return winner;
    }

    private ObjectNode currentRoleVotes(ObjectNode team) {
        String role = team.path("roleVoteStage").asText("captain");
        return team.withObject("/roleVotes").withObject("/" + role);
    }

    private boolean isEligibleRoleVoter(ObjectNode team, String playerId) {
        for (JsonNode player : team.path("players")) {
            if (playerId.equals(player.path("id").asText())) return !player.path("managed").asBoolean();
        }
        return false;
    }

    private int eligibleRoleVoterCount(ObjectNode team) {
        int count = 0;
        for (JsonNode player : team.path("players")) if (!player.path("managed").asBoolean()) count++;
        return count;
    }

    private int eligibleRoleVoteCount(ObjectNode team, ObjectNode votes) {
        int count = 0;
        var voters = votes.fieldNames();
        while (voters.hasNext()) if (isEligibleRoleVoter(team, voters.next())) count++;
        return count;
    }

    private boolean roleElectionComplete(ObjectNode team) {
        return team.path("roles").hasNonNull("captain")
                && team.path("roles").hasNonNull("strategist")
                && team.path("roles").hasNonNull("pitcher");
    }

    private void prepareSandboxRoleVotes(ObjectNode root) {
        Set<String> configured = new HashSet<>();
        root.path("sandboxPlayers").forEach(player -> configured.add(player.path("playerId").asText()));
        for (JsonNode teamNode : root.path("teams")) {
            ObjectNode team = (ObjectNode) teamNode;
            List<JsonNode> players = new java.util.ArrayList<>(); team.path("players").forEach(players::add);
            JsonNode pitcher = players.stream().filter(player -> !player.path("managed").asBoolean()).filter(player -> configured.contains(player.path("id").asText()) && "back".equals(player.path("role").asText())).findFirst()
                    .orElseGet(() -> players.stream().filter(player -> !player.path("managed").asBoolean()).filter(player -> "back".equals(player.path("role").asText())).findFirst().orElseThrow());
            JsonNode captain = players.stream().filter(player -> !player.path("managed").asBoolean()).filter(player -> configured.contains(player.path("id").asText()) && "front".equals(player.path("role").asText())).findFirst()
                    .orElseGet(() -> players.stream().filter(player -> !player.path("managed").asBoolean()).filter(player -> "front".equals(player.path("role").asText())).findFirst().orElseThrow());
            JsonNode preferred = players.stream().filter(player -> !player.path("managed").asBoolean()).filter(player -> !pitcher.path("id").asText().equals(player.path("id").asText())
                    && !captain.path("id").asText().equals(player.path("id").asText())).findFirst().orElseThrow();
            team.put("roleVoteStage", "captain"); team.putObject("roles");
            ObjectNode votes = team.putObject("roleVotes");
            ObjectNode captainVotes = votes.putObject("captain");
            ObjectNode strategistVotes = votes.putObject("strategist");
            ObjectNode pitcherVotes = votes.putObject("pitcher");
            for (JsonNode player : players) if (!player.path("managed").asBoolean() && !configured.contains(player.path("id").asText())) {
                String voterId = player.path("id").asText();
                captainVotes.put(voterId, captain.path("id").asText());
                strategistVotes.put(voterId, preferred.path("id").asText());
                pitcherVotes.put(voterId, pitcher.path("id").asText());
            }
            team.put("roleVoteCount", captainVotes.size());
            while (!roleElectionComplete(team)
                    && eligibleRoleVoteCount(team, currentRoleVotes(team)) == eligibleRoleVoterCount(team))
                completeRoleVoteStage(team);
        }
    }

    /**
     * 沙盘助攻：只在"一方有真实玩家、另一方没有"时干预，保证真人能一直打到下一轮。
     * 两边都有真人时让他们真打；两边都没有真人时完全不干预——否则整个签表会被固定成编号在前的队伍晋级。
     */
    private void settleSandboxRound(ObjectNode root, List<ObjectNode> active, Set<String> controlledTeams) {
        for (ObjectNode match : active) {
            String forcedSide = forcedSandboxSide(controlledTeams.contains(match.path("a").asText()),
                    controlledTeams.contains(match.path("b").asText()));
            if (forcedSide != null) assistSandboxSide(match, forcedSide);
            enterResult(root, match);
        }
    }

    /** 只有"恰好一方有真实玩家"时才助攻；两边都有或都没有真人，一律让骰子说话。 */
    static String forcedSandboxSide(boolean aControlled, boolean bControlled) {
        return aControlled == bControlled ? null : (aControlled ? "A" : "B");
    }

    /**
     * 助攻的实现方式是"重掷出一手更好的骰子"，而不是直接改写攻击力。
     * 攻击力始终由骰子按同一套公式算出，大屏上的点数和分数必须永远对得上。
     */
    void assistSandboxSide(ObjectNode match, String forcedSide) {
        String loserSide = "A".equals(forcedSide) ? "B" : "A";
        ObjectNode winnerRoll = (ObjectNode) match.at("/rolls/" + forcedSide);
        if (winnerRoll == null) return;
        double target = match.at("/rolls/" + loserSide + "/attack").asDouble() + 10;
        if (winnerRoll.path("attack").asDouble() >= target) return;
        writeRoll(winnerRoll, diceWithSum((int) Math.ceil(target / 1.5)), true);
        winnerRoll.put("sandboxAssisted", true);
    }

    /** 生成一组和为 target（自动夹到 5..30）的五枚骰子，顺序打乱以免每次都是同一种形状。 */
    List<Integer> diceWithSum(int target) {
        int remaining = Math.min(30, Math.max(5, target)) - 5;
        List<Integer> dice = new ArrayList<>(List.of(1, 1, 1, 1, 1));
        for (int index = 0; index < dice.size() && remaining > 0; index++) {
            int add = Math.min(5, remaining);
            dice.set(index, 1 + add);
            remaining -= add;
        }
        java.util.Collections.shuffle(dice);
        return dice;
    }

    /** 骰子是唯一事实来源：同步加成与豹子倍率都在这里统一结算，避免分数和点数对不上。 */
    private void writeRoll(ObjectNode roll, List<Integer> dice, boolean sync) {
        ArrayNode array = roll.putArray("dice");
        int sum = 0;
        for (int die : dice) { array.add(die); sum += die; }
        boolean leopard = dice.size() == 5 && dice.stream().distinct().count() == 1;
        double attack = sum * (sync ? 1.5 : 1) * (leopard ? 3 : 1);
        roll.put("syncOk", sync);
        roll.put("attack", Math.round(attack * 100d) / 100d);
        roll.put("fatigued", false);
    }

    private JsonNode sandboxPlayer(ObjectNode root, String username) {
        if (username == null) return null;
        for (JsonNode player : root.path("sandboxPlayers")) if (username.equals(player.path("username").asText())) return player;
        JsonNode legacy = root.path("sandboxSolo");
        return username.equals(legacy.path("username").asText(null)) ? legacy : null;
    }

    private Set<String> sandboxTeams(ObjectNode root) {
        Set<String> teams = new HashSet<>();
        root.path("sandboxPlayers").forEach(player -> teams.add(player.path("teamId").asText()));
        if (teams.isEmpty() && root.path("sandboxSolo").hasNonNull("teamId")) teams.add(root.at("/sandboxSolo/teamId").asText());
        return teams;
    }

    private String teamForSide(ObjectNode match, String side) {
        return match.path("A".equals(side) ? "a" : "b").asText();
    }

    private List<ObjectNode> activeMatches(ObjectNode root) {
        List<ObjectNode> result = new java.util.ArrayList<>();
        root.path("matches").elements().forEachRemaining(node -> {
            if ("active".equals(node.path("status").asText())) result.add((ObjectNode) node);
        });
        return result;
    }

    private ObjectNode activeMatchFor(ObjectNode root, String teamId) {
        return activeMatches(root).stream().filter(match -> teamId.equals(match.path("a").asText())
                || teamId.equals(match.path("b").asText())).findFirst()
                .orElseThrow(() -> new IllegalStateException("领跑队伍当前没有比赛"));
    }

    private ObjectNode findTeam(ObjectNode root, String teamId) {
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) return (ObjectNode) candidate;
        throw new IllegalStateException("队伍资料不存在");
    }

    private void validateSelection(ObjectNode root, String teamId, List<String> selected, boolean needBack) {
        ObjectNode team = findTeam(root, teamId);
        Set<String> valid = new HashSet<>(), back = new HashSet<>();
        for (JsonNode candidate : team.path("players")) {
            if (candidate.path("managed").asBoolean()) continue;
            valid.add(candidate.path("id").asText());
            if ("back".equals(candidate.path("role").asText())) back.add(candidate.path("id").asText());
        }
        if (new HashSet<>(selected).size() != selected.size() || !valid.containsAll(selected))
            throw new IllegalArgumentException("选择的队员无效");
        if (needBack && selected.stream().noneMatch(back::contains)) throw new IllegalArgumentException("阵容至少需要 1 名后端队员");
    }

    public void submitCaptainLineup(ObjectNode root, ObjectNode match, UserAccount player, String side,
                                    List<String> selected) {
        requireRole(root, player, "captain", "只有当选队长可以决定本轮出战阵容");
        if (selected.size() != 5) throw new IllegalArgumentException("出战阵容必须选择 5 人");
        String teamId = teamForSide(match, side);
        if (!teamId.equals(player.getTeamId())) throw new IllegalStateException("只能选择本队出战阵容");
        if (match.at("/submitted/lineup" + side).asBoolean()) throw new IllegalStateException("本队本轮阵容已经确定");
        validateSelection(root, teamId, selected, true);
        setArray(match.withObject("/lineups"), side, selected);
        match.withObject("/submitted").put("lineup" + side, true);
    }

    private void setArray(ObjectNode target, String field, List<String> values) {
        ArrayNode array = mapper.createArrayNode(); values.forEach(array::add); target.set(field, array);
    }

    @Transactional
    public TestStep simulateStep(String username) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("测试比赛尚未建立"));
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"parallel".equals(root.path("mode").asText())) throw new IllegalStateException("当前不是并行比赛流程");
            if ("ROLE_VOTE".equals(root.path("stage").asText())) {
                root.path("teams").forEach(team -> simulateRolesIfMissing((ObjectNode) team));
                startAccumulationIfReady(root);
                record.update(root.toString(), username); states.save(record); events.gameChanged();
                return new TestStep(8, null, controls.findById(1L).map(control -> control.getPhase()).orElse("PREPARING"));
            }
            if ("ACCUMULATION".equals(root.path("stage").asText())) {
                root.path("teams").forEach(team -> fillAccumulation(root, (ObjectNode) team));
                finishAccumulationIfReady(root);
                record.update(root.toString(), username); states.save(record); events.gameChanged();
                return new TestStep(8, null, controls.findById(1L).map(control -> control.getPhase()).orElse("PREPARING"));
            }
            root.path("teams").forEach(team -> simulateRolesIfMissing((ObjectNode) team));
            ObjectNode matches = (ObjectNode) root.path("matches");
            var iterator = matches.elements();
            int advanced = 0;
            while (iterator.hasNext()) {
                ObjectNode match = (ObjectNode) iterator.next();
                if (!"active".equals(match.path("status").asText())) continue;
                switch (match.path("phase").asText()) {
                    case "PROPHET" -> simulateProphet(match);
                    case "LINEUP" -> simulateLineup(root, match);
                    case "ATTACKING" -> simulateParallelAttackStep(root, match);
                    case "RESULT" -> completeResult(match);
                    default -> { }
                }
                advanced++;
            }
            advance(root);
            record.update(root.toString(), username); states.save(record);
            if (root.hasNonNull("champion")) events.stateChanged(); else events.gameChanged();
            return new TestStep(advanced, root.path("champion").asText(null),
                    controls.findById(1L).map(control -> control.getPhase()).orElse("PREPARING"));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("推进测试流程失败", e);
        }
    }

    private void simulateProphet(ObjectNode match) {
        ObjectNode prophet = match.withObject("/prophet");
        prophet.putArray("A"); prophet.putArray("B");
        ObjectNode submitted = match.withObject("/submitted");
        submitted.put("prophetA", true); submitted.put("prophetB", true);
        startLineupVoting(match);
    }

    private void simulateRolesIfMissing(ObjectNode team) {
        if (roleElectionComplete(team)) return;
        List<JsonNode> players = new java.util.ArrayList<>(); team.path("players").forEach(players::add);
        if (players.size() < 3) throw new IllegalStateException("测试队伍人数不足");
        String strategist = players.get(0).path("id").asText();
        String pitcher = players.stream().filter(player -> "back".equals(player.path("role").asText())
                && !strategist.equals(player.path("id").asText())).map(player -> player.path("id").asText()).findFirst().orElseThrow();
        String captain = players.stream().filter(player -> "front".equals(player.path("role").asText())
                && !strategist.equals(player.path("id").asText()) && !pitcher.equals(player.path("id").asText()))
                .map(player -> player.path("id").asText()).findFirst().orElseThrow();
        ObjectNode roles = team.putObject("roles");
        roles.put("strategist", strategist); roles.put("pitcher", pitcher); roles.put("captain", captain);
        team.put("roleVoteStage", "complete");
        team.put("roleVoteCount", team.path("players").size());
        team.remove("roleVoteDeadlineAt");
    }

    private void simulateLineup(ObjectNode root, ObjectNode match) {
        for (String side : List.of("A", "B")) {
            match.withObject("/lineups").set(side, simulatedLineup(root, teamForSide(match, side)));
            match.withObject("/submitted").put("lineup" + side, true);
        }
        startParallelAttack(match);
    }

    public void startLineupVoting(ObjectNode match) {
        match.put("phase", "LINEUP");
        match.remove("prophetDeadlineAt");
        match.put("lineupDeadlineAt", System.currentTimeMillis() + LINEUP_DURATION_MS);
    }

    public void startParallelAttack(ObjectNode match) {
        long now = System.currentTimeMillis();
        long displayUntil = now + 5_000L;
        match.put("phase", "ATTACKING");
        match.remove("lineupDeadlineAt");
        match.put("lineupDisplayUntil", displayUntil);
        ObjectNode sidePhases = match.putObject("sidePhases");
        sidePhases.put("A", "PREPARING");
        sidePhases.put("B", "PREPARING");
        ObjectNode attackBoost = match.putObject("attackBoost");
        attackBoost.put("deadlineAt", now + ATTACK_BOOST_DURATION_MS);
        attackBoost.putObject("claimsA");
        attackBoost.putObject("claimsB");
        // 备战时限从阵容展示结束后才开始计，避免展示的 5 秒被算进准备时间。
        armSideDeadline(match, "A", "PREPARING", displayUntil);
        armSideDeadline(match, "B", "PREPARING", displayUntil);
    }

    public double claimAttackBoost(ObjectNode root, ObjectNode match, UserAccount user, String side) {
        if (!"ATTACKING".equals(match.path("phase").asText()))
            throw new IllegalStateException("当前不在攻击力盲盒领取阶段");
        if (LobbyService.isStandIn(user)) throw new IllegalStateException("托管队员不能领取攻击力盲盒");
        ObjectNode attackBoost = match.withObject("/attackBoost");
        long deadlineAt = attackBoost.path("deadlineAt").asLong(0L);
        if (deadlineAt <= 0 || deadlineAt <= System.currentTimeMillis())
            throw new IllegalStateException("攻击力盲盒领取时间已结束，本轮视为放弃");

        String playerId = "u" + user.getId();
        ObjectNode team = findTeam(root, user.getTeamId());
        if (playerId.equals(team.at("/roles/captain").asText())
                || playerId.equals(team.at("/roles/pitcher").asText()))
            throw new IllegalStateException("队长和王牌投手不能领取攻击力盲盒");
        if (contains(match.at("/lineups/" + side), playerId))
            throw new IllegalStateException("本轮出战队员不能领取攻击力盲盒");

        JsonNode player = null;
        for (JsonNode candidate : team.path("players")) {
            if (playerId.equals(candidate.path("id").asText())) { player = candidate; break; }
        }
        if (player == null) throw new IllegalStateException("当前账号不在本队参赛名单中");
        if (player.path("managed").asBoolean() || player.path("standIn").asBoolean()
                || player.path("afk").asBoolean())
            throw new IllegalStateException("托管队员不能领取攻击力盲盒");

        ObjectNode claims = attackBoost.withObject("/claims" + side);
        if (claims.has(playerId)) throw new IllegalStateException("本轮已经领取过攻击力盲盒");
        double multiplier = ThreadLocalRandom.current().nextInt(100, 151) / 100d;
        claims.put(playerId, multiplier);
        return multiplier;
    }

    public String attackSidePhase(ObjectNode match, String side) {
        if (!match.path("sidePhases").isObject()) {
            String legacy = match.path("phase").asText();
            ObjectNode sidePhases = match.putObject("sidePhases");
            sidePhases.put("A", switch (legacy) {
                case "ROLL_A" -> "ROLL";
                case "PITCHER_ROLL_A" -> "PITCHER_ROLL";
                case "CONFIRM_B", "ROLL_B", "PITCHER_ROLL_B" -> "WAITING";
                default -> "PREPARING";
            });
            sidePhases.put("B", switch (legacy) {
                case "ROLL_B" -> "ROLL";
                case "PITCHER_ROLL_B" -> "PITCHER_ROLL";
                default -> "PREPARING";
            });
            match.put("phase", "ATTACKING");
        }
        return match.at("/sidePhases/" + side).asText();
    }

    public void requireAttackSidePhase(ObjectNode match, String side, String expected) {
        if (!expected.equals(attackSidePhase(match, side)))
            throw new IllegalStateException("当前不是本队的" + expected + "阶段");
    }

    /** 切换本方阶段时同步维护截止时间：等人的阶段自动上时限，不等人的阶段撤掉。 */
    public void setAttackSidePhase(ObjectNode match, String side, String phase) {
        match.withObject("/sidePhases").put(side, phase);
        armSideDeadline(match, side, phase, System.currentTimeMillis());
    }

    private void armSideDeadline(ObjectNode match, String side, String phase, long from) {
        long duration = switch (phase) {
            case "PREPARING" -> PREPARE_DURATION_MS;
            case "ROLL" -> ROLL_DURATION_MS;
            case "PITCHER_ROLL" -> PITCHER_DURATION_MS;
            default -> 0L;
        };
        ObjectNode deadlines = match.withObject("/sideDeadlines");
        if (duration > 0) deadlines.put(side, from + duration); else deadlines.remove(side);
    }

    private boolean contains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    public void requireProphetOpen(ObjectNode match) {
        if (!match.has("prophetDeadlineAt"))
            match.put("prophetDeadlineAt", System.currentTimeMillis() + PROPHET_DURATION_MS);
        if (match.path("prophetDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("军师预言已超时，本局视为放弃预言");
    }

    private ArrayNode simulatedLineup(ObjectNode root, String teamId) {
        JsonNode team = null;
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) team = candidate;
        if (team == null || team.path("players").size() < 5) throw new IllegalStateException("测试队伍人数不足");
        ArrayNode lineup = mapper.createArrayNode();
        for (JsonNode player : team.path("players")) {
            if (player.path("managed").asBoolean()) continue;
            if ("back".equals(player.path("role").asText())) { lineup.add(player.path("id").asText()); break; }
        }
        for (JsonNode player : team.path("players")) {
            if (player.path("managed").asBoolean()) continue;
            String id = player.path("id").asText();
            boolean selected = false;
            for (JsonNode value : lineup) if (id.equals(value.asText())) selected = true;
            if (!selected && lineup.size() < 5) lineup.add(id);
        }
        return lineup;
    }

    private void simulatePreparation(ObjectNode match, String side) {
        setAttackSidePhase(match, side, "ROLL");
    }

    private void simulateParallelAttackStep(ObjectNode root, ObjectNode match) {
        for (String side : List.of("A", "B")) {
            switch (attackSidePhase(match, side)) {
                case "PREPARING", "CONFIRM", "COUNTDOWN" -> simulatePreparation(match, side);
                case "ROLL" -> simulateTiming(match, side);
                case "PITCHER_ROLL" -> {
                    simulateRoll(root, match, side);
                    setAttackSidePhase(match, side, "WAITING");
                }
                default -> { }
            }
        }
        if (match.at("/rolls/A/dice").isArray() && match.at("/rolls/B/dice").isArray()) enterResult(root, match);
    }

    private void simulateRoll(ObjectNode root, ObjectNode match, String side) {
        Random random = new Random();
        ObjectNode roll = match.withObject("/rolls").putObject(side);
        List<Integer> dice = new ArrayList<>();
        for (int i = 0; i < 5; i++) dice.add(random.nextInt(6) + 1);
        JsonNode timingSync = match.at("/timing/syncOk" + side);
        writeRoll(roll, dice, timingSync.isBoolean() ? timingSync.asBoolean() : random.nextBoolean());
    }

    private void simulateTiming(ObjectNode match, String side) {
        boolean sync = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
        match.withObject("/timing").put("syncOk" + side, sync).put("spreadMs" + side, sync ? 250 : 650);
        setAttackSidePhase(match, side, "PITCHER_ROLL");
    }

    @EventListener
    @Transactional
    public void onDiceAttackStarted(OnlineGameService.DiceAttackStartedEvent event) {
        updateAttackStart(event.teamId());
    }

    @EventListener
    public void onDiceReadinessChanged(OnlineGameService.DiceReadinessChangedEvent event) {
        events.teamGameChanged(event.teamId());
    }

    private void updateAttackStart(String teamId) {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        if (record == null) return;
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            for (JsonNode node : root.path("matches")) {
                ObjectNode match = (ObjectNode) node;
                if (!"active".equals(match.path("status").asText())) continue;
                String side = teamId.equals(match.path("a").asText()) ? "A"
                        : teamId.equals(match.path("b").asText()) ? "B" : null;
                if (side == null || !"COUNTDOWN".equals(attackSidePhase(match, side))) continue;
                setAttackSidePhase(match, side, "ROLL");
                record.update(root.toString(), "system"); states.save(record); events.teamGameChanged(teamId);
                return;
            }
        } catch (Exception ignored) { }
    }

    @EventListener
    @Transactional
    public void onDiceTimingProgress(OnlineGameService.DiceTimingProgressEvent event) {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        if (record == null) return;
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"parallel".equals(root.path("mode").asText())) return;
            for (JsonNode node : root.path("matches")) {
                ObjectNode match = (ObjectNode) node;
                if (!"active".equals(match.path("status").asText())) continue;
                String side = event.teamId().equals(match.path("a").asText()) ? "A"
                        : event.teamId().equals(match.path("b").asText()) ? "B" : null;
                if (side == null || !"ROLL".equals(attackSidePhase(match, side))) continue;
                ArrayNode progress = match.withObject("/timing").putArray("rolledSlots" + side);
                event.rolledSlots().forEach(progress::add);
                record.update(root.toString(), "system");
                states.save(record);
                events.gameChanged();
                return;
            }
        } catch (Exception ignored) { }
    }

    @EventListener
    @Transactional
    public void onDiceTimingReady(OnlineGameService.DiceTimingReadyEvent event) {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        if (record == null) return;
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"parallel".equals(root.path("mode").asText())) return;
            for (JsonNode node : root.path("matches")) {
                ObjectNode match = (ObjectNode) node;
                if (!"active".equals(match.path("status").asText())) continue;
                String side = event.teamId().equals(match.path("a").asText()) ? "A"
                        : event.teamId().equals(match.path("b").asText()) ? "B" : null;
                if (side == null || !"ROLL".equals(attackSidePhase(match, side))) continue;
                match.withObject("/timing").put("syncOk" + side, event.syncOk())
                        .put("spreadMs" + side, event.spreadMs());
                setAttackSidePhase(match, side, "PITCHER_ROLL");
                record.update(root.toString(), "system"); states.save(record); events.gameChanged();
                return;
            }
        } catch (Exception ignored) { }
    }

    @EventListener
    @Transactional
    public void onDiceReveal(OnlineGameService.DiceRevealEvent event) {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        if (record == null) return;
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"parallel".equals(root.path("mode").asText())) return;
            ObjectNode target = null; String side = null;
            var iterator = root.path("matches").elements();
            while (iterator.hasNext()) {
                ObjectNode match = (ObjectNode) iterator.next();
                if (!"active".equals(match.path("status").asText())) continue;
                if (event.teamId().equals(match.path("a").asText()) && "PITCHER_ROLL".equals(attackSidePhase(match, "A"))) { target = match; side = "A"; break; }
                if (event.teamId().equals(match.path("b").asText()) && "PITCHER_ROLL".equals(attackSidePhase(match, "B"))) { target = match; side = "B"; break; }
            }
            if (target == null) return;
            ObjectNode roll = target.withObject("/rolls").putObject(side);
            ArrayNode dice = roll.putArray("dice"); event.dice().forEach(d -> dice.add(d.die()));
            roll.put("syncOk", event.syncOk());
            Attack attack = attack(event, root); roll.put("attack", attack.value()); roll.put("fatigued", attack.fatigued());
            setAttackSidePhase(target, side, "WAITING");
            if (target.at("/rolls/A/dice").isArray() && target.at("/rolls/B/dice").isArray()) enterResult(root, target);
            record.update(root.toString(), "system"); states.save(record); events.gameChanged();
        } catch (Exception ignored) { }
    }

    private Attack attack(OnlineGameService.DiceRevealEvent event, ObjectNode root) {
        int sum = event.dice().stream().mapToInt(OnlineGameService.DiceResult::die).sum();
        boolean leopard = event.dice().size() == 5 && event.dice().stream().map(OnlineGameService.DiceResult::die).distinct().count() == 1;
        double value = sum * (event.syncOk() ? 1.5 : 1) * (leopard ? 3 : 1);
        return new Attack(Math.round(value * 100d) / 100d, false);
    }

    private void enterResult(ObjectNode root, ObjectNode match) {
        // 同一局只结算一次：沙盘推进和定时扫描都可能再次走到这里，重复结算会把胜场算两遍、战报写两条。
        if ("RESULT".equals(match.path("phase").asText())) return;
        boolean prophetA = prophetHit(match, "A", "B");
        boolean prophetB = prophetHit(match, "B", "A");
        match.putObject("prophetResults").put("A", prophetA).put("B", prophetB);
        double a = finalAttack(root, match, "A", prophetA);
        double b = finalAttack(root, match, "B", prophetB);
        String winnerSide = singleRoundWinner(match, a, b);
        if ("A".equals(winnerSide)) {
            match.put("winsA", match.path("winsA").asInt() + 1);
            match.put("roundWinner", "A");
        } else {
            match.put("winsB", match.path("winsB").asInt() + 1);
            match.put("roundWinner", "B");
        }
        match.put("phase", "RESULT");
        match.put("resultReadyAt", System.currentTimeMillis() + resultDisplayMs);
        match.put("matchPoint", true);
        recordRoundReport(root, match, winnerSide);
    }

    /**
     * 每局结算写一条战报。手册第 8 章把"战报日志"作为争议裁定依据，
     * 所以它必须落库成可追加、可回放的记录，而不是只存在于会被整份覆盖的比赛状态 JSON 里。
     */
    private void recordRoundReport(ObjectNode root, ObjectNode match, String winnerSide) {
        String text = roundLabel(match.path("id").asText())
                + " " + sideReport(root, match, "A")
                + " ｜ " + sideReport(root, match, "B")
                + " ｜ 胜者 " + teamName(root, teamForSide(match, winnerSide));
        reports.save(new BattleReport(text, "system"));
    }

    private String sideReport(ObjectNode root, ObjectNode match, String side) {
        JsonNode roll = match.at("/rolls/" + side);
        List<String> faces = new ArrayList<>();
        int sum = 0;
        for (JsonNode die : roll.path("dice")) { faces.add(die.asText()); sum += die.asInt(); }
        StringBuilder text = new StringBuilder(teamName(root, teamForSide(match, side)))
                .append(" 骰子 ").append(String.join("+", faces)).append("=").append(sum);
        if (roll.path("syncOk").asBoolean()) text.append(" 同步×1.5");
        if (roll.path("prophetBonus").asInt() > 0) text.append(" 预言+2");
        if (roll.path("sandboxAssisted").asBoolean()) text.append(" [沙盘助攻]");
        return text.append(" 攻擂 ").append(roll.path("attackPhaseAttack").asDouble())
                .append("，积累 ").append(roll.path("accumulationAttack").asDouble())
                .append("，系数 ").append(roll.path("growthCoefficient").asDouble())
                .append("，盲盒 ").append(roll.path("attackBoostMultiplier").asDouble(1d))
                .append("，总攻击 ").append(roll.path("finalAttack").asDouble()).toString();
    }

    private String roundLabel(String matchId) {
        if (matchId.startsWith("g")) return "【1/4 决赛 " + matchId + "】";
        if (matchId.startsWith("s")) return "【半决赛 " + matchId + "】";
        return "【决赛 " + matchId + "】";
    }

    private String teamName(ObjectNode root, String teamId) {
        for (JsonNode team : root.path("teams")) {
            if (teamId.equals(team.path("id").asText())) return team.path("name").asText(teamId);
        }
        return teamId;
    }

    private String singleRoundWinner(ObjectNode match, double finalA, double finalB) {
        int comparison = compareSingleRound(finalA, finalB,
                match.at("/rolls/A/growthCoefficient").asDouble(1d),
                match.at("/rolls/B/growthCoefficient").asDouble(1d),
                match.at("/rolls/A/dice"), match.at("/rolls/B/dice"));
        if (comparison == 0) comparison = match.path("b").asText().compareTo(match.path("a").asText());
        return comparison >= 0 ? "A" : "B";
    }

    static int compareSingleRound(double finalA, double finalB, double coefficientA, double coefficientB,
                                  JsonNode diceA, JsonNode diceB) {
        int comparison = Double.compare(finalA, finalB);
        if (comparison == 0) comparison = Double.compare(coefficientA, coefficientB);
        return comparison == 0 ? compareDice(diceA, diceB) : comparison;
    }

    static int compareDice(JsonNode diceA, JsonNode diceB) {
        List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
        diceA.forEach(value -> a.add(value.asInt())); diceB.forEach(value -> b.add(value.asInt()));
        a.sort(Comparator.reverseOrder()); b.sort(Comparator.reverseOrder());
        for (int index = 0; index < Math.min(a.size(), b.size()); index++) {
            int comparison = Integer.compare(a.get(index), b.get(index));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.size(), b.size());
    }

    private double finalAttack(ObjectNode root, ObjectNode match, String side, boolean prophetHit) {
        String teamId = teamForSide(match, side);
        ObjectNode team = findTeam(root, teamId);
        double coefficient = team.path("growthCoefficient").asDouble(1d);
        double accumulation = team.path("accumulationPoints").asDouble();
        ObjectNode roll = (ObjectNode) match.at("/rolls/" + side);
        double attackPhase = roll.path("attack").asDouble() + (prophetHit ? 2 : 0);
        double attackBoostMultiplier = attackBoostMultiplier(match, side);
        double finalAttack = round2((accumulation + attackPhase) * coefficient * attackBoostMultiplier);
        roll.put("prophetBonus", prophetHit ? 2 : 0);
        roll.put("accumulationAttack", accumulation);
        roll.put("attackPhaseAttack", round2(attackPhase));
        roll.put("growthCoefficient", coefficient);
        roll.put("attackBoostMultiplier", attackBoostMultiplier);
        roll.put("finalAttack", finalAttack);
        return finalAttack;
    }

    static double attackBoostMultiplier(ObjectNode match, String side) {
        JsonNode claims = match.at("/attackBoost/claims" + side);
        if (!claims.isObject() || claims.isEmpty()) return 1d;
        double sum = 0d;
        int count = 0;
        var values = claims.elements();
        while (values.hasNext()) { sum += values.next().asDouble(1d); count++; }
        return Math.round(sum / count * 100d) / 100d;
    }

    private double round2(double value) { return Math.round(value * 100d) / 100d; }

    private void completeResult(ObjectNode match) {
        match.remove("resultReadyAt");
        match.put("status", "done"); match.put("phase", "FINISHED");
        match.put("winner", "A".equals(match.path("roundWinner").asText()) ? match.path("a").asText() : match.path("b").asText());
    }

    @Scheduled(fixedDelayString = "${app.game.result-scan-ms:500}")
    @Transactional
    public void advanceDueResults() {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        if (record == null) return;
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"parallel".equals(root.path("mode").asText())) return;
            long now = System.currentTimeMillis();
            String stageBefore = root.path("stage").asText();
            Map<String, String> roleStagesBefore = new LinkedHashMap<>();
            root.path("teams").forEach(team -> roleStagesBefore.put(
                    team.path("id").asText(), team.path("roleVoteStage").asText(null)));
            boolean accumulationChanged = revealDueAccumulation(root, now);
            boolean votingChanged = expireVoting(root, now);
            boolean prophetChanged = expireProphets(root, now);
            boolean countdownChanged = expireAttackCountdowns(root, now);
            List<Runnable> onlineActions = new ArrayList<>();
            boolean lineupChanged = expireLineups(root, now, onlineActions);
            boolean sideChanged = expireAttackSides(root, now, onlineActions);
            boolean resultChanged = false;
            var iterator = root.path("matches").elements();
            while (iterator.hasNext()) {
                ObjectNode match = (ObjectNode) iterator.next();
                if ("active".equals(match.path("status").asText())
                        && "RESULT".equals(match.path("phase").asText())
                        && match.path("resultReadyAt").asLong(Long.MAX_VALUE) <= now) {
                    completeResult(match);
                    resultChanged = true;
                }
            }
            boolean changed = accumulationChanged || votingChanged || prophetChanged || countdownChanged
                    || lineupChanged || sideChanged || resultChanged;
            if (!changed) return;
            advance(root);
            driveSandboxAfterAdvance(root);
            record.update(root.toString(), "system");
            states.save(record);
            // 联机侧的动作要等本次状态提交后再执行，否则它们触发的事件监听会读到旧状态并互相覆盖。
            dispatchAfterCommit(onlineActions);
            if (root.hasNonNull("champion")) {
                events.stateChanged();
            } else if (accumulationChanged || prophetChanged || countdownChanged || resultChanged
                    || lineupChanged || sideChanged
                    || !stageBefore.equals(root.path("stage").asText())) {
                events.gameChanged();
            } else if (votingChanged) {
                root.path("teams").forEach(team -> {
                    String teamId = team.path("id").asText();
                    if (!java.util.Objects.equals(roleStagesBefore.get(teamId),
                            team.path("roleVoteStage").asText(null))) events.teamGameChanged(teamId);
                });
            }
        } catch (Exception ignored) { }
    }

    boolean expireVoting(ObjectNode root, long now) {
        if (!"ROLE_VOTE".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode teamNode : root.path("teams")) {
            ObjectNode team = (ObjectNode) teamNode;
            if (!roleElectionComplete(team)
                    && team.path("roleVoteDeadlineAt").asLong(Long.MAX_VALUE) <= now) {
                completeRoleVoteStage(team); changed = true;
            }
        }
        if (changed) startAccumulationIfReady(root);
        return changed;
    }

    boolean expireProphets(ObjectNode root, long now) {
        if (!"ATTACK".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText())
                    || !"PROPHET".equals(match.path("phase").asText())) continue;
            if (!match.has("prophetDeadlineAt")) {
                match.put("prophetDeadlineAt", now + PROPHET_DURATION_MS);
                changed = true;
                continue;
            }
            if (match.path("prophetDeadlineAt").asLong() > now) continue;
            ObjectNode submitted = match.withObject("/submitted");
            ObjectNode prophet = match.withObject("/prophet");
            for (String side : List.of("A", "B")) {
                if (submitted.path("prophet" + side).asBoolean()) continue;
                prophet.putArray(side);
                submitted.put("prophet" + side, true);
                match.withObject("/prophetTimedOut").put(side, true);
            }
            startLineupVoting(match);
            changed = true;
        }
        return changed;
    }

    /** 队长迟迟不交阵容：按在场且非托管的队员自动补齐 5 人（含 ≥1 后端），本局照常开打。 */
    boolean expireLineups(ObjectNode root, long now, List<Runnable> onlineActions) {
        if (!"ATTACK".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText())
                    || !"LINEUP".equals(match.path("phase").asText())) continue;
            if (!match.has("lineupDeadlineAt")) {
                match.put("lineupDeadlineAt", now + LINEUP_DURATION_MS);
                changed = true;
                continue;
            }
            if (match.path("lineupDeadlineAt").asLong() > now) continue;
            ObjectNode submitted = match.withObject("/submitted");
            for (String side : List.of("A", "B")) {
                if (submitted.path("lineup" + side).asBoolean()) continue;
                match.withObject("/lineups").set(side, simulatedLineup(root, teamForSide(match, side)));
                submitted.put("lineup" + side, true);
                match.withObject("/lineupTimedOut").put(side, true);
            }
            startParallelAttack(match);
            String teamA = match.path("a").asText(), teamB = match.path("b").asText();
            String matchId = match.path("id").asText();
            int round = match.path("round").asInt(1);
            List<String> lineupA = lineupOf(match, "A"), lineupB = lineupOf(match, "B");
            onlineActions.add(() -> {
                online.prepare(teamA, matchId, round, lineupA);
                online.prepare(teamB, matchId, round, lineupB);
            });
            changed = true;
        }
        return changed;
    }

    /**
     * 攻擂阶段等不到人时的兜底。规则与手册一致：缺席位置的骰子由系统自动补掷，
     * 但该队本局的同步增益失效，点数仍然有效。
     */
    boolean expireAttackSides(ObjectNode root, long now, List<Runnable> onlineActions) {
        if (!"ATTACK".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText())
                    || !"ATTACKING".equals(match.path("phase").asText())) continue;
            for (String side : List.of("A", "B")) {
                if (match.at("/sideDeadlines/" + side).asLong(Long.MAX_VALUE) > now) continue;
                String teamId = teamForSide(match, side);
                switch (attackSidePhase(match, side)) {
                    case "PREPARING" -> {
                        // 不再等不到场的队员准备，直接下达口令；在场的人照常点击。
                        match.withObject("/sideTimedOut").put(side, "prepare");
                        setAttackSidePhase(match, side, "ROLL");
                        onlineActions.add(() -> online.forceStart(teamId));
                        changed = true;
                    }
                    case "ROLL" -> {
                        // 直接在本次事务里推进，不依赖联机事件回传：事件监听器有自己的事务，
                        // 由本方法触发时无法保证写回，状态与联机会话会就此分叉。
                        match.withObject("/sideTimedOut").put(side, "roll");
                        match.withObject("/timing").put("syncOk" + side, false);   // 人不齐，同步增益失效
                        setAttackSidePhase(match, side, "PITCHER_ROLL");
                        onlineActions.add(() -> online.forceTiming(teamId));
                        changed = true;
                    }
                    case "PITCHER_ROLL" -> {
                        match.withObject("/sideTimedOut").put(side, "pitcher");
                        armSideDeadline(match, side, "PITCHER_ROLL", now);
                        onlineActions.add(() -> { online.forceTiming(teamId); online.finalRoll(teamId); });
                        changed = true;
                    }
                    default -> { }
                }
            }
        }
        return changed;
    }

    private List<String> lineupOf(ObjectNode match, String side) {
        List<String> lineup = new ArrayList<>();
        match.at("/lineups/" + side).forEach(id -> lineup.add(id.asText()));
        return lineup;
    }

    boolean expireAttackCountdowns(ObjectNode root, long now) {
        boolean changed = false;
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText()) || !"ATTACKING".equals(match.path("phase").asText())) continue;
            for (String side : List.of("A", "B")) {
                if (!"COUNTDOWN".equals(attackSidePhase(match, side))) continue;
                if (match.at("/countdownUntil/" + side).asLong(Long.MAX_VALUE) > now) continue;
                setAttackSidePhase(match, side, "ROLL");
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 兜底动作必须在本次事务提交后、且在另一个线程上执行。
     * 这些动作会触发 onDiceReveal 等 @Transactional 监听器，若仍在提交完成的事务上下文里调用，
     * 监听器会加入那个已结束的事务，它的 states.save() 不会真正落库。
     */
    private void dispatchAfterCommit(List<Runnable> actions) {
        if (actions.isEmpty()) return;
        Runnable batch = () -> actions.forEach(ParallelTournamentService::runQuietly);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { timeoutExecutor.execute(batch); }
            });
        } else timeoutExecutor.execute(batch);
    }

    /** 单个兜底动作失败不应该影响其它场次，也不应该让定时扫描中断。 */
    private static void runQuietly(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { }
    }

    /**
     * 管理员强制推进某一场：把该场所有等待中的截止时间提前到现在，
     * 由同一套超时逻辑在下一次扫描（500ms 内）完成推进，不另开一条代码路径。
     */
    @Transactional
    public ForceResult forceMatch(String matchId) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("比赛尚未开始"));
        ObjectNode root = readState(record);
        if (root == null || !root.path("matches").has(matchId)) throw new IllegalArgumentException("场次不存在");
        ObjectNode match = (ObjectNode) root.path("matches").path(matchId);
        if (!"active".equals(match.path("status").asText())) throw new IllegalStateException("该场次已经结束");
        long past = System.currentTimeMillis() - 1;
        List<String> forced = new ArrayList<>();
        String phase = match.path("phase").asText();
        if ("PROPHET".equals(phase)) { match.put("prophetDeadlineAt", past); forced.add("军师预言"); }
        if ("LINEUP".equals(phase)) { match.put("lineupDeadlineAt", past); forced.add("队长选阵容"); }
        if ("ATTACKING".equals(phase)) {
            for (String side : List.of("A", "B")) {
                String sidePhase = attackSidePhase(match, side);
                if (List.of("PREPARING", "ROLL", "PITCHER_ROLL").contains(sidePhase)) {
                    match.withObject("/sideDeadlines").put(side, past);
                    forced.add(side + " 方" + switch (sidePhase) {
                        case "PREPARING" -> "备战准备";
                        case "ROLL" -> "同步点击";
                        default -> "最终投骰";
                    });
                } else if ("COUNTDOWN".equals(sidePhase)) {
                    match.withObject("/countdownUntil").put(side, past);
                    forced.add(side + " 方倒计时");
                }
            }
        }
        if (forced.isEmpty()) throw new IllegalStateException("该场次当前没有可强制推进的环节");
        record.update(root.toString(), "admin"); states.save(record); events.gameChanged();
        return new ForceResult(matchId, phase, forced);
    }

    private void driveSandboxAfterAdvance(ObjectNode root) {
        if (!root.path("sandboxPlayers").isArray() || root.path("sandboxPlayers").isEmpty()) return;
        if ("ACCUMULATION".equals(root.path("stage").asText())) { driveSandboxAccumulation(root); return; }
        for (JsonNode player : root.path("sandboxPlayers")) {
            String teamId = player.path("teamId").asText();
            try {
                ObjectNode lead = activeMatchFor(root, teamId);
                driveSandboxAutomation(root, lead, activeMatches(root));
                return;
            } catch (IllegalStateException ignored) { }
        }
    }

    private boolean prophetHit(ObjectNode match, String prophetSide, String lineupSide) {
        var guess = match.at("/prophet/" + prophetSide);
        var lineup = match.at("/lineups/" + lineupSide);
        if (!guess.isArray() || !lineup.isArray() || guess.size() != 5 || lineup.size() != 5) return false;
        Set<String> guessed = new HashSet<>(), selected = new HashSet<>();
        guess.forEach(v -> guessed.add(v.asText())); lineup.forEach(v -> selected.add(v.asText()));
        return guessed.equals(selected);
    }

    private void resetRound(ObjectNode match) {
        match.remove("roundWinner"); match.remove("matchPoint"); match.remove("resultReadyAt");
        match.remove("prophetResults");
        match.remove("prophetTimedOut");
        match.remove("sandboxRolled");
        match.remove("sandboxReady");
        match.remove("countdownUntil");
        match.remove("lineupDisplayUntil");
        match.remove("attackBoost");
        match.remove("sidePhases");
        match.remove("lineupVotes"); match.remove("lineupVoteCounts"); match.remove("lineupVoteTotals");
        match.remove("lineupVoteDeadlineAt");
        match.put("phase", "PROPHET"); match.set("submitted", mapper.createObjectNode());
        match.put("prophetDeadlineAt", System.currentTimeMillis() + PROPHET_DURATION_MS);
        match.set("prophet", mapper.createObjectNode()); match.set("lineups", mapper.createObjectNode());
        match.set("sync", mapper.createObjectNode()); match.set("rolls", mapper.createObjectNode());
    }

    private void advance(ObjectNode root) {
        ObjectNode matches = (ObjectNode) root.path("matches");
        if (!matches.has("s1") && done(matches, "g1") && done(matches, "g2") && done(matches, "g3") && done(matches, "g4")) {
            createMatch(matches, "s1", winner(matches, "g1"), winner(matches, "g2"));
            createMatch(matches, "s2", winner(matches, "g3"), winner(matches, "g4"));
        }
        if (!matches.has("f1") && done(matches, "s1") && done(matches, "s2")) {
            createMatch(matches, "f1", winner(matches, "s1"), winner(matches, "s2"));
        }
        if (done(matches, "f1") && !root.hasNonNull("champion")) {
            root.put("champion", winner(matches, "f1"));
            reports.save(new BattleReport("【冠军】第 " + root.path("day").asInt(1) + " 天擂主产生："
                    + teamName(root, winner(matches, "f1")), "system"));
            saveDayResult(root);
            controls.findById(1L).ifPresent(control -> control.changePhase("FINISHED"));
        }
    }

    private void saveDayResult(ObjectNode root) {
        int day = root.path("day").asInt(1);
        ObjectNode results = root.withObject("/dayResults");
        String key = "day" + day;
        if (results.has(key)) return;
        ObjectNode result = results.putObject(key);
        result.put("day", day); result.put("champion", root.path("champion").asText());
        result.put("finishedAt", System.currentTimeMillis());
        ObjectNode stats = mapper.createObjectNode();
        ArrayNode teams = result.putArray("teams");
        for (JsonNode source : root.path("teams")) {
            ObjectNode team = teams.addObject();
            String id = source.path("id").asText();
            team.put("id", id); team.put("name", source.path("name").asText());
            team.put("gmv", source.path("gmv").decimalValue());
            team.put("growthCoefficient", source.path("growthCoefficient").asDouble(1d));
            team.put("growthRate", source.path("growthRate").asDouble(
                    (source.path("growthCoefficient").asDouble(1d) - 1d) * 100d));
            team.put("matchWins", 0); team.put("matchLosses", 0);
            team.put("roundWins", 0); team.put("roundLosses", 0);
            ArrayNode players = team.putArray("players");
            source.path("players").forEach(player -> {
                ObjectNode member = players.addObject();
                member.put("id", player.path("id").asText());
                member.put("name", player.path("name").asText());
                member.put("department", player.path("department").asText());
                member.put("role", player.path("role").asText());
                member.put("standIn", player.path("standIn").asBoolean(false));
                member.put("afk", player.path("afk").asBoolean(false));
                member.put("participated", !player.path("managed").asBoolean(false));
            });
            stats.set(id, team);
        }
        ArrayNode matchResults = result.putArray("matches");
        root.path("matches").forEach(matchNode -> {
            if (!"done".equals(matchNode.path("status").asText())) return;
            ObjectNode match = matchResults.addObject();
            String a = matchNode.path("a").asText(), b = matchNode.path("b").asText();
            int winsA = matchNode.path("winsA").asInt(), winsB = matchNode.path("winsB").asInt();
            String winner = matchNode.path("winner").asText();
            match.put("id", matchNode.path("id").asText()); match.put("a", a); match.put("b", b);
            match.put("winsA", winsA); match.put("winsB", winsB); match.put("winner", winner);
            ObjectNode teamA = (ObjectNode) stats.path(a), teamB = (ObjectNode) stats.path(b);
            teamA.put("roundWins", teamA.path("roundWins").asInt() + winsA);
            teamA.put("roundLosses", teamA.path("roundLosses").asInt() + winsB);
            teamB.put("roundWins", teamB.path("roundWins").asInt() + winsB);
            teamB.put("roundLosses", teamB.path("roundLosses").asInt() + winsA);
            ObjectNode winning = winner.equals(a) ? teamA : teamB;
            ObjectNode losing = winner.equals(a) ? teamB : teamA;
            winning.put("matchWins", winning.path("matchWins").asInt() + 1);
            losing.put("matchLosses", losing.path("matchLosses").asInt() + 1);
        });
        if (day == 2) saveOverallResult(root, results);
    }

    private void saveOverallResult(ObjectNode root, ObjectNode dayResults) {
        Map<String, ObjectNode> totals = new LinkedHashMap<>();
        for (String dayKey : List.of("day1", "day2")) {
            for (JsonNode source : dayResults.path(dayKey).path("teams")) {
                String id = source.path("id").asText();
                ObjectNode total = totals.computeIfAbsent(id, ignored -> {
                    ObjectNode value = mapper.createObjectNode();
                    value.put("id", id); value.put("name", source.path("name").asText(id));
                    value.put("totalMatchWins", 0); value.put("totalGrowthRate", 0d);
                    return value;
                });
                double growthRate = source.has("growthRate") ? source.path("growthRate").asDouble()
                        : (source.path("growthCoefficient").asDouble(1d) - 1d) * 100d;
                total.put("totalMatchWins", total.path("totalMatchWins").asInt()
                        + source.path("matchWins").asInt());
                total.put("totalGrowthRate", Math.round((total.path("totalGrowthRate").asDouble()
                        + growthRate) * 10_000d) / 10_000d);
            }
        }
        List<ObjectNode> ranking = new ArrayList<>(totals.values());
        ranking.sort(Comparator
                .comparingInt((ObjectNode team) -> team.path("totalMatchWins").asInt()).reversed()
                .thenComparing(Comparator.comparingDouble(
                        (ObjectNode team) -> team.path("totalGrowthRate").asDouble()).reversed())
                .thenComparing(team -> team.path("id").asText()));
        if (ranking.isEmpty()) return;
        ObjectNode overall = root.putObject("overallResult");
        overall.put("champion", ranking.get(0).path("id").asText());
        overall.put("decidedAt", System.currentTimeMillis());
        ArrayNode standings = overall.putArray("standings");
        ranking.forEach(team -> standings.add(team.deepCopy()));
        root.put("overallChampion", ranking.get(0).path("id").asText());
    }

    @jakarta.annotation.PreDestroy
    void shutdownTimeoutExecutor() { timeoutExecutor.shutdownNow(); }

    @Transactional
    public void resetTwoDayTournament() {
        if (states.existsById(1L)) states.deleteById(1L);
        users.deleteAll(users.findAll().stream().filter(LobbyService::isStandIn).toList());
        List<UserAccount> accounts = users.findAll().stream().filter(u -> "USER".equals(u.getRole())).toList();
        accounts.forEach(user -> { user.setReady(false); user.setAfk(false); });
        users.saveAll(accounts);
        controls.findById(1L).ifPresent(control -> control.changePhase("PREPARING"));
        events.stateChanged();
    }

    private ObjectNode createMatch(ObjectNode matches, String id, String a, String b) {
        ObjectNode match = matches.putObject(id); match.put("id", id); match.put("a", a); match.put("b", b);
        match.put("winsA", 0); match.put("winsB", 0); match.put("round", 1); match.put("status", "active"); resetRound(match);
        return match;
    }
    private boolean done(ObjectNode matches, String id) { return matches.has(id) && "done".equals(matches.path(id).path("status").asText()); }
    private String winner(ObjectNode matches, String id) { return matches.path(id).path("winner").asText(); }
    private record Attack(double value, boolean fatigued) {}
    public record SandboxAssignment(UserAccount player, UserAccount replaced, String teamId, String identity) {}
    public record TestStep(int advancedMatches, String champion, String phase) {}
    public record AdminAccumulationResult(String teamId, int rolledCount, int accumulationPoints, String stage) {}
    public record AdminRoleAssignment(String teamId, String role, String playerId, String nextRole, String stage) {}
    public record ForceResult(String matchId, String phase, List<String> forced) {}
}
