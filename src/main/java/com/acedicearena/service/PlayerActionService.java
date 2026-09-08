package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class PlayerActionService {
    private final GameStateRepository gameStates;
    private final UserAccountRepository users;
    private final ObjectMapper mapper;
    private final LobbyEventService events;
    private final ParallelTournamentService tournament;

    public PlayerActionService(GameStateRepository gameStates, UserAccountRepository users,
                               ObjectMapper mapper, LobbyEventService events,
                               ParallelTournamentService tournament) {
        this.gameStates = gameStates;
        this.users = users;
        this.mapper = mapper;
        this.events = events;
        this.tournament = tournament;
    }

    @Transactional
    public Map<String, Object> submit(String username, String type, List<String> selections) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        if (!"USER".equals(user.getRole()) || user.getTeamId() == null) {
            throw new IllegalStateException("只有本轮已分组玩家可以提交比赛操作");
        }
        if (user.isAfk()) throw new IllegalStateException("你当前处于挂机状态，请先取消挂机再操作");
        // 开盲盒结果按玩家独立成行（player_blind_box 表），不取 game_state 全局行锁
        if ("blind-box-open".equals(type)) {
            int box = openBlindBoxIndependent(user);
            events.gameChanged();
            return Map.of("ok", true, "blindBox", box);
        }
        GameStateRecord record = gameStates.findLockedById(1L)
                .orElseThrow(() -> new IllegalStateException("主持人尚未创建比赛"));
        ObjectNode root = parse(record.getContent());
        if (!"parallel".equals(root.path("mode").asText())) {
            throw new IllegalStateException("当前比赛不是并行赛制");
        }
        String captainBefore = "role-vote".equals(type) ? captainOf(root, user.getTeamId()) : null;
        String gameStageBefore = root.path("stage").asText();
        List<String> values = selections == null ? List.of() : selections;
        if (tournament.isSandboxPlayer(root, username)) {
            tournament.submitSandboxAction(root, user, type, values);
        } else {
            submitParallel(root, user, type, values);
        }
        record.update(root.toString(), username);
        gameStates.save(record);
        notifyPlayerAction(type, captainBefore, gameStageBefore, root, user.getTeamId());
        return Map.of("ok", true);
    }

    /**
     * 独立开盒；唯一键冲突 = 同一玩家并发重复提交，读已有行返回同一结果（幂等）。
     */
    private int openBlindBoxIndependent(UserAccount user) {
        try {
            return tournament.openBlindBoxIndependent(user);
        } catch (DataIntegrityViolationException duplicate) {
            Integer box = tournament.openedBlindBoxValue(user);
            if (box != null) return box;
            throw duplicate;
        }
    }

    private void notifyPlayerAction(String type, String captainBefore, String gameStageBefore,
                                    ObjectNode root, String teamId) {
        if (!"role-vote".equals(type)) {
            events.gameChanged();
            return;
        }
        if (!java.util.Objects.equals(gameStageBefore, root.path("stage").asText())) {
            events.gameChanged();
        } else if (!java.util.Objects.equals(captainBefore, captainOf(root, teamId))) {
            events.teamGameChanged(teamId);
        } else {
            events.adminGameChanged();
        }
    }

    private String captainOf(ObjectNode root, String teamId) {
        for (JsonNode team : root.path("teams")) {
            if (teamId.equals(team.path("id").asText())) return team.at("/roles/captain").asText(null);
        }
        return null;
    }

    private void submitParallel(ObjectNode root, UserAccount user, String type, List<String> values) {
        tournament.dispatchPlayerAction(root, user, type, values);
    }

    private ObjectNode parse(String content) {
        try {
            return (ObjectNode) mapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("比赛状态无法读取");
        }
    }
}
