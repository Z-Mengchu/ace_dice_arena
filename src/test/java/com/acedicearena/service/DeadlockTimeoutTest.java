package com.acedicearena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 任何一个环节都不能因为某个玩家不操作而永久卡住整届赛事。 */
class DeadlockTimeoutTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void captainWhoNeverSubmitsALineupDoesNotStallTheMatch() {
        ObjectNode root = state();
        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        match.put("phase", "LINEUP");
        match.put("lineupDeadlineAt", System.currentTimeMillis() - 1);
        List<Runnable> actions = new ArrayList<>();

        assertThat(service().expireLineups(root, System.currentTimeMillis(), actions)).isTrue();

        assertThat(match.at("/lineups/A").size()).isEqualTo(5);
        assertThat(match.at("/lineups/B").size()).isEqualTo(5);
        assertThat(match.at("/lineupTimedOut/A").asBoolean()).isTrue();
        assertThat(match.path("phase").asText()).isEqualTo("ATTACKING");
        assertThat(actions).hasSize(1);   // 需要把补齐后的阵容同步给联机端
    }

    @Test
    void autoFilledLineupAlwaysContainsABackEndPlayer() {
        ObjectNode root = state();
        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        match.put("phase", "LINEUP");
        match.put("lineupDeadlineAt", System.currentTimeMillis() - 1);
        service().expireLineups(root, System.currentTimeMillis(), new ArrayList<>());

        List<String> lineup = new ArrayList<>();
        match.at("/lineups/A").forEach(id -> lineup.add(id.asText()));
        assertThat(lineup).contains("t1-back");
    }

    @Test
    void aPlayerWhoNeverClicksReadyDoesNotBlockTheAttack() {
        ObjectNode match = attacking("PREPARING");
        List<Runnable> actions = new ArrayList<>();

        assertThat(service().expireAttackSides(rootOf(match), System.currentTimeMillis(), actions)).isTrue();

        assertThat(match.at("/sidePhases/A").asText()).isEqualTo("ROLL");
        assertThat(match.at("/sideTimedOut/A").asText()).isEqualTo("prepare");
        assertThat(actions).hasSize(1);
    }

    @Test
    void aPlayerWhoNeverClicksRollDoesNotBlockTheReveal() {
        ObjectNode match = attacking("ROLL");
        long now = System.currentTimeMillis();
        List<Runnable> actions = new ArrayList<>();

        assertThat(service().expireAttackSides(rootOf(match), now, actions)).isTrue();

        assertThat(match.at("/sideTimedOut/A").asText()).isEqualTo("roll");
        assertThat(actions).hasSize(1);
        // 必须在本次事务里直接推进到投骰阶段：联机事件监听器有自己的事务，写回不可靠
        assertThat(match.at("/sidePhases/A").asText()).isEqualTo("PITCHER_ROLL");
        // 人不齐，同步增益当场失效
        assertThat(match.at("/timing/syncOkA").asBoolean()).isFalse();
        assertThat(match.at("/sideDeadlines/A").asLong()).isGreaterThan(now);
    }

    @Test
    void aPitcherWhoNeverRollsDoesNotBlockTheMatch() {
        ObjectNode match = attacking("PITCHER_ROLL");
        List<Runnable> actions = new ArrayList<>();

        assertThat(service().expireAttackSides(rootOf(match), System.currentTimeMillis(), actions)).isTrue();

        assertThat(match.at("/sideTimedOut/A").asText()).isEqualTo("pitcher");
        assertThat(actions).hasSize(1);
    }

    @Test
    void nothingIsForcedWhileThereIsStillTimeLeft() {
        ObjectNode match = attacking("PREPARING");
        match.withObject("/sideDeadlines").put("A", System.currentTimeMillis() + 60_000);
        match.withObject("/sideDeadlines").put("B", System.currentTimeMillis() + 60_000);
        List<Runnable> actions = new ArrayList<>();

        assertThat(service().expireAttackSides(rootOf(match), System.currentTimeMillis(), actions)).isFalse();
        assertThat(match.at("/sidePhases/A").asText()).isEqualTo("PREPARING");
        assertThat(actions).isEmpty();
    }

    @Test
    void missingPlayersStillGetADiceRollButTheTeamLosesTheSyncBonus() {
        org.springframework.context.ApplicationEventPublisher publisher =
                mock(org.springframework.context.ApplicationEventPublisher.class);
        OnlineGameService online = new OnlineGameService(publisher);
        String[] tokens = new String[5];
        for (int slot = 1; slot <= 5; slot++) {
            tokens[slot - 1] = online.join("t1", slot, "队员" + slot, "u" + slot).token();
            online.ping(tokens[slot - 1], (double) System.currentTimeMillis());
            online.calibrate(tokens[slot - 1], 20d);
        }
        online.forceStart("t1");

        // 只有 3 个人点了【掷！】，另外 2 个位置的人始终没出现
        for (int i = 0; i < 3; i++) online.roll(tokens[i], (double) System.currentTimeMillis());
        assertThat(online.isTimingReady("t1")).isFalse();   // 按原逻辑到这里就永远卡住了

        online.forceTiming("t1");
        assertThat(online.isTimingReady("t1")).isTrue();

        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(publisher, atLeastOnce()).publishEvent(captor.capture());
        OnlineGameService.DiceTimingReadyEvent timing = captor.getAllValues().stream()
                .filter(OnlineGameService.DiceTimingReadyEvent.class::isInstance)
                .map(OnlineGameService.DiceTimingReadyEvent.class::cast).findFirst().orElseThrow();
        assertThat(timing.syncOk()).isFalse();             // 人不齐，同步增益失效

        online.finalRoll("t1");
        OnlineGameService.DiceRevealEvent reveal = captor.getAllValues().stream()
                .filter(OnlineGameService.DiceRevealEvent.class::isInstance)
                .map(OnlineGameService.DiceRevealEvent.class::cast).findFirst().orElse(null);
        if (reveal == null) {
            verify(publisher, atLeastOnce()).publishEvent(captor.capture());
            reveal = captor.getAllValues().stream()
                    .filter(OnlineGameService.DiceRevealEvent.class::isInstance)
                    .map(OnlineGameService.DiceRevealEvent.class::cast).findFirst().orElseThrow();
        }
        assertThat(reveal.dice()).hasSize(5);              // 缺席的 2 个位置由服务端补掷
        assertThat(reveal.dice()).allMatch(d -> d.die() >= 1 && d.die() <= 6);
        assertThat(reveal.dice().stream().filter(d -> d.ts() == null)).hasSize(2);
        assertThat(reveal.syncOk()).isFalse();
    }

    /* ---------- 构造 ---------- */

    private ParallelTournamentService service() {
        return new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L,
                org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class),
                org.mockito.Mockito.mock(OnlineGameService.class));
    }

    private ObjectNode state() {
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", "ATTACK");
        ArrayNode teams = root.putArray("teams");
        for (String id : List.of("t1", "t2")) {
            ObjectNode team = teams.addObject();
            team.put("id", id); team.put("name", id); team.put("accumulationPoints", 10);
            team.put("growthCoefficient", 1.0);
            ArrayNode players = team.putArray("players");
            players.addObject().put("id", id + "-back").put("name", "后端").put("role", "back").put("managed", false);
            for (int i = 1; i <= 6; i++)
                players.addObject().put("id", id + "-p" + i).put("name", "前端" + i).put("role", "front").put("managed", false);
        }
        ObjectNode match = root.putObject("matches").putObject("g1");
        match.put("id", "g1"); match.put("a", "t1"); match.put("b", "t2");
        match.put("status", "active"); match.put("winsA", 0); match.put("winsB", 0);
        match.putObject("submitted"); match.putObject("lineups"); match.putObject("prophet");
        return root;
    }

    /** 造一个 A 方停在指定阶段、且截止时间已过的进行中场次。 */
    private ObjectNode attacking(String sidePhase) {
        ObjectNode root = state();
        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        match.put("phase", "ATTACKING");
        match.putObject("sidePhases").put("A", sidePhase).put("B", "WAITING");
        match.putObject("sideDeadlines").put("A", System.currentTimeMillis() - 1);
        return match;
    }

    private ObjectNode rootOf(ObjectNode match) {
        ObjectNode root = state();
        ((ObjectNode) root.path("matches")).set("g1", match);
        return root;
    }
}
