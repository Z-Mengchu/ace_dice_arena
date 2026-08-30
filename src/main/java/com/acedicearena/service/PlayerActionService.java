package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PlayerActionService {
    private final GameStateRepository gameStates;
    private final UserAccountRepository users;
    private final ObjectMapper mapper;
    private final LobbyEventService events;
    private final OnlineGameService online;
    private final ParallelTournamentService tournament;
    private final ConcurrentHashMap<String, ReentrantLock> accumulationLocks = new ConcurrentHashMap<>();

    public PlayerActionService(GameStateRepository gameStates, UserAccountRepository users,
                               ObjectMapper mapper, LobbyEventService events, OnlineGameService online,
                               ParallelTournamentService tournament) {
        this.gameStates = gameStates; this.users = users; this.mapper = mapper;
        this.events = events; this.online = online; this.tournament = tournament;
    }

    @Transactional
    public void submit(String username, String type, List<String> selections) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        if (!"USER".equals(user.getRole()) || user.getTeamId() == null) {
            throw new IllegalStateException("只有本轮已分组玩家可以提交比赛操作");
        }
        if (user.isAfk()) throw new IllegalStateException("你当前处于挂机状态，请先取消挂机再操作");
        ReentrantLock accumulationLock = lockAccumulationRoll(user, type);
        boolean unlockOnReturn = accumulationLock != null && !TransactionSynchronizationManager.isSynchronizationActive();
        try {
            if (accumulationLock != null && !unlockOnReturn) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) { accumulationLock.unlock(); }
                });
            }
            GameStateRecord record = gameStates.findLockedById(1L)
                    .orElseThrow(() -> new IllegalStateException("主持人尚未创建比赛"));
            ObjectNode root = parse(record.getContent());
            String roleVoteStageBefore = "role-vote".equals(type) ? roleVoteStage(root, user.getTeamId()) : null;
            String gameStageBefore = root.path("stage").asText();
            if ("parallel".equals(root.path("mode").asText())) {
                if (tournament.isSandboxPlayer(root, username)) {
                    tournament.submitSandboxAction(root, user, type, selections == null ? List.of() : selections);
                    record.update(root.toString(), username); gameStates.save(record);
                    notifyPlayerAction(type, roleVoteStageBefore, gameStageBefore, root, user.getTeamId());
                    return;
                }
                String onlineCommand = submitParallel(root, user, type, selections == null ? List.of() : selections);
                record.update(root.toString(), username); gameStates.save(record);
                notifyPlayerAction(type, roleVoteStageBefore, gameStageBefore, root, user.getTeamId());
                if (onlineCommand != null) dispatchOnlineCommand(onlineCommand, root);
                return;
            }
            ObjectNode live = object(root, "live", "当前没有进行中的比赛");
            ObjectNode match = object(root.path("matches"), live.path("matchId").asText(), "比赛记录不存在");
            String side = sideFor(match, user.getTeamId());
            ObjectNode actions = live.withObject("/playerActions");
            List<String> values = selections == null ? List.of() : selections;

            switch (type == null ? "" : type) {
                case "prophet" -> submitProphet(root, live, match, actions, side, values);
                case "lineup" -> submitLineup(root, live, match, actions, side, values);
                case "captain-ready" -> submitCaptainReady(live, match, side, user.getTeamId());
                case "pitcher-ready" -> submitPitcherReady(live, match, side, user.getTeamId());
                default -> throw new IllegalArgumentException("未知的玩家操作");
            }
            record.update(root.toString(), username); gameStates.save(record); events.gameChanged();
        } finally {
            if (unlockOnReturn) accumulationLock.unlock();
        }
    }

    private ReentrantLock lockAccumulationRoll(UserAccount user, String type) {
        if (!"accumulation-roll".equals(type)) return null;
        ReentrantLock lock = accumulationLocks.computeIfAbsent(user.getTeamId(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) throw new IllegalStateException("队友正在掷积累骰，请等待本次结果");
        return lock;
    }

    private void notifyPlayerAction(String type, String roleVoteStageBefore, String gameStageBefore,
                                    ObjectNode root, String teamId) {
        if (!"role-vote".equals(type)) { events.gameChanged(); return; }
        if (!java.util.Objects.equals(gameStageBefore, root.path("stage").asText())) {
            events.gameChanged();
        } else if (!java.util.Objects.equals(roleVoteStageBefore, roleVoteStage(root, teamId))) {
            events.teamGameChanged(teamId);
        } else {
            events.adminGameChanged();
        }
    }

    private String roleVoteStage(ObjectNode root, String teamId) {
        for (JsonNode team : root.path("teams")) {
            if (teamId.equals(team.path("id").asText())) return team.path("roleVoteStage").asText(null);
        }
        return null;
    }

    private void dispatchOnlineCommand(String command, ObjectNode root) {
        Runnable action;
        if (command.startsWith("PREPARE_MATCH:")) {
            String matchId = command.substring("PREPARE_MATCH:".length());
            JsonNode match = root.at("/matches/" + matchId);
            int round = match.path("round").asInt(1);
            action = () -> {
                online.prepare(match.path("a").asText(), matchId, round,
                        lineupFor(root, match.path("a").asText()));
                online.prepare(match.path("b").asText(), matchId, round,
                        lineupFor(root, match.path("b").asText()));
            };
        } else if (command.startsWith("START:")) {
            String teamId = command.substring("START:".length());
            action = () -> online.startCountdown(teamId);
        } else if (command.startsWith("ARM:")) {
            String teamId = command.substring(4);
            List<String> lineup = lineupFor(root, teamId);
            action = () -> online.armAndGo(teamId, lineup);
        } else {
            String teamId = command.substring("REVEAL:".length());
            action = () -> online.finalRoll(teamId);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private String submitParallel(ObjectNode root, UserAccount user, String type, List<String> values) {
        String teamId = user.getTeamId();
        if ("accumulation-roll".equals(type)) {
            tournament.beginAccumulation(root, user);
            return null;
        }
        if ("role-vote".equals(type)) {
            tournament.submitRoleVote(root, user, values);
            return null;
        }
        if (!"ATTACK".equals(root.path("stage").asText()))
            throw new IllegalStateException("必须先完成角色投票和八支队伍的积累期全部掷骰");
        ObjectNode match = null;
        var matches = root.path("matches").elements();
        while (matches.hasNext()) {
            ObjectNode candidate = (ObjectNode) matches.next();
            if ("active".equals(candidate.path("status").asText()) &&
                    (teamId.equals(candidate.path("a").asText()) || teamId.equals(candidate.path("b").asText()))) { match = candidate; break; }
        }
        if (match == null) throw new IllegalStateException("本队当前没有进行中的比赛");
        String side = teamId.equals(match.path("a").asText()) ? "A" : "B";
        ObjectNode submitted = match.withObject("/submitted");
        String phase = match.path("phase").asText();
        switch (type == null ? "" : type) {
            case "prophet" -> {
                tournament.requireRole(root, user, "strategist", "只有当选军师可以提交预言");
                if (!"PROPHET".equals(phase)) throw new IllegalStateException("当前不接受预言");
                tournament.requireProphetOpen(match);
                if (!values.isEmpty() && values.size() != 5) throw new IllegalArgumentException("预言必须选择 5 人或放弃");
                validatePlayers(root, "A".equals(side) ? match.path("b").asText() : match.path("a").asText(), values, false);
                setArrayOrNull(match.withObject("/prophet"), side, values); submitted.put("prophet" + side, true);
                if (submitted.path("prophetA").asBoolean() && submitted.path("prophetB").asBoolean()) tournament.startLineupVoting(match);
            }
            case "lineup" -> {
                if (!"LINEUP".equals(phase)) throw new IllegalStateException("当前不接受阵容");
                tournament.submitCaptainLineup(root, match, user, side, values);
                if (submitted.path("lineupA").asBoolean() && submitted.path("lineupB").asBoolean()) {
                    tournament.startParallelAttack(match);
                    return "PREPARE_MATCH:" + match.path("id").asText();
                }
            }
            case "captain-command" -> {
                tournament.requireRole(root, user, "captain", "只有当选队长可以发号施令");
                tournament.requireAttackSidePhase(match, side, "PREPARING");
                if (!online.isTeamReady(teamId)) throw new IllegalStateException("必须等待五名出战队员全部准备");
                tournament.setAttackSidePhase(match, side, "COUNTDOWN");
                match.withObject("/sync").put("commanded" + side, true);
                match.withObject("/countdownUntil").put(side, System.currentTimeMillis() + 3_000L);
                return "START:" + teamId;
            }
            case "attack-boost" -> tournament.claimAttackBoost(root, match, user, side);
            case "pitcher-roll" -> {
                tournament.requireRole(root, user, "pitcher", "只有当选王牌投手可以完成最终投骰");
                tournament.requireAttackSidePhase(match, side, "PITCHER_ROLL");
                if (!online.isTimingReady(teamId)) throw new IllegalStateException("请等待五名出战队员全部完成同步点击");
                ObjectNode sync = match.withObject("/sync");
                String pendingKey = "revealPending" + side;
                if (sync.path(pendingKey).asBoolean()) throw new IllegalStateException("最终投骰正在处理，请勿重复点击");
                sync.put(pendingKey, true);
                return "REVEAL:" + teamId;
            }
            default -> throw new IllegalArgumentException("未知的玩家操作");
        }
        return null;
    }

    private List<String> lineupFor(ObjectNode root, String teamId) {
        for (JsonNode match : root.path("matches")) {
            if (!"active".equals(match.path("status").asText())) continue;
            String side = teamId.equals(match.path("a").asText()) ? "A" : teamId.equals(match.path("b").asText()) ? "B" : null;
            if (side == null) continue;
            List<String> lineup = new java.util.ArrayList<>();
            match.at("/lineups/" + side).forEach(id -> lineup.add(id.asText()));
            return lineup;
        }
        throw new IllegalStateException("本队当前没有出战阵容");
    }

    private void submitProphet(ObjectNode root, ObjectNode live, ObjectNode match, ObjectNode actions,
                               String side, List<String> values) {
        requireStep(live, "prophet");
        if (!values.isEmpty() && values.size() != 5) throw new IllegalArgumentException("预言必须选择 5 人或放弃");
        String opponentId = "A".equals(side) ? match.path("b").asText() : match.path("a").asText();
        validatePlayers(root, opponentId, values, false);
        setArrayOrNull(live.withObject("/prophet"), side, values);
        actions.put("prophet" + side, true);
        if (actions.path("prophetA").asBoolean() && actions.path("prophetB").asBoolean()) live.put("step", "lineupA");
    }

    private void submitLineup(ObjectNode root, ObjectNode live, ObjectNode match, ObjectNode actions,
                              String side, List<String> values) {
        requireStep(live, "A".equals(side) ? "lineupA" : "lineupB");
        if (values.size() != 5) throw new IllegalArgumentException("出战阵容必须选择 5 人");
        String teamId = "A".equals(side) ? match.path("a").asText() : match.path("b").asText();
        validatePlayers(root, teamId, values, true);
        setArray(live.withObject("/lineups"), side, values);
        setArray(live.withObject("/lastLineups"), side, values);
        actions.put("lineup" + side, true);
        if ("A".equals(side)) live.put("step", "lineupB");
        else {
            live.put("attacker", "A");
            ObjectNode sync = mapper.createObjectNode();
            sync.put("captainReady", false); sync.put("pitcherReady", false); sync.put("skipped", false);
            live.set("sync", sync); live.put("step", "sync");
        }
    }

    private void submitCaptainReady(ObjectNode live, ObjectNode match, String side, String teamId) {
        requireAttackingTeam(live, match, side, teamId);
        live.withObject("/sync").put("captainReady", true);
    }

    private void submitPitcherReady(ObjectNode live, ObjectNode match, String side, String teamId) {
        requireAttackingTeam(live, match, side, teamId);
        ObjectNode sync = live.withObject("/sync");
        if (!sync.path("captainReady").asBoolean()) throw new IllegalStateException("请等待队长先确认准备进攻");
        sync.put("pitcherReady", true); live.put("step", "roll");
    }

    private void requireAttackingTeam(ObjectNode live, ObjectNode match, String side, String teamId) {
        requireStep(live, "sync");
        if (!side.equals(live.path("attacker").asText())) throw new IllegalStateException("当前不是本队进攻回合");
        String attacking = "A".equals(side) ? match.path("a").asText() : match.path("b").asText();
        if (!teamId.equals(attacking)) throw new IllegalStateException("当前不是本队进攻回合");
    }

    private void validatePlayers(ObjectNode root, String teamId, List<String> selected, boolean needBack) {
        JsonNode team = null;
        for (JsonNode candidate : root.path("teams")) if (teamId.equals(candidate.path("id").asText())) team = candidate;
        if (team == null) throw new IllegalStateException("队伍资料不存在");
        Set<String> valid = new HashSet<>(); Set<String> back = new HashSet<>();
        for (JsonNode player : team.path("players")) {
            valid.add(player.path("id").asText());
            if ("back".equals(player.path("role").asText())) back.add(player.path("id").asText());
        }
        if (new HashSet<>(selected).size() != selected.size() || !valid.containsAll(selected)) {
            throw new IllegalArgumentException("选择的队员无效");
        }
        if (needBack && selected.stream().noneMatch(back::contains)) throw new IllegalArgumentException("阵容至少需要 1 名后端队员");
    }

    private String sideFor(ObjectNode match, String teamId) {
        if (teamId.equals(match.path("a").asText())) return "A";
        if (teamId.equals(match.path("b").asText())) return "B";
        throw new IllegalStateException("本队不在当前比赛中");
    }

    private void requireStep(ObjectNode live, String expected) {
        if (!expected.equals(live.path("step").asText())) throw new IllegalStateException("当前不接受这项操作");
    }

    private ObjectNode parse(String content) {
        try { return (ObjectNode) mapper.readTree(content); }
        catch (Exception e) { throw new IllegalStateException("比赛状态无法读取"); }
    }
    private ObjectNode object(JsonNode parent, String field, String message) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new IllegalStateException(message);
        return (ObjectNode) value;
    }
    private void setArray(ObjectNode target, String field, List<String> values) {
        ArrayNode array = mapper.createArrayNode(); values.forEach(array::add); target.set(field, array);
    }
    private void setArrayOrNull(ObjectNode target, String field, List<String> values) {
        if (values.isEmpty()) target.putNull(field); else setArray(target, field, values);
    }
}
