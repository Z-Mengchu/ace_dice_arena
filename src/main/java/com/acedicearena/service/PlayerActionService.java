package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

@Service
public class PlayerActionService {
    private final GameStateRepository gameStates;
    private final UserAccountRepository users;
    private final ObjectMapper mapper;
    private final LobbyEventService events;
    private final ParallelTournamentService tournament;
    private final BlindBoxRoundService blindBoxRounds;
    private final TransactionTemplate transactions;

    public PlayerActionService(GameStateRepository gameStates, UserAccountRepository users,
                               ObjectMapper mapper, LobbyEventService events,
                               ParallelTournamentService tournament, BlindBoxRoundService blindBoxRounds,
                               PlatformTransactionManager transactionManager) {
        this.gameStates = gameStates;
        this.users = users;
        this.mapper = mapper;
        this.events = events;
        this.tournament = tournament;
        this.blindBoxRounds = blindBoxRounds;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 按动作分流事务入口：开盲盒走内存运行态模块（自身管理阶段锁与写穿事务），
     * 其余动作在单个事务内锁 game_state 并走原有 JSON 状态机。
     */
    public Map<String, Object> submit(String username, String type, List<String> selections) {
        if ("blind-box-open".equals(type)) return submitBlindBoxOpen(username, selections);
        Map<String, Object> body = transactions.execute(status -> submitStateAction(username, type, selections));
        return body == null ? Map.of("ok", true) : body;
    }

    /** 开盲盒：不锁 game_state、不解析整份 JSON，用户/角色/挂机校验在盲盒模块的事务内完成。 */
    private Map<String, Object> submitBlindBoxOpen(String username, List<String> selections) {
        var result = blindBoxRounds.open(username, parseBoxIndex(selections));
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("ok", true);
        body.put("blindBox", result.value());
        if (result.boxes() != null) {
            body.put("boxes", java.util.Arrays.stream(result.boxes()).boxed().toList());
            body.put("picked", result.picked());
        }
        return body;
    }

    private Map<String, Object> submitStateAction(String username, String type, List<String> selections) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        if (!"USER".equals(user.getRole()) || user.getTeamId() == null) {
            throw new IllegalStateException("只有本轮已分组玩家可以提交比赛操作");
        }
        if (user.isAfk()) throw new IllegalStateException("你当前处于挂机状态，请先取消挂机再操作");
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

    /** 解析可选的盒子序号（selections 首元素）；缺省为 null，由服务端随机选一个。 */
    private Integer parseBoxIndex(List<String> selections) {
        if (selections == null || selections.isEmpty()) return null;
        try {
            return Integer.parseInt(selections.getFirst());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("盲盒序号必须是数字");
        }
    }

    private void notifyPlayerAction(String type, String captainBefore, String gameStageBefore,
                                    ObjectNode root, String teamId) {
        boolean stageChanged = !java.util.Objects.equals(gameStageBefore, root.path("stage").asText());
        if (!"role-vote".equals(type)) {
            if (stageChanged) events.gameChangedNow();
            else events.gameChanged();
            return;
        }
        if (stageChanged) {
            events.gameChangedNow();
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
