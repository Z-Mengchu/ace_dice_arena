package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.domain.BattleReport;
import com.acedicearena.domain.MatchReport;
import com.acedicearena.domain.PlayerBlindBox;
import com.acedicearena.repository.BattleReportRepository;
import com.acedicearena.repository.GameControlRepository;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.MatchReportRepository;
import com.acedicearena.repository.PerformanceRecordRepository;
import com.acedicearena.repository.PlayerBlindBoxRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class ParallelTournamentService {
    private static final Logger log = LoggerFactory.getLogger(ParallelTournamentService.class);
    private static final BigDecimal GMV_PER_REROLL = BigDecimal.valueOf(100_000L);
    /**
     * 队长投票时限；超时按已投票计票，无人投票按名单顺序取先。
     */
    private static final long VOTE_DURATION_MS = 19_500L;
    /**
     * 队长分 6×5 小队的时限；超时由系统随机均分。
     */
    private static final long SQUAD_FORM_DURATION_MS = 90_000L;
    /**
     * 全员掷骰总窗口：最后一个小队的开掷时刻加小队窗口；超时未掷者由系统代掷。
     */
    private static final long ROLL_DURATION_MS = rollDurationMs();
    /**
     * 321 倒计时：进入 ROLL 阶段后 3 秒才到 go，全员同一时刻起跑。
     */
    private static final long ROLL_COUNTDOWN_MS = 3_000L;
    /**
     * 相邻小队的开掷间隔：第 k 小队（0 起）在 go+k×1s 开掷。
     */
    static final long SQUAD_ROLL_STAGGER_MS = 1_000L;
    /**
     * 最后一个小队的掷骰窗口时长：全局截止 = 最后开掷时刻 + 窗口，早开掷的小队窗口相应更长。
     */
    static final long SQUAD_ROLL_WINDOW_MS = 25_000L;
    /**
     * 真人掷骰时刻允许早于服务端收包时刻的最大值：覆盖真实网络单程延迟，同时限制伪造空间。
     */
    static final long ROLL_TOLERANCE_MS = 250L;
    /**
     * 开盲盒窗口；超时未开视为放弃（按 0 计），系统不再代开。
     */
    private static final long BLIND_BOX_DURATION_MS = 25_000L;
    /**
     * 队长重掷与排阵的战术窗口；超时未用重掷作废、顺序按小队编号锁定。
     */
    private static final long TACTICS_DURATION_MS = 90_000L;
    /**
     * 每轮出战小队的猜阵时限；超时视为放弃，命中记 0。
     */
    private static final long GUESS_DURATION_MS = 30_000L;
    /**
     * 每局猜阵的最短时长：双方都交齐后也要满此时长才揭晓。
     */
    static final long GUESS_MIN_DURATION_MS = 5_000L;
    /**
     * 每局揭晓展示时长，到期自动进入下一局。
     */
    private static final long REVEAL_DURATION_MS = 10_000L;
    static final int SQUAD_COUNT = 6;
    static final int SQUAD_SIZE = 5;
    static final int REROLL_LIMIT_PER_MATCH = 5;
    static final double GUESS_BONUS_PER_HIT = 0.4d;
    static final double GUESS_BONUS_CAP = 10d;
    static final long SYNC_CRIT_WINDOW_MS = 500L;
    static final double SYNC_CRIT_MULTIPLIER = 1.5d;
    /**
     * 系统代掷的掷骰时刻记为 go+1s；含代掷队员的小队不能触发同步暴击。
     */
    static final long AUTO_ROLL_OFFSET_MS = 1_000L;
    static final int[] BLIND_BOX_VALUES = {5, 4, 3, 2, 1, -1, -2};
    static final int[] BLIND_BOX_WEIGHTS = {1, 4, 10, 25, 35, 17, 8};
    /** 三选一盲盒：摆出供玩家选择的盒子数量。 */
    static final int BLIND_BOX_COUNT = 3;

    private final GameStateRepository states;
    private final UserAccountRepository users;
    private final PerformanceRecordRepository performances;
    private final GameControlRepository controls;
    private final ObjectMapper mapper;
    private final LobbyEventService events;
    private final long resultDisplayMs;
    private final BattleReportRepository reports;
    private final MatchReportRepository matchReports;
    private final PlayerBlindBoxRepository blindBoxes;

    /**
     * ROLL 阶段总窗口 = 最后一个小队的开掷时刻（go+5s）+ 小队窗口（15s）= go+20s。
     */
    private static long rollDurationMs() {
        return (SQUAD_COUNT - 1) * SQUAD_ROLL_STAGGER_MS + SQUAD_ROLL_WINDOW_MS;
    }

    public ParallelTournamentService(GameStateRepository states, UserAccountRepository users,
                                     PerformanceRecordRepository performances,
                                     GameControlRepository controls, ObjectMapper mapper, LobbyEventService events,
                                     @Value("${app.game.result-display-ms:16000}") long resultDisplayMs,
                                     BattleReportRepository reports, MatchReportRepository matchReports,
                                     PlayerBlindBoxRepository blindBoxes) {
        this.states = states;
        this.users = users;
        this.performances = performances;
        this.controls = controls;
        this.mapper = mapper;
        this.events = events;
        this.resultDisplayMs = Math.max(0, resultDisplayMs);
        this.reports = reports;
        this.matchReports = matchReports;
        this.blindBoxes = blindBoxes;
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
        root.put("version", 5);
        root.put("mode", "parallel");
        root.put("day", day);
        long startedAt = System.currentTimeMillis();
        root.put("startedAt", startedAt);
        root.put("stage", "CAPTAIN_VOTE");
        root.set("dayResults", dayResults);
        ArrayNode teams = root.putArray("teams");
        List<UserAccount> accounts = users.findAll().stream()
                .filter(u -> "USER".equals(u.getRole()) && AccountService.hasUsableDepartment(u.getDepartment()))
                .sorted(Comparator.comparing(UserAccount::getId)).toList();
        for (int i = 0; i < LobbyService.TEAM_IDS.size(); i++) {
            String teamId = LobbyService.TEAM_IDS.get(i);
            ObjectNode team = teams.addObject();
            team.put("id", teamId);
            team.put("name", LobbyService.TEAM_NAMES.get(i));
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
            int rerollQuota = teamGmv.divide(GMV_PER_REROLL, 0, RoundingMode.FLOOR).intValue();
            team.put("gmv", teamGmv);
            team.put("lastWeekGmv", lastWeekGmv);
            team.put("growthRate", growthRate);
            team.put("growthCoefficient", growthCoefficient);
            team.put("rerollQuota", rerollQuota);
            team.put("rerollUsed", 0);
            team.putObject("roles");
            team.putObject("roleVotes");
            team.put("roleVoteDeadlineAt", startedAt + VOTE_DURATION_MS);
            ArrayNode players = team.putArray("players");
            accounts.stream().filter(u -> teamId.equals(u.getTeamId())).forEach(u -> {
                ObjectNode player = players.addObject();
                player.put("id", "u" + u.getId());
                player.put("name", u.getDisplayName());
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
        if (record == null) record = new GameStateRecord(1L, root.toString(), username);
        else record.update(root.toString(), username);
        states.save(record);
        events.gameChangedNow();
    }

    /**
     * 总冠军加赛：两天候选队胜场与 GMV 全平时，由候选两队以第 2 天快照为班底打一场总决赛。
     */
    @Transactional
    public void startOvertime(String username) {
        GameStateRecord record = states.findById(1L).orElse(null);
        ObjectNode previous = readState(record);
        ObjectNode overallResult = previous != null && previous.path("overallResult").isObject()
                ? (ObjectNode) previous.path("overallResult") : null;
        if (overallResult == null || !"OVERTIME_PENDING".equals(overallResult.path("status").asText()))
            throw new IllegalStateException("当前不需要总冠军加赛");
        ObjectNode dayResults = previous.path("dayResults").isObject()
                ? ((ObjectNode) previous.path("dayResults")).deepCopy() : mapper.createObjectNode();
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 5);
        root.put("mode", "overtime");
        root.put("day", 3);
        long startedAt = System.currentTimeMillis();
        root.put("startedAt", startedAt);
        root.put("stage", "CAPTAIN_VOTE");
        root.set("dayResults", dayResults);
        root.set("overallResult", overallResult.deepCopy());
        Map<String, UserAccount> accountsById = new LinkedHashMap<>();
        users.findAll().forEach(u -> accountsById.put("u" + u.getId(), u));
        Map<String, ObjectNode> snapshots = new LinkedHashMap<>();
        for (JsonNode teamNode : dayResults.path("day2").path("teams"))
            snapshots.put(teamNode.path("id").asText(), (ObjectNode) teamNode);
        List<String> candidateIds = new ArrayList<>();
        overallResult.path("candidates").forEach(candidate -> candidateIds.add(candidate.path("id").asText()));
        ArrayNode teams = root.putArray("teams");
        for (String candidateId : candidateIds) {
            ObjectNode snapshot = snapshots.get(candidateId);
            ObjectNode team = teams.addObject();
            team.put("id", candidateId);
            team.put("name", snapshot != null ? snapshot.path("name").asText(candidateId) : candidateId);
            team.put("shortName", team.path("name").asText().replace("战区", ""));
            BigDecimal teamGmv = snapshot != null && snapshot.path("gmv").isNumber()
                    ? snapshot.path("gmv").decimalValue() : BigDecimal.ZERO;
            team.put("gmv", teamGmv);
            team.put("growthCoefficient", snapshot != null ? snapshot.path("growthCoefficient").asDouble(1d) : 1d);
            team.put("rerollQuota", teamGmv.divide(GMV_PER_REROLL, 0, RoundingMode.FLOOR).intValue());
            team.put("rerollUsed", 0);
            team.putObject("roles");
            team.putObject("roleVotes");
            team.put("roleVoteDeadlineAt", startedAt + VOTE_DURATION_MS);
            ArrayNode players = team.putArray("players");
            if (snapshot == null) continue;
            snapshot.path("players").forEach(member -> {
                ObjectNode player = players.addObject();
                player.put("id", member.path("id").asText());
                player.put("name", member.path("name").asText());
                player.put("department", member.path("department").asText());
                boolean standIn = member.path("standIn").asBoolean(false);
                player.put("standIn", standIn);
                UserAccount account = accountsById.get(member.path("id").asText());
                boolean afk = account != null && account.isAfk();
                player.put("role", account != null
                        ? (account.isFrontEnd() ? "front" : "back") : member.path("role").asText("back"));
                player.put("afk", afk);
                player.put("managed", standIn || afk);
            });
        }
        ObjectNode matches = root.putObject("matches");
        if (candidateIds.size() >= 2) createMatch(matches, "f1", candidateIds.get(0), candidateIds.get(1));
        if (record == null) record = new GameStateRecord(1L, root.toString(), username);
        else record.update(root.toString(), username);
        states.save(record);
        events.gameChangedNow();
    }

    private ObjectNode readState(GameStateRecord record) {
        if (record == null) return null;
        try {
            return (ObjectNode) mapper.readTree(record.getContent());
        } catch (Exception ignored) {
            return null;
        }
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
                entry.put("username", player.getUsername());
                entry.put("displayName", player.getDisplayName());
                entry.put("teamId", assignment.teamId());
                entry.put("playerId", "u" + player.getId());
                entry.put("identity", assignment.identity());
                ObjectNode team = findTeam(root, assignment.teamId());
                ArrayNode players = (ArrayNode) team.path("players");
                String replacedId = "u" + assignment.replaced().getId();
                JsonNode replacedNode = null;
                for (JsonNode candidate : players) {
                    if (replacedId.equals(candidate.path("id").asText())) {
                        replacedNode = candidate;
                        break;
                    }
                }
                for (int index = players.size() - 1; index >= 0; index--) {
                    if (replacedId.equals(players.get(index).path("id").asText())) players.remove(index);
                }
                ObjectNode node = players.addObject();
                node.put("id", "u" + player.getId());
                node.put("name", player.getDisplayName());
                node.put("department", player.getDepartment());
                node.put("role", assignment.identity());
                node.put("standIn", false);
                node.put("afk", false);
                node.put("managed", false);
                // 对局中途替换时，把被替换队员的轮次数据与 Squad 席位一并移交，保证结算能找到人。
                if (replacedNode != null) {
                    for (String field : List.of("dice", "rollTs", "diceFinal", "rerolled",
                            "blindBox", "blindBoxOpened", "autoRolled")) {
                        JsonNode value = replacedNode.path(field);
                        if (!value.isMissingNode()) node.set(field, value);
                    }
                }
                JsonNode squads = team.path("squads");
                if (squads.isArray()) {
                    for (JsonNode squad : squads) {
                        for (int index = 0; index < squad.size(); index++) {
                            if (replacedId.equals(squad.get(index).asText()))
                                ((ArrayNode) squad).set(index, "u" + player.getId());
                        }
                    }
                }
                // BLIND_BOX 阶段开盒结果在 player_blind_box 表：随席位移交改挂到新队员名下
                if ("BLIND_BOX".equals(root.path("stage").asText())) {
                    int day = root.path("day").asInt(1);
                    int round = bracketRoundOf(root);
                    blindBoxes.findByGameDayAndBracketRoundAndPlayerId(day, round, replacedId)
                            .ifPresent(row -> {
                                blindBoxes.delete(row);
                                blindBoxes.save(new PlayerBlindBox(day, round, "u" + player.getId(),
                                        assignment.teamId(), row.getBoxValue()));
                            });
                }
            }
            if ("CAPTAIN_VOTE".equals(root.path("stage").asText())) {
                prepareSandboxCaptainVotes(root);
                startSquadsIfReady(root);
            }
            record.update(root.toString(), "system");
            states.save(record);
            events.gameChanged();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法建立双人沙盘模式", e);
        }
    }

    /**
     * 沙盘玩家之外的队员自动把队长票投给本队沙盘玩家（无沙盘玩家的队伍投给名单第一人），
     * 沙盘玩家自己的一票留给他真人投出；没有沙盘玩家的队伍当场完成选举。
     */
    private void prepareSandboxCaptainVotes(ObjectNode root) {
        Set<String> configured = new HashSet<>();
        root.path("sandboxPlayers").forEach(player -> configured.add(player.path("playerId").asText()));
        for (JsonNode teamNode : root.path("teams")) {
            ObjectNode team = (ObjectNode) teamNode;
            if (hasCaptain(team)) continue;
            String target = null;
            for (JsonNode player : team.path("players")) {
                if (configured.contains(player.path("id").asText())) {
                    target = player.path("id").asText();
                    break;
                }
            }
            if (target == null) {
                for (JsonNode player : team.path("players")) {
                    if (!player.path("managed").asBoolean()) {
                        target = player.path("id").asText();
                        break;
                    }
                }
            }
            if (target == null) continue;
            ObjectNode votes = team.withObject("/roleVotes");
            boolean waitingForSandboxPlayer = false;
            for (JsonNode player : team.path("players")) {
                if (player.path("managed").asBoolean()) continue;
                String voterId = player.path("id").asText();
                if (configured.contains(voterId)) {
                    waitingForSandboxPlayer = true;
                    continue;
                }
                if (!votes.has(voterId)) votes.put(voterId, target);
            }
            if (!waitingForSandboxPlayer) electCaptain(team);
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

    /**
     * 沙盘正式玩家与正式玩家走同一张动作表；沙盘模式只是允许指定真人顶替沙盘队员。
     */
    public void submitSandboxAction(ObjectNode root, UserAccount player, String type, List<String> values) {
        JsonNode configuredPlayer = sandboxPlayer(root, player.getUsername());
        if (configuredPlayer == null) throw new IllegalStateException("当前账号不是沙盘正式玩家");
        dispatchPlayerAction(root, player, type, values);
    }

    public void dispatchPlayerAction(ObjectNode root, UserAccount player, String type, List<String> values) {
        switch (type == null ? "" : type) {
            case "role-vote" -> submitRoleVote(root, player, values);
            case "squad-form" -> submitSquadForm(root, player, values);
            case "blind-box-open" -> openBlindBox(root, player, values);
            case "reroll" -> submitReroll(root, player, values);
            case "squad-order" -> submitSquadOrder(root, player, values);
            case "tactics-confirm" -> submitTacticsConfirm(root, player, true);
            case "tactics-cancel" -> submitTacticsConfirm(root, player, false);
            case "round-guess" -> submitRoundGuess(root, player, values);
            case "pre-guess" -> submitPreGuess(root, player, values);
            case "retract-guess" -> retractGuess(root, player);
            default -> throw new IllegalArgumentException("未知的玩家操作");
        }
    }

    /* ---------- 队长投票 ---------- */

    public void submitRoleVote(ObjectNode root, UserAccount voter, List<String> values) {
        if (!"CAPTAIN_VOTE".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在队长投票阶段");
        if (values.size() != 1) throw new IllegalArgumentException("每次只能选择一名队长候选人");
        ObjectNode team = findTeam(root, voter.getTeamId());
        if (hasCaptain(team)) throw new IllegalStateException("本队队长投票已经完成");
        if (team.path("roleVoteDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("队长投票时间已经结束");
        String candidate = values.getFirst();
        boolean candidateFound = false;
        for (JsonNode player : team.path("players")) {
            if (!player.path("managed").asBoolean() && candidate.equals(player.path("id").asText())) {
                candidateFound = true;
                break;
            }
        }
        if (!candidateFound) throw new IllegalArgumentException("候选人不在本队");
        String voterId = "u" + voter.getId();
        if (!isEligibleVoter(team, voterId))
            throw new IllegalStateException("托管队友不能参与队长投票");
        ObjectNode votes = team.withObject("/roleVotes");
        if (votes.has(voterId)) throw new IllegalStateException("你已经提交过队长选票");
        votes.put(voterId, candidate);
        if (eligibleVoteCount(team) >= eligibleVoterCount(team)) {
            electCaptain(team);
            startSquadsIfReady(root);
        }
    }

    @Transactional
    public AdminRoleAssignment assignCurrentRole(String teamId, String role, String playerId, String adminUsername) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("比赛尚未开始"));
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"CAPTAIN_VOTE".equals(root.path("stage").asText()))
                throw new IllegalStateException("当前不在队长投票阶段");
            if (!"captain".equals(role)) throw new IllegalArgumentException("新规则只需选出队长");
            ObjectNode team = findTeam(root, teamId);
            if (hasCaptain(team)) throw new IllegalStateException("本队队长投票已经完成");
            boolean found = false;
            for (JsonNode player : team.path("players")) {
                if (!player.path("managed").asBoolean() && playerId != null
                        && playerId.equals(player.path("id").asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) throw new IllegalArgumentException("候选人不在本队");
            team.withObject("/roles").put("captain", playerId);
            team.remove("roleVoteDeadlineAt");
            startSquadsIfReady(root);
            record.update(root.toString(), adminUsername);
            states.save(record);
            if (!"CAPTAIN_VOTE".equals(root.path("stage").asText())) events.gameChanged();
            else events.teamGameChanged(teamId);
            return new AdminRoleAssignment(teamId, role, playerId, root.path("stage").asText());
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("管理员指定队长失败", e);
        }
    }

    public void requireRole(ObjectNode root, UserAccount user, String role, String message) {
        ObjectNode team = findTeam(root, user.getTeamId());
        if (!team.path("roles").hasNonNull(role)) throw new IllegalStateException("请等待全队完成队长投票");
        if (!("u" + user.getId()).equals(team.path("roles").path(role).asText()))
            throw new IllegalStateException(message);
    }

    /**
     * 计票产生队长：票数最高者当选，平票或无人投票按队员名单顺序取先。
     */
    private void electCaptain(ObjectNode team) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        team.path("roleVotes").fields().forEachRemaining(vote -> {
            if (isEligibleVoter(team, vote.getKey())) counts.merge(vote.getValue().asText(), 1, Integer::sum);
        });
        String winner = null;
        int best = -1;
        for (JsonNode player : team.path("players")) {
            if (player.path("managed").asBoolean()) continue;
            String id = player.path("id").asText();
            int count = counts.getOrDefault(id, 0);
            if (count > best) {
                winner = id;
                best = count;
            }
        }
        if (winner == null && !team.path("players").isEmpty())
            winner = team.path("players").get(0).path("id").asText();
        if (winner == null) throw new IllegalStateException("队伍缺少成员");
        team.withObject("/roles").put("captain", winner);
        team.remove("roleVoteDeadlineAt");
    }

    private boolean hasCaptain(ObjectNode team) {
        return team.path("roles").hasNonNull("captain");
    }

    private boolean isEligibleVoter(ObjectNode team, String playerId) {
        for (JsonNode player : team.path("players")) {
            if (playerId.equals(player.path("id").asText())) return !player.path("managed").asBoolean();
        }
        return false;
    }

    private int eligibleVoterCount(ObjectNode team) {
        int count = 0;
        for (JsonNode player : team.path("players")) if (!player.path("managed").asBoolean()) count++;
        return count;
    }

    private int eligibleVoteCount(ObjectNode team) {
        int count = 0;
        var voters = team.path("roleVotes").fieldNames();
        while (voters.hasNext()) if (isEligibleVoter(team, voters.next())) count++;
        return count;
    }

    boolean expireVoting(ObjectNode root, long now) {
        if (!"CAPTAIN_VOTE".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode teamNode : root.path("teams")) {
            ObjectNode team = (ObjectNode) teamNode;
            // 无成员队伍无人可投，直接跳过，避免 electCaptain 抛异常导致整轮扫描回滚卡死
            if (team.path("players").isEmpty()) continue;
            if (!hasCaptain(team) && team.path("roleVoteDeadlineAt").asLong(Long.MAX_VALUE) <= now) {
                electCaptain(team);
                changed = true;
            }
        }
        if (changed) startSquadsIfReady(root);
        return changed;
    }

    /* ---------- 分队 ---------- */

    boolean startSquadsIfReady(ObjectNode root) {
        if (!"CAPTAIN_VOTE".equals(root.path("stage").asText())) return false;
        // 无成员队伍永远选不出队长，不作为进入分队阶段的前置条件
        for (JsonNode team : root.path("teams"))
            if (!team.path("players").isEmpty() && !hasCaptain((ObjectNode) team)) return false;
        root.put("stage", "SQUAD_FORM");
        root.put("stageDeadlineAt", System.currentTimeMillis() + SQUAD_FORM_DURATION_MS);
        return true;
    }

    /**
     * 队长提交分队：selections 为本队 30 个队员 id，按 6×5 顺序扁平排列，序号即 1~6 号小队。
     */
    public void submitSquadForm(ObjectNode root, UserAccount captain, List<String> values) {
        if (!"SQUAD_FORM".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在分队阶段");
        requireRole(root, captain, "captain", "只有当选队长可以分队");
        ObjectNode team = findTeam(root, captain.getTeamId());
        if (team.has("squads")) throw new IllegalStateException("本队分队已经锁定");
        Set<String> roster = new HashSet<>();
        team.path("players").forEach(player -> roster.add(player.path("id").asText()));
        if (values.size() != SQUAD_COUNT * SQUAD_SIZE
                || new HashSet<>(values).size() != values.size()
                || !roster.equals(new HashSet<>(values)))
            throw new IllegalArgumentException("分队必须包含本队全部 30 名队员且不重复");
        writeSquads(team, values);
        startRoundFlowIfReady(root);
    }

    private void writeSquads(ObjectNode team, List<String> orderedIds) {
        ArrayNode squads = team.putArray("squads");
        for (int squad = 0; squad < SQUAD_COUNT; squad++) {
            ArrayNode members = squads.addArray();
            for (int index = squad * SQUAD_SIZE; index < (squad + 1) * SQUAD_SIZE; index++)
                members.add(orderedIds.get(index));
        }
    }

    private void randomSquads(ObjectNode team) {
        List<String> ids = new ArrayList<>();
        team.path("players").forEach(player -> ids.add(player.path("id").asText()));
        java.util.Collections.shuffle(ids);
        writeSquads(team, ids);
    }

    private boolean allSquadsFormed(ObjectNode root) {
        for (JsonNode team : root.path("teams")) if (!team.has("squads")) return false;
        return true;
    }

    boolean startRoundFlowIfReady(ObjectNode root) {
        if (!"SQUAD_FORM".equals(root.path("stage").asText())) return false;
        if (!allSquadsFormed(root)) return false;
        startRoundFlow(root);
        return true;
    }

    boolean expireSquadForm(ObjectNode root, long now) {
        if (!"SQUAD_FORM".equals(root.path("stage").asText())) return false;
        if (!allSquadsFormed(root)) {
            if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) > now) return false;
            for (JsonNode teamNode : root.path("teams")) {
                ObjectNode team = (ObjectNode) teamNode;
                if (!team.has("squads")) randomSquads(team);
            }
        }
        startRoundFlow(root);
        return true;
    }

    /* ---------- 轮次流程：ROLL → BLIND_BOX → TACTICS ---------- */

    /**
     * 每个 bracket 轮次重来一次掷骰/盲盒/重掷：重置在赛队伍的轮次数据并进入 ROLL。
     */
    private void startRoundFlow(ObjectNode root) {
        long now = System.currentTimeMillis();
        long goAt = now + ROLL_COUNTDOWN_MS;
        root.put("stage", "ROLL");
        root.put("rollGoAt", goAt);
        ArrayNode rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < SQUAD_COUNT; k++) rollOpenAts.add(goAt + k * SQUAD_ROLL_STAGGER_MS);
        root.put("stageDeadlineAt", goAt + ROLL_DURATION_MS);
        Set<String> activeTeams = activeTeamIds(root);
        for (JsonNode teamNode : root.path("teams")) {
            ObjectNode team = (ObjectNode) teamNode;
            if (!activeTeams.contains(team.path("id").asText())) continue;
            team.put("rerollUsed", 0);
            team.putArray("rerollLog");
            team.put("squadOrderLocked", false);
            team.put("tacticsConfirmed", false);
            for (JsonNode playerNode : team.path("players")) {
                ObjectNode player = (ObjectNode) playerNode;
                player.remove(List.of("dice", "rollTs", "diceFinal", "rerolled",
                        "blindBox", "blindBoxOpened", "autoRolled"));
            }
        }
    }

    /**
     * 第 squadIndex 小队（0 起）的开掷时刻；缺时刻表时按 rollGoAt 推导，都没有则返回 Long.MIN_VALUE 表示不拦截。
     */
    private long rollOpenAt(ObjectNode root, int squadIndex) {
        JsonNode opens = root.path("rollOpenAts");
        if (opens.isArray() && squadIndex < opens.size()) return opens.get(squadIndex).asLong();
        if (root.hasNonNull("rollGoAt"))
            return root.path("rollGoAt").asLong() + squadIndex * SQUAD_ROLL_STAGGER_MS;
        return Long.MIN_VALUE;
    }

    /**
     * 成员所在小队下标（0 起）；查不到（手工构造状态）按 0 号小队兜底。
     */
    private int squadIndexOf(ObjectNode team, String playerId) {
        JsonNode squads = team.path("squads");
        if (squads.isArray()) {
            for (int k = 0; k < squads.size(); k++)
                if (contains(squads.get(k), playerId)) return k;
        }
        return 0;
    }

    /**
     * 在赛队伍全员是否都已有点数（真人掷或系统代掷）。
     */
    private boolean allActiveRolled(ObjectNode root) {
        Set<String> activeTeams = activeTeamIds(root);
        for (JsonNode teamNode : root.path("teams")) {
            if (!activeTeams.contains(teamNode.path("id").asText())) continue;
            for (JsonNode player : teamNode.path("players")) if (!player.has("dice")) return false;
        }
        return true;
    }

    /**
     * 全员（真人）掷齐立即进盲盒；截止时刻全员统一为 stageDeadlineAt，到点由 doRoll 代掷未掷者（force 推进依赖它）。
     */
    boolean expireRoll(ObjectNode root, long now) {
        if (!"ROLL".equals(root.path("stage").asText())) return false;
        if ((root.hasNonNull("rollGoAt") || root.has("rollOpenAts")) && allActiveRolled(root)) {
            enterBlindBox(root);
            return true;
        }
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) > now) return false;
        doRoll(root);
        return true;
    }

    private void doRoll(ObjectNode root) {
        Set<String> activeTeams = activeTeamIds(root);
        for (JsonNode teamNode : root.path("teams")) {
            if (!activeTeams.contains(teamNode.path("id").asText())) continue;
            ObjectNode team = (ObjectNode) teamNode;
            for (JsonNode playerNode : team.path("players")) {
                ObjectNode player = (ObjectNode) playerNode;
                if (player.has("dice")) continue;
                int die = ThreadLocalRandom.current().nextInt(1, 7);
                player.put("dice", die);
                player.put("diceFinal", die);
                player.put("rollTs", rollOpenAt(root, squadIndexOf(team, player.path("id").asText()))
                        + AUTO_ROLL_OFFSET_MS);
                player.put("autoRolled", true);
            }
        }
        enterBlindBox(root);
    }

    private void enterBlindBox(ObjectNode root) {
        root.put("stage", "BLIND_BOX");
        root.put("stageDeadlineAt", System.currentTimeMillis() + BLIND_BOX_DURATION_MS);
    }

    /* ---------- 真人联机掷骰 ---------- */

    /**
     * 真人掷骰落库：客户端只提交点击时刻（已由校准偏移归一化），点数在事务内随机产生。
     * 时刻夹取到 [now-250ms, now]，且不早于 go（抢跑按 go 时刻计）。
     */
    @Transactional
    public LiveRoll recordLiveRoll(String username, long clientTs) {
        // 用户解析不持行锁，与 PlayerActionService.submit 的锁外查用户对齐
        UserAccount user = users.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("账号不存在"));
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("比赛尚未开始"));
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!"ROLL".equals(root.path("stage").asText()))
                throw new IllegalStateException("当前不在掷骰阶段");
            String teamId = user.getTeamId();
            if (teamId == null || !activeTeamIds(root).contains(teamId))
                throw new IllegalStateException("本队本轮没有比赛");
            ObjectNode team = findTeam(root, teamId);
            ObjectNode player = findPlayer(team, "u" + user.getId());
            if (player == null) throw new IllegalStateException("当前账号不在本队参赛名单中");
            long now = System.currentTimeMillis();
            long goAt = root.path("rollGoAt").asLong(now);
            if (now < goAt) throw new IllegalStateException("掷骰还未开始，请等待倒计时结束");
            if (now > root.path("stageDeadlineAt").asLong(Long.MIN_VALUE))
                throw new IllegalStateException("本轮掷骰已截止");
            long openAt = rollOpenAt(root, squadIndexOf(team, player.path("id").asText()));
            if (now < openAt) throw new IllegalStateException("还没轮到你们小队掷骰");
            // 开掷时刻按小队错开，但截止时刻全员统一为 stageDeadlineAt
            if (player.has("dice")) throw new IllegalStateException("你本轮已经掷过骰子");
            long rollTs = Math.max(Math.min(Math.max(clientTs, now - ROLL_TOLERANCE_MS), now), goAt);
            int die = ThreadLocalRandom.current().nextInt(1, 7);
            player.put("dice", die);
            player.put("diceFinal", die);
            player.put("rollTs", rollTs);
            // 全员掷齐立即进盲盒，不等阶段截止
            boolean advanced = allActiveRolled(root);
            if (advanced) enterBlindBox(root);
            record.update(root.toString(), username);
            states.save(record);
            // 阶段推进走立即广播，普通掷骰走合并广播
            if (advanced) events.gameChangedNow();
            else events.gameChanged();
            return new LiveRoll(die, rollTs);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("掷骰失败", e);
        }
    }

    /**
     * 掷骰席位查询：供 /api/roll-assignment 与 join 令牌发放共用；不在 ROLL 或本队不在赛时 eligible=false。
     */
    @Transactional(readOnly = true)
    public RollAssignmentView rollAssignment(String username) {
        UserAccount user = users.findByUsername(username).orElse(null);
        String teamId = user == null ? null : user.getTeamId();
        GameStateRecord record = states.findById(1L).orElse(null);
        if (record == null) return new RollAssignmentView(false, null, null, null, false, teamId, null, null, null);
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            String stage = root.path("stage").asText(null);
            Long rollGoAt = root.hasNonNull("rollGoAt") ? root.path("rollGoAt").asLong() : null;
            Long deadline = root.hasNonNull("stageDeadlineAt") ? root.path("stageDeadlineAt").asLong() : null;
            boolean eligible = false;
            boolean alreadyRolled = false;
            Integer squadIndex = null;
            Long rollOpenAt = null;
            Long rollDeadlineAt = null;
            if ("ROLL".equals(stage) && teamId != null && activeTeamIds(root).contains(teamId)) {
                eligible = true;
                ObjectNode team = findTeam(root, teamId);
                ObjectNode player = user == null ? null : findPlayer(team, "u" + user.getId());
                alreadyRolled = player != null && player.has("dice");
                if (player != null) {
                    squadIndex = squadIndexOf(team, player.path("id").asText());
                    long openAt = rollOpenAt(root, squadIndex);
                    if (openAt != Long.MIN_VALUE) rollOpenAt = openAt;
                    // 截止时刻全员统一为全局 stageDeadlineAt，不再按小队截断
                    rollDeadlineAt = deadline;
                }
            }
            return new RollAssignmentView(eligible, stage, rollGoAt, deadline, alreadyRolled, teamId,
                    squadIndex, rollOpenAt, rollDeadlineAt);
        } catch (Exception e) {
            throw new IllegalStateException("比赛状态无法读取", e);
        }
    }

    /* ---------- 玩家视角脱敏 ---------- */

    /**
     * 玩家级轮次数据字段：对敌方队伍一律剥离。
     */
    private static final List<String> PLAYER_ROUND_FIELDS = List.of(
            "dice", "rollTs", "diceFinal", "rerolled", "blindBox", "blindBoxOpened", "autoRolled");

    /**
     * 玩家视角不使用的队伍级业绩字段：摘要视图一律剥离（管理员视图保留全量）。
     */
    private static final List<String> TEAM_PERFORMANCE_FIELDS = List.of(
            "shortName", "gmv", "lastWeekGmv", "growthRate", "growthCoefficient");

    /**
     * 普通玩家视角的 game_state 视图（深拷贝，不改缓存原对象）：
     * 本队完整；敌方队伍剥离小队编排、球员级点数/盲盒、重掷日志与投票明细（猜阵时看不到敌方历史出战）；
     * 所有场次的 guesses 内容密封，替换为 guessStatus 提交状态布尔。
     */
    public JsonNode publicStateView(JsonNode state, String teamId, String playerId) {
        ObjectNode view = state.deepCopy();
        String ownTeamId = teamId;
        if (ownTeamId == null && playerId != null) {
            for (JsonNode team : view.path("teams")) {
                for (JsonNode player : team.path("players")) {
                    if (playerId.equals(player.path("id").asText())) {
                        ownTeamId = team.path("id").asText();
                        break;
                    }
                }
                if (ownTeamId != null) break;
            }
        }
        for (JsonNode teamNode : view.path("teams")) {
            ObjectNode team = (ObjectNode) teamNode;
            team.remove(TEAM_PERFORMANCE_FIELDS);
            for (JsonNode playerNode : team.path("players"))
                ((ObjectNode) playerNode).remove("rollTs");
            if (team.path("id").asText().equals(ownTeamId)) continue;
            team.remove(List.of("squads", "squadOrderLocked", "rerollLog", "roleVotes"));
            for (JsonNode playerNode : team.path("players"))
                ((ObjectNode) playerNode).remove(PLAYER_ROUND_FIELDS);
        }
        for (JsonNode matchNode : view.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            sealGuesses(match);
            summarizeRounds(match);
        }
        return view;
    }

    /**
     * 单场对局的玩家视角（深拷贝）：guesses 内容密封为 guessStatus 提交状态布尔；
     * rounds 保留完整战力明细，供详情接口按需下发。
     */
    public JsonNode publicMatchView(JsonNode matchNode) {
        ObjectNode match = (ObjectNode) matchNode.deepCopy();
        sealGuesses(match);
        return match;
    }

    /**
     * 猜阵密封：内容不可见，只能看到提交状态；提前猜阵同样密封为 preGuessStatus。
     */
    private void sealGuesses(ObjectNode match) {
        JsonNode guesses = match.path("guesses");
        ObjectNode status = mapper.createObjectNode();
        for (String side : List.of("A", "B")) {
            ObjectNode sideStatus = status.putObject(side);
            guesses.path(side).fieldNames().forEachRemaining(id -> sideStatus.put(id, true));
        }
        match.set("guessStatus", status);
        match.remove("guesses");
        JsonNode preGuesses = match.path("preGuesses");
        ObjectNode preStatus = mapper.createObjectNode();
        preGuesses.fields().forEachRemaining(roundEntry -> {
            ObjectNode roundStatus = preStatus.putObject(roundEntry.getKey());
            for (String side : List.of("A", "B")) {
                ObjectNode sideStatus = roundStatus.putObject(side);
                roundEntry.getValue().path(side).fieldNames().forEachRemaining(id -> sideStatus.put(id, true));
            }
        });
        match.set("preGuessStatus", preStatus);
        match.remove("preGuesses");
    }

    /**
     * 摘要视图的 rounds 只保留局号与胜负（记分格渲染够用），战力明细由单场详情接口按需下发。
     */
    private void summarizeRounds(ObjectNode match) {
        ArrayNode summary = mapper.createArrayNode();
        for (JsonNode round : match.path("rounds")) {
            ObjectNode entry = summary.addObject();
            entry.put("round", round.path("round").asInt());
            if (round.hasNonNull("winner")) entry.put("winner", round.path("winner").asText());
            else entry.putNull("winner");
        }
        match.set("rounds", summary);
    }

    int drawBlindBox() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        int cumulative = 0;
        for (int i = 0; i < BLIND_BOX_VALUES.length; i++) {
            cumulative += BLIND_BOX_WEIGHTS[i];
            if (roll < cumulative) return BLIND_BOX_VALUES[i];
        }
        return BLIND_BOX_VALUES[BLIND_BOX_VALUES.length - 1];
    }

    /** 三选一：一次抽出全部盒子的内容，玩家选中的那个落库，其余仅用于展示对比。 */
    int[] drawBlindBoxes() {
        int[] boxes = new int[BLIND_BOX_COUNT];
        for (int i = 0; i < boxes.length; i++) boxes[i] = drawBlindBox();
        return boxes;
    }

    /**
     * 开盒结果：value 为落库点数档；boxes/picked 仅本次开盒的响应携带（陪跑值不落库），
     * 幂等重放（并发重复或已开过）时 boxes 为 null、picked 为 -1，前端按刷新重进处理。
     */
    public record BlindBoxResult(int value, int[] boxes, int picked) {}

    /** 解析可选的盒子序号；缺省时服务端随机选一个（玩家主动开盒为前提，系统不代开）。 */
    private int parseBoxIndex(List<String> values) {
        if (values == null || values.isEmpty())
            return ThreadLocalRandom.current().nextInt(BLIND_BOX_COUNT);
        int index;
        try {
            index = Integer.parseInt(values.getFirst());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("盲盒序号必须是数字");
        }
        if (index < 0 || index >= BLIND_BOX_COUNT) throw new IllegalArgumentException("盲盒序号超出范围");
        return index;
    }

    public void openBlindBox(ObjectNode root, UserAccount user) {
        openBlindBox(root, user, List.of());
    }

    public void openBlindBox(ObjectNode root, UserAccount user, List<String> values) {
        if (!"BLIND_BOX".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在开盲盒阶段");
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("开盲盒时间已经结束");
        ObjectNode team = findTeam(root, user.getTeamId());
        if (!activeTeamIds(root).contains(team.path("id").asText()))
            throw new IllegalStateException("本队本轮没有比赛");
        ObjectNode player = findPlayer(team, "u" + user.getId());
        if (player == null) throw new IllegalStateException("当前账号不在本队参赛名单中");
        if (player.has("blindBox")) throw new IllegalStateException("你已经开过本轮盲盒");
        int[] boxes = drawBlindBoxes();
        player.put("blindBox", boxes[parseBoxIndex(values)]);
        player.put("blindBoxOpened", true);
        startTacticsIfReady(root);
    }

    private boolean startTacticsIfReady(ObjectNode root) {
        if (!"BLIND_BOX".equals(root.path("stage").asText())) return false;
        Set<String> activeTeams = activeTeamIds(root);
        for (JsonNode teamNode : root.path("teams")) {
            if (!activeTeams.contains(teamNode.path("id").asText())) continue;
            for (JsonNode player : teamNode.path("players")) if (!player.has("blindBox")) return false;
        }
        startTactics(root);
        return true;
    }

    /* ---------- 开盲盒（锁定 game_state 后独立成行） ---------- */

    /**
     * 在调用方事务内先锁定 game_state，再校验、写入 player_blind_box 并推进阶段。
     * 同一玩家的并发请求由状态行锁串行化，已有记录直接返回原结果。
     */
    @Transactional
    public BlindBoxResult openBlindBoxLocked(UserAccount user, Integer boxIndex) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("主持人尚未创建比赛"));
        ObjectNode root = readState(record);
        if (root == null) throw new IllegalStateException("比赛状态无法读取");
        if (!"parallel".equals(root.path("mode").asText()))
            throw new IllegalStateException("当前比赛不是并行赛制");
        if (!"BLIND_BOX".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在开盲盒阶段");
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("开盲盒时间已经结束");
        ObjectNode team = findTeam(root, user.getTeamId());
        if (!activeTeamIds(root).contains(team.path("id").asText()))
            throw new IllegalStateException("本队本轮没有比赛");
        String playerId = "u" + user.getId();
        ObjectNode player = findPlayer(team, playerId);
        if (player == null) throw new IllegalStateException("当前账号不在本队参赛名单中");
        if (player.has("blindBox"))
            return new BlindBoxResult(player.path("blindBox").asInt(), null, -1);
        int day = root.path("day").asInt(1);
        int round = bracketRoundOf(root);
        var existing = blindBoxes.findByGameDayAndBracketRoundAndPlayerId(day, round, playerId);
        if (existing.isPresent()) return new BlindBoxResult(existing.get().getBoxValue(), null, -1);
        int picked = boxIndex == null
                ? ThreadLocalRandom.current().nextInt(BLIND_BOX_COUNT) : boxIndex;
        if (picked < 0 || picked >= BLIND_BOX_COUNT) throw new IllegalArgumentException("盲盒序号超出范围");
        int[] boxes = drawBlindBoxes();
        int value = boxes[picked];
        blindBoxes.saveAndFlush(new PlayerBlindBox(day, round, playerId, team.path("id").asText(), value));
        if (allBlindBoxesOpened(root, day, round)) {
            applyBlindBoxRows(root);
            startTactics(root);
            record.update(root.toString(), "system");
            states.save(record);
            // 阶段推进：立即广播，玩家端不依赖到点兜底
            events.gameChangedNow();
        }
        return new BlindBoxResult(value, boxes, picked);
    }

    /**
     * 当天第几个 bracket 轮次：按进行中场次 id 前缀推导（g=1/4 决赛、s=半决赛、f=决赛/加赛）。
     */
    private int bracketRoundOf(ObjectNode root) {
        for (JsonNode match : root.path("matches")) {
            if (!"active".equals(match.path("status").asText())) continue;
            String id = match.path("id").asText();
            return id.startsWith("g") ? 1 : id.startsWith("s") ? 2 : 3;
        }
        return 1;
    }

    private Set<String> activePlayerIds(ObjectNode root) {
        Set<String> activeTeams = activeTeamIds(root);
        Set<String> ids = new HashSet<>();
        for (JsonNode team : root.path("teams")) {
            if (!activeTeams.contains(team.path("id").asText())) continue;
            for (JsonNode player : team.path("players")) ids.add(player.path("id").asText());
        }
        return ids;
    }

    /**
     * 计数判定：本轮已开盒行覆盖全部在赛队员（只计仍在名册内的行，被替换离队的行不影响判定）。
     */
    private boolean allBlindBoxesOpened(ObjectNode root, int day, int round) {
        Set<String> active = activePlayerIds(root);
        long opened = blindBoxes.findByGameDayAndBracketRound(day, round).stream()
                .filter(row -> active.contains(row.getPlayerId())).count();
        return opened >= active.size();
    }

    /**
     * 把本轮 player_blind_box 行写入 JSON 的 player 节点；未开者不写字段（按放弃计 0 分）。
     */
    private void applyBlindBoxRows(ObjectNode root) {
        int day = root.path("day").asInt(1);
        int round = bracketRoundOf(root);
        for (PlayerBlindBox row : blindBoxes.findByGameDayAndBracketRound(day, round)) {
            ObjectNode team = findTeamOrNull(root, row.getTeamId());
            ObjectNode player = team == null ? null : findPlayer(team, row.getPlayerId());
            if (player == null || player.has("blindBox")) continue;
            player.put("blindBox", row.getBoxValue());
            player.put("blindBoxOpened", true);
        }
    }

    private ObjectNode findTeamOrNull(ObjectNode root, String teamId) {
        for (JsonNode candidate : root.path("teams"))
            if (teamId.equals(candidate.path("id").asText())) return (ObjectNode) candidate;
        return null;
    }

    /**
     * 读取层注入：BLIND_BOX 阶段 game_state 行内还没有盲盒字段（结果在 player_blind_box 表），
     * 快照组装时把本轮已开结果注入 player 节点，玩家/大厅/大屏视图与脱敏逻辑保持不变。
     */
    public void injectBlindBoxResults(JsonNode state) {
        if (!(state instanceof ObjectNode root)) return;
        if (!"BLIND_BOX".equals(root.path("stage").asText())) return;
        applyBlindBoxRows(root);
    }

    boolean expireBlindBox(ObjectNode root, long now) {
        if (!"BLIND_BOX".equals(root.path("stage").asText())) return false;
        int day = root.path("day").asInt(1);
        int round = bracketRoundOf(root);
        if (allBlindBoxesOpened(root, day, round)) {
            applyBlindBoxRows(root);
            startTactics(root);
            return true;
        }
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) > now) return false;
        // 超时未开视为放弃（按 0 计），系统不再代开；已开结果合并回 JSON
        applyBlindBoxRows(root);
        startTactics(root);
        return true;
    }

    private void startTactics(ObjectNode root) {
        root.put("stage", "TACTICS");
        root.put("stageDeadlineAt", System.currentTimeMillis() + TACTICS_DURATION_MS);
    }

    /* ---------- 战术窗口：重掷 + 排阵 ---------- */

    /**
     * 队长指定本队任意队员重掷：只改点数，保留原掷骰时刻（暴击判定不受影响）与盲盒。
     */
    public void submitReroll(ObjectNode root, UserAccount captain, List<String> values) {
        if (!"TACTICS".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在战术阶段");
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("战术阶段时间已经结束");
        requireRole(root, captain, "captain", "只有当选队长可以重掷");
        ObjectNode team = findTeam(root, captain.getTeamId());
        if (!activeTeamIds(root).contains(team.path("id").asText()))
            throw new IllegalStateException("本队本轮没有比赛");
        if (team.path("tacticsConfirmed").asBoolean(false))
            throw new IllegalStateException("已确认完成战术布置，请先取消确认再调整");
        int limit = Math.min(team.path("rerollQuota").asInt(), REROLL_LIMIT_PER_MATCH);
        if (team.path("rerollUsed").asInt() >= limit)
            throw new IllegalStateException("本场重掷次数已用完");
        if (values.size() != 1) throw new IllegalArgumentException("每次只能指定一名队员重掷");
        ObjectNode player = findPlayer(team, values.getFirst());
        if (player == null) throw new IllegalArgumentException("只能选择本队队员");
        if (!player.has("diceFinal")) throw new IllegalStateException("该队员本轮尚未掷骰");
        int from = player.path("diceFinal").asInt();
        int to = ThreadLocalRandom.current().nextInt(1, 7);
        player.put("diceFinal", to);
        player.put("rerolled", true);
        team.put("rerollUsed", team.path("rerollUsed").asInt() + 1);
        ObjectNode log = team.withArray("rerollLog").addObject();
        log.put("playerId", player.path("id").asText());
        log.put("playerName", player.path("name").asText());
        log.put("from", from);
        log.put("to", to);
    }

    /**
     * 队长重排 1~6 号小队出场顺序：selections 为小队编号的目标排列，如 ["3","1","2","4","5","6"]。
     */
    public void submitSquadOrder(ObjectNode root, UserAccount captain, List<String> values) {
        if (!"TACTICS".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在战术阶段");
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("战术阶段时间已经结束");
        requireRole(root, captain, "captain", "只有当选队长可以调整出场顺序");
        ObjectNode team = findTeam(root, captain.getTeamId());
        if (!activeTeamIds(root).contains(team.path("id").asText()))
            throw new IllegalStateException("本队本轮没有比赛");
        if (team.path("tacticsConfirmed").asBoolean(false))
            throw new IllegalStateException("已确认完成战术布置，请先取消确认再调整");
        if (team.path("squadOrderLocked").asBoolean(false))
            throw new IllegalStateException("本队出场顺序已经锁定");
        JsonNode squads = team.path("squads");
        if (!squads.isArray() || squads.size() != SQUAD_COUNT)
            throw new IllegalStateException("本队尚未完成分队");
        if (values.size() != SQUAD_COUNT) throw new IllegalArgumentException("必须提交 6 个小队的出场顺序");
        Set<Integer> seen = new HashSet<>();
        int[] order = new int[SQUAD_COUNT];
        for (int i = 0; i < SQUAD_COUNT; i++) {
            int squadNo;
            try {
                squadNo = Integer.parseInt(values.get(i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("小队编号必须是 1~6");
            }
            if (squadNo < 1 || squadNo > SQUAD_COUNT || !seen.add(squadNo))
                throw new IllegalArgumentException("小队编号必须是 1~6 且不重复");
            order[i] = squadNo;
        }
        ArrayNode reordered = mapper.createArrayNode();
        for (int squadNo : order) reordered.add(squads.get(squadNo - 1).deepCopy());
        team.set("squads", reordered);
        team.put("squadOrderLocked", true);
    }

    /**
     * 队长确认战术布置完成；取消确认可继续调整。所有在赛队伍都确认后立即进入对局。
     */
    public void submitTacticsConfirm(ObjectNode root, UserAccount captain, boolean confirmed) {
        if (!"TACTICS".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在战术阶段");
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("战术阶段时间已经结束");
        requireRole(root, captain, "captain", "只有当选队长可以确认战术布置");
        ObjectNode team = findTeam(root, captain.getTeamId());
        if (!activeTeamIds(root).contains(team.path("id").asText()))
            throw new IllegalStateException("本队本轮没有比赛");
        team.put("tacticsConfirmed", confirmed);
        if (confirmed && allActiveTeamsConfirmed(root)) startBattle(root);
    }

    private boolean allActiveTeamsConfirmed(ObjectNode root) {
        Set<String> activeTeams = activeTeamIds(root);
        for (JsonNode teamNode : root.path("teams")) {
            if (!activeTeams.contains(teamNode.path("id").asText())) continue;
            if (!teamNode.path("tacticsConfirmed").asBoolean(false)) return false;
        }
        return true;
    }

    boolean expireTactics(ObjectNode root, long now) {
        if (!"TACTICS".equals(root.path("stage").asText())) return false;
        if (root.path("stageDeadlineAt").asLong(Long.MAX_VALUE) > now) return false;
        startBattle(root);
        return true;
    }

    private void startBattle(ObjectNode root) {
        long now = System.currentTimeMillis();
        root.put("stage", "BATTLE");
        root.remove("stageDeadlineAt");
        Set<String> activeTeams = activeTeamIds(root);
        for (JsonNode teamNode : root.path("teams")) {
            if (!activeTeams.contains(teamNode.path("id").asText())) continue;
            ((ObjectNode) teamNode).put("squadOrderLocked", true);
        }
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText())) continue;
            match.put("phase", "BATTLE");
            match.put("round", 1);
            match.put("roundPhase", "GUESS");
            match.put("winsA", 0);
            match.put("winsB", 0);
            match.set("rounds", mapper.createArrayNode());
            match.set("guesses", mapper.createObjectNode());
            match.set("preGuesses", mapper.createObjectNode());
            match.put("guessDeadlineAt", now + GUESS_DURATION_MS);
            match.put("guessOpenedAt", now);
        }
    }

    /* ---------- BATTLE：6 轮对局 ---------- */

    /**
     * 本轮出战小队成员各提交一份对敌方出战 5 人的猜测，密封；双方都齐 5 份时提前揭晓。
     */
    public void submitRoundGuess(ObjectNode root, UserAccount user, List<String> values) {
        if (!"BATTLE".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在对局阶段");
        ObjectNode match = activeMatchFor(root, user.getTeamId());
        if (!"BATTLE".equals(match.path("phase").asText())
                || !"GUESS".equals(match.path("roundPhase").asText()))
            throw new IllegalStateException("当前不接受猜阵");
        if (match.path("guessDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("本轮猜阵已截止");
        String side = user.getTeamId().equals(match.path("a").asText()) ? "A" : "B";
        ObjectNode team = findTeam(root, user.getTeamId());
        String playerId = "u" + user.getId();
        if (!contains(team.path("squads").path(match.path("round").asInt(1) - 1), playerId))
            throw new IllegalStateException("只有本轮出战小队成员可以提交猜阵");
        if (values.size() != SQUAD_SIZE || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("猜阵必须选择 5 名敌方队员");
        ObjectNode enemy = findTeam(root, teamForSide(match, "A".equals(side) ? "B" : "A"));
        Set<String> enemyRoster = new HashSet<>();
        enemy.path("players").forEach(player -> enemyRoster.add(player.path("id").asText()));
        if (!enemyRoster.containsAll(values)) throw new IllegalArgumentException("猜阵目标必须是敌方队员");
        ObjectNode sideGuesses = match.withObject("/guesses").withObject("/" + side);
        if (sideGuesses.has(playerId)) throw new IllegalStateException("你已经提交过本轮猜阵");
        ArrayNode guess = mapper.createArrayNode();
        values.forEach(guess::add);
        sideGuesses.set(playerId, guess);
        // 双方交齐也要满每局最短时长才揭晓
        if (sideGuesses.size() >= SQUAD_SIZE
                && match.at("/guesses/" + ("A".equals(side) ? "B" : "A")).size() >= SQUAD_SIZE
                && System.currentTimeMillis() >= match.path("guessOpenedAt").asLong(0L) + GUESS_MIN_DURATION_MS)
            revealRound(root, match);
    }

    /**
     * 提前猜阵：本人小队出战的轮次在当前轮之后时，可预交对敌方出战 5 人的猜测，开窗时自动生效；
     * 重复提交覆盖旧值（改投），当前轮请走普通猜阵通道。
     */
    public void submitPreGuess(ObjectNode root, UserAccount user, List<String> values) {
        if (!"BATTLE".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在对局阶段");
        ObjectNode match = activeMatchFor(root, user.getTeamId());
        if (!"BATTLE".equals(match.path("phase").asText()))
            throw new IllegalStateException("当前不接受猜阵");
        String side = user.getTeamId().equals(match.path("a").asText()) ? "A" : "B";
        ObjectNode team = findTeam(root, user.getTeamId());
        String playerId = "u" + user.getId();
        int round = squadIndexOf(team, playerId) + 1;
        int currentRound = match.path("round").asInt(1);
        if (round == currentRound) throw new IllegalStateException("当前轮次请直接提交猜阵");
        if (round < currentRound) throw new IllegalStateException("你们小队的轮次已经结束");
        if (values.size() != SQUAD_SIZE || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("猜阵必须选择 5 名敌方队员");
        ObjectNode enemy = findTeam(root, teamForSide(match, "A".equals(side) ? "B" : "A"));
        Set<String> enemyRoster = new HashSet<>();
        enemy.path("players").forEach(player -> enemyRoster.add(player.path("id").asText()));
        if (!enemyRoster.containsAll(values)) throw new IllegalArgumentException("猜阵目标必须是敌方队员");
        ArrayNode guess = mapper.createArrayNode();
        values.forEach(guess::add);
        match.withObject("/preGuesses").withObject("/" + round).withObject("/" + side).set(playerId, guess);
    }

    /**
     * 撤回猜阵：先撤当前轮未揭晓的 live 猜阵（撤回后可重投），再撤本人后续轮次的提前猜阵。
     */
    public void retractGuess(ObjectNode root, UserAccount user) {
        if (!"BATTLE".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在对局阶段");
        ObjectNode match = activeMatchFor(root, user.getTeamId());
        if (!"BATTLE".equals(match.path("phase").asText()))
            throw new IllegalStateException("当前不接受猜阵");
        String side = user.getTeamId().equals(match.path("a").asText()) ? "A" : "B";
        String playerId = "u" + user.getId();
        boolean removed = false;
        if ("GUESS".equals(match.path("roundPhase").asText())) {
            ObjectNode sideGuesses = (ObjectNode) match.path("guesses").path(side);
            if (sideGuesses.has(playerId)) {
                sideGuesses.remove(playerId);
                removed = true;
            }
        }
        for (JsonNode roundNode : match.path("preGuesses")) {
            ObjectNode sideNode = (ObjectNode) roundNode.path(side);
            if (sideNode.has(playerId)) {
                sideNode.remove(playerId);
                removed = true;
            }
        }
        if (!removed) throw new IllegalStateException("你没有可撤回的猜阵");
    }

    boolean expireGuesses(ObjectNode root, long now) {
        if (!"BATTLE".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText())
                    || !"BATTLE".equals(match.path("phase").asText())
                    || !"GUESS".equals(match.path("roundPhase").asText())) continue;
            // 双方交齐（含整轮靠提前猜阵交齐）且满最短时长即揭晓
            boolean bothComplete = match.at("/guesses/A").size() >= SQUAD_SIZE
                    && match.at("/guesses/B").size() >= SQUAD_SIZE;
            boolean minElapsed = now >= match.path("guessOpenedAt").asLong(0L) + GUESS_MIN_DURATION_MS;
            if (bothComplete && minElapsed) {
                revealRound(root, match);
                changed = true;
                continue;
            }
            if (match.path("guessDeadlineAt").asLong(Long.MAX_VALUE) > now) continue;
            revealRound(root, match);
            changed = true;
        }
        return changed;
    }

    /**
     * 结算一局：双方出战小队战力（基础 × 同步暴击 + 猜阵加成），高者胜，相等记平局。
     */
    void revealRound(ObjectNode root, ObjectNode match) {
        int round = match.path("round").asInt(1);
        ObjectNode entry = match.withArray("rounds").addObject();
        entry.put("round", round);
        double powerA = settleSide(root, match, entry, round, "A");
        double powerB = settleSide(root, match, entry, round, "B");
        String winner = powerA > powerB ? "A" : powerB > powerA ? "B" : null;
        if (winner == null) entry.putNull("winner");
        else {
            entry.put("winner", winner);
            match.put("wins" + winner, match.path("wins" + winner).asInt() + 1);
        }
        match.put("roundPhase", "REVEAL");
        match.put("revealUntil", System.currentTimeMillis() + REVEAL_DURATION_MS);
        match.remove("guessDeadlineAt");
        match.set("guesses", mapper.createObjectNode());
    }

    private double settleSide(ObjectNode root, ObjectNode match, ObjectNode entry, int round, String side) {
        ObjectNode team = findTeam(root, teamForSide(match, side));
        JsonNode squad = team.path("squads").path(round - 1);
        int base = 0;
        List<Long> rollTimestamps = new ArrayList<>();
        boolean anyAuto = false;
        for (JsonNode idNode : squad) {
            ObjectNode player = findPlayer(team, idNode.asText());
            if (player == null) continue;
            base += personalPoints(player);
            if (player.has("rollTs")) rollTimestamps.add(player.path("rollTs").asLong());
            if (player.path("autoRolled").asBoolean()) anyAuto = true;
        }
        boolean crit = syncCrit(rollTimestamps, anyAuto);
        int hits = guessHits(root, match, side, round);
        double bonus = guessBonus(hits);
        double power = squadPower(base, crit, hits);
        entry.put("base" + side, base);
        entry.put("crit" + side, crit);
        entry.put("guessHits" + side, hits);
        entry.put("guessBonus" + side, bonus);
        entry.put("power" + side, power);
        return power;
    }

    static int personalPoints(JsonNode player) {
        return player.path("diceFinal").asInt() + player.path("blindBox").asInt();
    }

    /**
     * 同步暴击：小队 5 人掷骰时刻首尾差 ≤500ms；含系统代掷队员的小队不能暴击。
     */
    static boolean syncCrit(List<Long> rollTimestamps, boolean anyAutoRolled) {
        if (anyAutoRolled || rollTimestamps.size() < SQUAD_SIZE) return false;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long ts : rollTimestamps) {
            min = Math.min(min, ts);
            max = Math.max(max, ts);
        }
        return max - min <= SYNC_CRIT_WINDOW_MS;
    }

    static double guessBonus(int hits) {
        return round2(Math.min(hits * GUESS_BONUS_PER_HIT, GUESS_BONUS_CAP));
    }

    static double squadPower(double base, boolean syncCrit, int hits) {
        return round2(base * (syncCrit ? SYNC_CRIT_MULTIPLIER : 1d)) + guessBonus(hits);
    }

    /**
     * 命中人次：本方出战 5 人提交的猜测中，猜中敌方本轮出战队员的总次数。
     */
    private int guessHits(ObjectNode root, ObjectNode match, String side, int round) {
        ObjectNode enemy = findTeam(root, teamForSide(match, "A".equals(side) ? "B" : "A"));
        Set<String> enemySquad = new HashSet<>();
        enemy.path("squads").path(round - 1).forEach(id -> enemySquad.add(id.asText()));
        int hits = 0;
        var guesses = match.at("/guesses/" + side).fields();
        while (guesses.hasNext()) {
            for (JsonNode guessed : guesses.next().getValue())
                if (enemySquad.contains(guessed.asText())) hits++;
        }
        return hits;
    }

    boolean completeRoundReveals(ObjectNode root, long now) {
        if (!"BATTLE".equals(root.path("stage").asText())) return false;
        boolean changed = false;
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText())
                    || !"BATTLE".equals(match.path("phase").asText())
                    || !"REVEAL".equals(match.path("roundPhase").asText())) continue;
            if (match.path("revealUntil").asLong(Long.MAX_VALUE) > now) continue;
            advanceMatchRound(root, match);
            changed = true;
        }
        return changed;
    }

    private void advanceMatchRound(ObjectNode root, ObjectNode match) {
        int round = match.path("round").asInt(1);
        match.remove("revealUntil");
        if (round >= SQUAD_COUNT) {
            enterResult(root, match);
            return;
        }
        int newRound = round + 1;
        match.put("round", newRound);
        match.put("roundPhase", "GUESS");
        long now = System.currentTimeMillis();
        match.put("guessDeadlineAt", now + GUESS_DURATION_MS);
        match.put("guessOpenedAt", now);
        mergePreGuesses(root, match, newRound);
    }

    /**
     * 开窗合并：把该轮的提前猜阵并入正式猜阵，按当前敌方花名册过滤失效目标，合并后删除该轮条目。
     */
    private void mergePreGuesses(ObjectNode root, ObjectNode match, int round) {
        JsonNode roundNode = match.path("preGuesses").path(String.valueOf(round));
        if (!roundNode.isObject()) return;
        ObjectNode guesses = match.withObject("/guesses");
        for (String side : List.of("A", "B")) {
            JsonNode sideNode = roundNode.path(side);
            if (!sideNode.isObject()) continue;
            ObjectNode enemy = findTeam(root, teamForSide(match, "A".equals(side) ? "B" : "A"));
            Set<String> enemyRoster = new HashSet<>();
            enemy.path("players").forEach(player -> enemyRoster.add(player.path("id").asText()));
            ObjectNode target = guesses.withObject("/" + side);
            sideNode.fields().forEachRemaining(entry -> {
                ArrayNode filtered = mapper.createArrayNode();
                for (JsonNode id : entry.getValue())
                    if (enemyRoster.contains(id.asText())) filtered.add(id.asText());
                target.set(entry.getKey(), filtered);
            });
        }
        ((ObjectNode) match.path("preGuesses")).remove(String.valueOf(round));
    }

    /* ---------- 比赛结算与平局链 ---------- */

    private void enterResult(ObjectNode root, ObjectNode match) {
        String winnerSide = decideMatchWinner(root, match);
        match.remove("roundPhase");
        if (winnerSide == null) {
            // 胜场/总点数/GMV 全平：不出胜者，停在待加赛状态，由管理员触发两队重赛
            match.put("phase", "OVERTIME_PENDING");
        } else {
            match.put("phase", "RESULT");
            match.put("resultReadyAt", System.currentTimeMillis() + resultDisplayMs);
            match.put("winner", teamForSide(match, winnerSide));
        }
        recordMatchReport(root, match, winnerSide);
        // 逐局战力明细归档到 match_report，GameState 行内只留 {round, winner} 摘要，控制行体积
        archiveMatchDetail(root, match);
        summarizeRounds(match);
    }

    private void archiveMatchDetail(ObjectNode root, ObjectNode match) {
        matchReports.save(new MatchReport(root.path("day").asInt(1), match.path("id").asText(), match.toString()));
    }

    /**
     * 比赛胜负链：6 局胜场多者胜 → 30 人最终个人点数总和 → 队伍 GMV → 全平则待加赛（返回 null）。
     */
    String decideMatchWinner(ObjectNode root, ObjectNode match) {
        int winsA = match.path("winsA").asInt(), winsB = match.path("winsB").asInt();
        String a = match.path("a").asText(), b = match.path("b").asText();
        if (winsA != winsB) {
            match.put("tieBreak", "胜场");
            return winsA > winsB ? "A" : "B";
        }
        double pointsA = totalPersonalPoints(root, a), pointsB = totalPersonalPoints(root, b);
        match.put("totalPointsA", pointsA);
        match.put("totalPointsB", pointsB);
        BigDecimal gmvA = findTeam(root, a).path("gmv").decimalValue();
        BigDecimal gmvB = findTeam(root, b).path("gmv").decimalValue();
        match.put("gmvA", gmvA);
        match.put("gmvB", gmvB);
        int comparison = compareMatchTieBreak(pointsA, pointsB, gmvA, gmvB);
        if (comparison == 0) {
            match.put("tieBreak", "加赛");
            return null;
        }
        match.put("tieBreak", pointsA != pointsB ? "总点数" : "GMV");
        return comparison > 0 ? "A" : "B";
    }

    /**
     * 平局链比较：返回正数表示 A 方胜，0 表示全平待加赛。总点数多者胜 → GMV 高者胜。
     */
    static int compareMatchTieBreak(double pointsA, double pointsB, BigDecimal gmvA, BigDecimal gmvB) {
        int comparison = Double.compare(pointsA, pointsB);
        if (comparison == 0) comparison = gmvA.compareTo(gmvB);
        return comparison;
    }

    private double totalPersonalPoints(ObjectNode root, String teamId) {
        int total = 0;
        for (JsonNode player : findTeam(root, teamId).path("players")) total += personalPoints(player);
        return total;
    }

    /**
     * 每场一条战报：每局明细 + 重掷记录 + 平局链 + 胜者。
     */
    private void recordMatchReport(ObjectNode root, ObjectNode match, String winnerSide) {
        StringBuilder text = new StringBuilder()
                .append(roundLabel(match.path("id").asText()))
                .append(teamName(root, match.path("a").asText()))
                .append(" vs ").append(teamName(root, match.path("b").asText()))
                .append("（6 局胜场 ").append(match.path("winsA").asInt())
                .append(":").append(match.path("winsB").asInt()).append("）");
        for (JsonNode roundNode : match.path("rounds")) {
            text.append("\n第").append(roundNode.path("round").asInt()).append("局 ")
                    .append(sideReport(root, match, roundNode, "A"))
                    .append(" ｜ ").append(sideReport(root, match, roundNode, "B"))
                    .append(" ｜ ");
            JsonNode winner = roundNode.path("winner");
            if (winner.isTextual())
                text.append("胜者 ").append(teamName(root, teamForSide(match, winner.asText())));
            else text.append("平局");
        }
        String rerolls = rerollSummary(root, match);
        text.append("\n重掷：").append(rerolls.isEmpty() ? "无" : rerolls);
        String tieBreak = match.path("tieBreak").asText("胜场");
        if (!"胜场".equals(tieBreak)) {
            text.append("\n平局链：胜场 ").append(match.path("winsA").asInt())
                    .append(":").append(match.path("winsB").asInt())
                    .append(" → 30 人总点数 ").append(match.path("totalPointsA").asDouble())
                    .append(":").append(match.path("totalPointsB").asDouble());
            if ("总点数".equals(tieBreak)) {
                text.append(" → 按总点数判定");
            } else {
                text.append(" → GMV ").append(match.path("gmvA").decimalValue())
                        .append(":").append(match.path("gmvB").decimalValue())
                        .append("加赛".equals(tieBreak) ? " → 全平，待管理员加赛" : " → 按 GMV 判定");
            }
        }
        if (winnerSide != null)
            text.append("\n胜者 ").append(teamName(root, teamForSide(match, winnerSide)));
        reports.save(new BattleReport(text.toString(), "system"));
    }

    private String sideReport(ObjectNode root, ObjectNode match, JsonNode roundNode, String side) {
        StringBuilder text = new StringBuilder(teamName(root, teamForSide(match, side)))
                .append(" ").append(roundNode.path("round").asInt()).append("号小队 战力 ")
                .append(roundNode.path("power" + side).asDouble())
                .append("（基础 ").append(roundNode.path("base" + side).asInt());
        if (roundNode.path("crit" + side).asBoolean()) text.append("，同步暴击×1.5");
        text.append("，猜阵命中 ").append(roundNode.path("guessHits" + side).asInt())
                .append(" +").append(roundNode.path("guessBonus" + side).asDouble()).append("）");
        return text.toString();
    }

    private String rerollSummary(ObjectNode root, ObjectNode match) {
        List<String> entries = new ArrayList<>();
        for (String side : List.of("A", "B")) {
            ObjectNode team = findTeam(root, teamForSide(match, side));
            for (JsonNode log : team.path("rerollLog")) {
                entries.add(teamName(root, team.path("id").asText()) + "·" + log.path("playerName").asText()
                        + " " + log.path("from").asInt() + "→" + log.path("to").asInt());
            }
        }
        return String.join("，", entries);
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

    static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private void completeResult(ObjectNode match) {
        match.remove("resultReadyAt");
        match.put("status", "done");
        match.put("phase", "FINISHED");
    }

    boolean completeDueResults(ObjectNode root, long now) {
        boolean changed = false;
        for (JsonNode matchNode : root.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if ("active".equals(match.path("status").asText())
                    && "RESULT".equals(match.path("phase").asText())
                    && match.path("resultReadyAt").asLong(Long.MAX_VALUE) <= now) {
                completeResult(match);
                changed = true;
            }
        }
        return changed;
    }

    /* ---------- 定时扫描：各阶段超时兜底 ---------- */

    private static boolean isTournamentMode(ObjectNode root) {
        String mode = root.path("mode").asText();
        return "parallel".equals(mode) || "overtime".equals(mode);
    }

    @Scheduled(fixedDelayString = "${app.game.result-scan-ms:500}")
    @Transactional
    public void advanceDueResults() {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        if (record == null) return;
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!isTournamentMode(root)) return;
            long now = System.currentTimeMillis();
            boolean changed = switch (root.path("stage").asText()) {
                case "CAPTAIN_VOTE" -> expireVoting(root, now);
                case "SQUAD_FORM" -> expireSquadForm(root, now);
                case "ROLL" -> expireRoll(root, now);
                case "BLIND_BOX" -> expireBlindBox(root, now);
                case "TACTICS" -> expireTactics(root, now);
                case "BATTLE" -> {
                    boolean battleChanged = expireGuesses(root, now);
                    battleChanged |= completeRoundReveals(root, now);
                    battleChanged |= completeDueResults(root, now);
                    yield battleChanged;
                }
                default -> false;
            };
            if (!changed) return;
            advanceBracketRound(root);
            record.update(root.toString(), "system");
            states.save(record);
            if (root.hasNonNull("champion")) events.stateChanged();
            else events.gameChangedNow();
        } catch (Exception e) {
            log.warn("定时推进比赛阶段失败", e);
        }
    }

    /**
     * bracket 晋级：一轮场次全部完结后创建下一轮对阵，并让在赛队伍从 ROLL 重新开始。
     */
    private void advanceBracketRound(ObjectNode root) {
        advance(root);
        if (root.hasNonNull("champion")) return;
        if (!"BATTLE".equals(root.path("stage").asText())) return;
        boolean anyLive = false, anyPending = false;
        for (JsonNode match : root.path("matches")) {
            if (!"active".equals(match.path("status").asText())) continue;
            if ("PENDING".equals(match.path("phase").asText())) anyPending = true;
            else anyLive = true;
        }
        if (anyPending && !anyLive) startRoundFlow(root);
    }

    private void advance(ObjectNode root) {
        ObjectNode matches = (ObjectNode) root.path("matches");
        if ("overtime".equals(root.path("mode").asText())) {
            // 加赛只有一场总决赛：完结后直接就地敲定总冠军，不回写每日结果。
            if (done(matches, "f1") && !root.hasNonNull("champion")) {
                String winner = winner(matches, "f1");
                root.put("champion", winner);
                reports.save(new BattleReport("【总冠军加赛】总冠军产生：" + teamName(root, winner), "system"));
                ObjectNode overall = root.withObject("/overallResult");
                overall.put("champion", winner);
                overall.put("status", "DECIDED");
                overall.put("decidedBy", "OVERTIME");
                overall.put("decidedAt", System.currentTimeMillis());
                root.put("overallChampion", winner);
                controls.findById(1L).ifPresent(control -> control.changePhase("FINISHED"));
            }
            return;
        }
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
        result.put("day", day);
        result.put("champion", root.path("champion").asText());
        result.put("finishedAt", System.currentTimeMillis());
        ObjectNode stats = mapper.createObjectNode();
        ArrayNode teams = result.putArray("teams");
        for (JsonNode source : root.path("teams")) {
            ObjectNode team = teams.addObject();
            String id = source.path("id").asText();
            team.put("id", id);
            team.put("name", source.path("name").asText());
            team.put("gmv", source.path("gmv").decimalValue());
            team.put("growthCoefficient", source.path("growthCoefficient").asDouble(1d));
            team.put("growthRate", source.path("growthRate").asDouble(
                    (source.path("growthCoefficient").asDouble(1d) - 1d) * 100d));
            team.put("matchWins", 0);
            team.put("matchLosses", 0);
            team.put("roundWins", 0);
            team.put("roundLosses", 0);
            ArrayNode players = team.putArray("players");
            source.path("players").forEach(player -> {
                ObjectNode member = players.addObject();
                member.put("id", player.path("id").asText());
                member.put("name", player.path("name").asText());
                member.put("department", player.path("department").asText());
                member.put("standIn", player.path("standIn").asBoolean(false));
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
            match.put("id", matchNode.path("id").asText());
            match.put("a", a);
            match.put("b", b);
            match.put("winsA", winsA);
            match.put("winsB", winsB);
            match.put("winner", winner);
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
        ObjectNode overall = decideOverall(dayResults);
        root.set("overallResult", overall);
        if (overall.hasNonNull("champion")) root.put("overallChampion", overall.path("champion").asText());
    }

    /**
     * 总冠军判定：候选只取两天的单日冠军。同一支队双冠直接夺冠；两支候选先比两天累计胜场，
     * 再比两天 GMV 合计；全部持平则不产生冠军，置为待加赛。
     */
    static ObjectNode decideOverall(ObjectNode dayResults) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        Map<String, ObjectNode> totals = new LinkedHashMap<>();
        for (String dayKey : List.of("day1", "day2")) {
            for (JsonNode source : dayResults.path(dayKey).path("teams")) {
                String id = source.path("id").asText();
                ObjectNode total = totals.computeIfAbsent(id, ignored -> {
                    ObjectNode value = nodes.objectNode();
                    value.put("id", id);
                    value.put("name", source.path("name").asText(id));
                    value.put("totalMatchWins", 0);
                    value.put("totalGmv", BigDecimal.ZERO);
                    return value;
                });
                total.put("totalMatchWins", total.path("totalMatchWins").asInt()
                        + source.path("matchWins").asInt());
                total.put("totalGmv", total.path("totalGmv").decimalValue()
                        .add(source.path("gmv").decimalValue()));
            }
        }
        List<ObjectNode> ranking = new ArrayList<>(totals.values());
        ranking.sort(Comparator
                .comparingInt((ObjectNode team) -> team.path("totalMatchWins").asInt()).reversed()
                .thenComparing((a, b) -> b.path("totalGmv").decimalValue()
                        .compareTo(a.path("totalGmv").decimalValue()))
                .thenComparing(team -> team.path("id").asText()));
        List<String> candidateIds = new ArrayList<>();
        for (String dayKey : List.of("day1", "day2")) {
            String dayChampion = dayResults.path(dayKey).path("champion").asText(null);
            if (dayChampion != null && !candidateIds.contains(dayChampion)) candidateIds.add(dayChampion);
        }
        String champion = null, decidedBy = null;
        if (candidateIds.size() == 1) {
            champion = candidateIds.get(0);
            decidedBy = "BOTH_DAYS";
        } else if (candidateIds.size() == 2) {
            ObjectNode first = totals.get(candidateIds.get(0)), second = totals.get(candidateIds.get(1));
            int winsFirst = first != null ? first.path("totalMatchWins").asInt() : 0;
            int winsSecond = second != null ? second.path("totalMatchWins").asInt() : 0;
            if (winsFirst != winsSecond) {
                champion = winsFirst > winsSecond ? candidateIds.get(0) : candidateIds.get(1);
                decidedBy = "MATCH_WINS";
            } else {
                BigDecimal gmvFirst = first != null ? first.path("totalGmv").decimalValue() : BigDecimal.ZERO;
                BigDecimal gmvSecond = second != null ? second.path("totalGmv").decimalValue() : BigDecimal.ZERO;
                int gmvCompare = gmvFirst.compareTo(gmvSecond);
                if (gmvCompare != 0) {
                    champion = gmvCompare > 0 ? candidateIds.get(0) : candidateIds.get(1);
                    decidedBy = "GMV";
                }
            }
        }
        ObjectNode overall = nodes.objectNode();
        if (champion != null) overall.put("champion", champion);
        else overall.putNull("champion");
        overall.put("status", champion != null ? "DECIDED" : "OVERTIME_PENDING");
        if (decidedBy != null) overall.put("decidedBy", decidedBy);
        else overall.putNull("decidedBy");
        ArrayNode candidates = overall.putArray("candidates");
        for (String candidateId : candidateIds) {
            ObjectNode total = totals.get(candidateId);
            if (total != null) candidates.add(total.deepCopy());
        }
        overall.put("decidedAt", System.currentTimeMillis());
        ArrayNode standings = overall.putArray("standings");
        ranking.forEach(team -> standings.add(team.deepCopy()));
        return overall;
    }

    /* ---------- 测试推进 ---------- */

    @Transactional
    public TestStep simulateStep(String username) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("测试比赛尚未建立"));
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
            if (!isTournamentMode(root)) throw new IllegalStateException("当前不是并行比赛流程");
            int progressed = 0;
            switch (root.path("stage").asText()) {
                case "CAPTAIN_VOTE" -> {
                    for (JsonNode teamNode : root.path("teams")) {
                        ObjectNode team = (ObjectNode) teamNode;
                        if (!team.path("players").isEmpty() && !hasCaptain(team)) {
                            electCaptain(team);
                            progressed++;
                        }
                    }
                    startSquadsIfReady(root);
                }
                case "SQUAD_FORM" -> {
                    for (JsonNode teamNode : root.path("teams")) {
                        ObjectNode team = (ObjectNode) teamNode;
                        if (!team.has("squads")) {
                            randomSquads(team);
                            progressed++;
                        }
                    }
                    startRoundFlowIfReady(root);
                }
                case "ROLL" -> {
                    doRoll(root);
                    progressed = 1;
                }
                case "BLIND_BOX" -> {
                    applyBlindBoxRows(root);
                    startTactics(root);
                    progressed = 1;
                }
                case "TACTICS" -> {
                    startBattle(root);
                    progressed = 1;
                }
                case "BATTLE" -> {
                    for (JsonNode matchNode : root.path("matches")) {
                        ObjectNode match = (ObjectNode) matchNode;
                        if (!"active".equals(match.path("status").asText())) continue;
                        switch (match.path("phase").asText()) {
                            case "BATTLE" -> {
                                if ("GUESS".equals(match.path("roundPhase").asText())) revealRound(root, match);
                                else advanceMatchRound(root, match);
                                progressed++;
                            }
                            case "RESULT" -> {
                                completeResult(match);
                                progressed++;
                            }
                            case "OVERTIME_PENDING" -> {
                                if (canRematch(root, match)) {
                                    prepareRematch(root, match);
                                    progressed++;
                                }
                            }
                            default -> {
                            }
                        }
                    }
                    advanceBracketRound(root);
                }
                default -> {
                }
            }
            record.update(root.toString(), username);
            states.save(record);
            if (root.hasNonNull("champion")) events.stateChanged();
            else events.gameChanged();
            return new TestStep(progressed, root.path("champion").asText(null),
                    controls.findById(1L).map(control -> control.getPhase()).orElse("PREPARING"));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("推进测试流程失败", e);
        }
    }

    /* ---------- 管理员强制推进 ---------- */

    /**
     * 管理员强制推进：把当前等待中的截止时间提前到现在，
     * 由同一套超时逻辑在下一次扫描完成推进，不另开一条代码路径。
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
        String stage = root.path("stage").asText();
        String phase = match.path("phase").asText();
        switch (stage) {
            case "CAPTAIN_VOTE" -> {
                for (JsonNode teamNode : root.path("teams")) {
                    ObjectNode team = (ObjectNode) teamNode;
                    if (!hasCaptain(team)) team.put("roleVoteDeadlineAt", past);
                }
                forced.add("队长投票");
            }
            case "SQUAD_FORM" -> {
                root.put("stageDeadlineAt", past);
                forced.add("小队分队");
            }
            case "ROLL" -> {
                root.put("stageDeadlineAt", past);
                forced.add("全员掷骰");
            }
            case "BLIND_BOX" -> {
                root.put("stageDeadlineAt", past);
                forced.add("开盲盒");
            }
            case "TACTICS" -> {
                root.put("stageDeadlineAt", past);
                forced.add("战术窗口");
            }
            case "BATTLE" -> {
                if ("BATTLE".equals(phase) && "GUESS".equals(match.path("roundPhase").asText())) {
                    match.put("guessDeadlineAt", past);
                    forced.add("第 " + match.path("round").asInt() + " 局猜阵");
                } else if ("BATTLE".equals(phase) && "REVEAL".equals(match.path("roundPhase").asText())) {
                    match.put("revealUntil", past);
                    forced.add("第 " + match.path("round").asInt() + " 局揭晓");
                } else if ("RESULT".equals(phase)) {
                    match.put("resultReadyAt", past);
                    forced.add("比赛结果展示");
                }
            }
            default -> {
            }
        }
        if (forced.isEmpty()) throw new IllegalStateException("该场次当前没有可强制推进的环节");
        record.update(root.toString(), "admin");
        states.save(record);
        events.gameChanged();
        return new ForceResult(matchId, "BATTLE".equals(stage) ? phase : stage, forced);
    }

    /**
     * 单场加赛：胜场/总点数/GMV 三连环全平的场次由管理员触发两队重赛，
     * 重置该场后复用正常轮次流程重走 ROLL → … → BATTLE，直到分出胜负。
     */
    @Transactional
    public void rematch(String username, String matchId) {
        GameStateRecord record = states.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("比赛尚未开始"));
        ObjectNode root = readState(record);
        if (root == null || !root.path("matches").has(matchId)) throw new IllegalArgumentException("场次不存在");
        ObjectNode match = (ObjectNode) root.path("matches").path(matchId);
        if (!"OVERTIME_PENDING".equals(match.path("phase").asText()))
            throw new IllegalStateException("该场次不在待加赛状态");
        if (!canRematch(root, match)) throw new IllegalStateException("等其他场次结束后再安排加赛");
        prepareRematch(root, match);
        reports.save(new BattleReport("【加赛】" + roundLabel(matchId)
                + teamName(root, match.path("a").asText()) + " vs "
                + teamName(root, match.path("b").asText()) + " 三连环全平，两队重赛", "system"));
        record.update(root.toString(), username);
        states.save(record);
        events.gameChanged();
    }

    /** 同 bracket 轮次还有其他在赛场次时不能加赛，避免重置到别人的掷骰数据。 */
    private boolean canRematch(ObjectNode root, ObjectNode match) {
        for (JsonNode other : root.path("matches")) {
            if (other != match && "active".equals(other.path("status").asText())) return false;
        }
        return true;
    }

    private void prepareRematch(ObjectNode root, ObjectNode match) {
        match.remove(List.of("winner", "tieBreak", "totalPointsA", "totalPointsB", "gmvA", "gmvB",
                "resultReadyAt", "guesses", "preGuesses", "guessDeadlineAt", "guessOpenedAt", "revealUntil"));
        match.put("winsA", 0);
        match.put("winsB", 0);
        match.put("round", 1);
        match.set("rounds", mapper.createArrayNode());
        match.put("phase", "PENDING");
        startRoundFlow(root);
    }

    @Transactional
    public void resetTwoDayTournament() {
        GameStateRecord record = states.findLockedById(1L).orElse(null);
        blindBoxes.deleteAll();
        if (record != null) states.delete(record);
        users.deleteAll(users.findAll().stream().filter(LobbyService::isStandIn).toList());
        List<UserAccount> accounts = users.findAll().stream().filter(u -> "USER".equals(u.getRole())).toList();
        accounts.forEach(user -> {
            user.setReady(false);
            user.setAfk(false);
        });
        users.saveAll(accounts);
        controls.findById(1L).ifPresent(control -> control.changePhase("PREPARING"));
        events.stateChanged();
    }

    /* ---------- 基础工具 ---------- */

    private JsonNode sandboxPlayer(ObjectNode root, String username) {
        if (username == null) return null;
        for (JsonNode player : root.path("sandboxPlayers"))
            if (username.equals(player.path("username").asText())) return player;
        JsonNode legacy = root.path("sandboxSolo");
        return username.equals(legacy.path("username").asText(null)) ? legacy : null;
    }

    private String teamForSide(ObjectNode match, String side) {
        return match.path("A".equals(side) ? "a" : "b").asText();
    }

    private ObjectNode activeMatchFor(ObjectNode root, String teamId) {
        for (JsonNode node : root.path("matches")) {
            if ("active".equals(node.path("status").asText())
                    && (teamId.equals(node.path("a").asText()) || teamId.equals(node.path("b").asText())))
                return (ObjectNode) node;
        }
        throw new IllegalStateException("本队当前没有进行中的比赛");
    }

    private Set<String> activeTeamIds(ObjectNode root) {
        Set<String> ids = new HashSet<>();
        for (JsonNode match : root.path("matches")) {
            if (!"active".equals(match.path("status").asText())) continue;
            ids.add(match.path("a").asText());
            ids.add(match.path("b").asText());
        }
        return ids;
    }

    private ObjectNode findTeam(ObjectNode root, String teamId) {
        for (JsonNode candidate : root.path("teams"))
            if (teamId.equals(candidate.path("id").asText())) return (ObjectNode) candidate;
        throw new IllegalStateException("队伍资料不存在");
    }

    private ObjectNode findPlayer(ObjectNode team, String playerId) {
        for (JsonNode candidate : team.path("players"))
            if (playerId.equals(candidate.path("id").asText())) return (ObjectNode) candidate;
        return null;
    }

    private boolean contains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    private ObjectNode createMatch(ObjectNode matches, String id, String a, String b) {
        ObjectNode match = matches.putObject(id);
        match.put("id", id);
        match.put("a", a);
        match.put("b", b);
        match.put("winsA", 0);
        match.put("winsB", 0);
        match.put("round", 1);
        match.put("status", "active");
        match.put("phase", "PENDING");
        match.putArray("rounds");
        return match;
    }

    private boolean done(ObjectNode matches, String id) {
        return matches.has(id) && "done".equals(matches.path(id).path("status").asText());
    }

    private String winner(ObjectNode matches, String id) {
        return matches.path(id).path("winner").asText();
    }

    public record SandboxAssignment(UserAccount player, UserAccount replaced, String teamId, String identity) {
    }

    public record TestStep(int advancedMatches, String champion, String phase) {
    }

    public record AdminRoleAssignment(String teamId, String role, String playerId, String stage) {
    }

    public record ForceResult(String matchId, String phase, List<String> forced) {
    }

    public record LiveRoll(int die, long rollTs) {
    }

    public record RollAssignmentView(boolean eligible, String stage, Long rollGoAt, Long stageDeadlineAt,
                                     boolean alreadyRolled, String teamId,
                                     Integer squadIndex, Long rollOpenAt, Long rollDeadlineAt) {
    }
}
