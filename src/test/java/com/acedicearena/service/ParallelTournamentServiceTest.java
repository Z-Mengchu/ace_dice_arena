package com.acedicearena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.acedicearena.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParallelTournamentServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /* ---------- 数值规则 ---------- */

    @Test
    void growthCoefficientUsesCurrentSalesDividedByLastWeekSales() {
        assertThat(ParallelTournamentService.growthCoefficient(new BigDecimal("94485"), new BigDecimal("43281")))
                .isEqualByComparingTo("2.1831");
        assertThat(ParallelTournamentService.growthCoefficient(new BigDecimal("68362"), new BigDecimal("71200")))
                .isEqualByComparingTo("0.9601");
    }

    @Test
    void growthCoefficientIsNeutralWhenLastWeekHasNoSales() {
        assertThat(ParallelTournamentService.growthCoefficient(new BigDecimal("2398"), BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void syncCritRequiresFiveTimestampsWithinHalfASecond() {
        assertThat(ParallelTournamentService.syncCrit(List.of(1_000L, 1_100L, 1_200L, 1_300L, 1_500L), false)).isTrue();
        assertThat(ParallelTournamentService.syncCrit(List.of(1_000L, 1_100L, 1_200L, 1_300L, 1_501L), false)).isFalse();
        // 人不够 5 个时刻（有人完全没掷出）也不能暴击
        assertThat(ParallelTournamentService.syncCrit(List.of(1_000L, 1_100L, 1_200L, 1_300L), false)).isFalse();
    }

    @Test
    void autoRolledMemberAlwaysBreaksTheSyncCrit() {
        // 代掷时刻统一记为 go+1s，即便五人时刻完全对齐也不算同步
        List<Long> aligned = List.of(2_000L, 2_000L, 2_000L, 2_000L, 2_000L);
        assertThat(ParallelTournamentService.syncCrit(aligned, true)).isFalse();
        assertThat(ParallelTournamentService.syncCrit(aligned, false)).isTrue();
    }

    @Test
    void guessBonusIsPointFourPerHitCappedAtTen() {
        assertThat(ParallelTournamentService.guessBonus(0)).isEqualTo(0d);
        assertThat(ParallelTournamentService.guessBonus(3)).isEqualTo(1.2d);
        assertThat(ParallelTournamentService.guessBonus(25)).isEqualTo(10d);
        assertThat(ParallelTournamentService.guessBonus(30)).isEqualTo(10d);
    }

    @Test
    void squadPowerRoundsTheCritProductBeforeAddingGuessBonus() {
        assertThat(ParallelTournamentService.squadPower(20, true, 0)).isEqualTo(30d);
        assertThat(ParallelTournamentService.squadPower(20, false, 0)).isEqualTo(20d);
        assertThat(ParallelTournamentService.squadPower(13, true, 1)).isEqualTo(19.9d);
        assertThat(ParallelTournamentService.squadPower(7, false, 30)).isEqualTo(17d);
    }

    @Test
    void personalPointsAreFinalDicePlusBlindBox() {
        ObjectNode player = mapper.createObjectNode();
        player.put("diceFinal", 4).put("blindBox", -2);
        assertThat(ParallelTournamentService.personalPoints(player)).isEqualTo(2);
    }

    @Test
    void blindBoxDrawsStayWithinDeclaredTiersAndCoverAllOfThem() {
        ParallelTournamentService service = service();
        int weightSum = 0;
        for (int weight : ParallelTournamentService.BLIND_BOX_WEIGHTS) weightSum += weight;
        assertThat(weightSum).isEqualTo(100);
        Map<Integer, Integer> counts = new HashMap<>();
        int total = 20_000;
        for (int i = 0; i < total; i++) {
            int value = service.drawBlindBox();
            counts.merge(value, 1, Integer::sum);
        }
        for (int i = 0; i < ParallelTournamentService.BLIND_BOX_VALUES.length; i++) {
            double expected = ParallelTournamentService.BLIND_BOX_WEIGHTS[i] / 100d;
            double ratio = counts.getOrDefault(ParallelTournamentService.BLIND_BOX_VALUES[i], 0) / (double) total;
            assertThat(ratio).isBetween(Math.max(0d, expected - 0.02d), expected + 0.02d);
        }
        // 负档（debuff）合计应为 25%
        double debuff = (counts.getOrDefault(-1, 0) + counts.getOrDefault(-2, 0)) / (double) total;
        assertThat(debuff).isBetween(0.22d, 0.28d);
    }

    /* ---------- 平局链 ---------- */

    @Test
    void matchTieBreakChainIsPointsThenGmvThenOvertime() {
        assertThat(ParallelTournamentService.compareMatchTieBreak(100, 99, BigDecimal.ZERO, BigDecimal.TEN)).isPositive();
        assertThat(ParallelTournamentService.compareMatchTieBreak(99, 100, BigDecimal.TEN, BigDecimal.ZERO)).isNegative();
        assertThat(ParallelTournamentService.compareMatchTieBreak(100, 100,
                BigDecimal.valueOf(300_000), BigDecimal.valueOf(200_000))).isPositive();
        assertThat(ParallelTournamentService.compareMatchTieBreak(100, 100,
                BigDecimal.valueOf(200_000), BigDecimal.valueOf(300_000))).isNegative();
        // 总点数与 GMV 全部相等 → 全平，待加赛
        assertThat(ParallelTournamentService.compareMatchTieBreak(100, 100, BigDecimal.TEN, BigDecimal.TEN)).isZero();
    }

    @Test
    void matchWinnerWalksTheWholeTieBreakChain() {
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 6, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 1, 1_000L, 600L, false);
        team(root, "t1").put("gmv", BigDecimal.valueOf(300_000));
        team(root, "t2").put("gmv", BigDecimal.valueOf(200_000));
        ObjectNode match = match(root);

        match.put("winsA", 4).put("winsB", 2);
        assertThat(service().decideMatchWinner(root, match)).isEqualTo("A");
        assertThat(match.path("tieBreak").asText()).isEqualTo("胜场");

        // 胜场相等 → 30 人最终个人点数总和（t1 全 6，t2 全 1）
        match.put("winsA", 3).put("winsB", 3);
        assertThat(service().decideMatchWinner(root, match)).isEqualTo("A");
        assertThat(match.path("tieBreak").asText()).isEqualTo("总点数");

        // 总点数也相等 → GMV（t1 30 万 > t2 20 万）
        giveDice(team(root, "t2"), 6, 1_000L, 600L, false);
        assertThat(service().decideMatchWinner(root, match)).isEqualTo("A");
        assertThat(match.path("tieBreak").asText()).isEqualTo("GMV");

        // GMV 也相等 → 全平，不产生胜者，待加赛
        team(root, "t2").put("gmv", BigDecimal.valueOf(300_000));
        assertThat(service().decideMatchWinner(root, match)).isNull();
        assertThat(match.path("tieBreak").asText()).isEqualTo("加赛");
    }

    @Test
    void rematchResetsFullTieMatchBackIntoRollFlow() throws Exception {
        ObjectNode root = battleRoot();
        ObjectNode match = match(root);
        match.put("phase", "OVERTIME_PENDING");
        match.put("tieBreak", "加赛");
        match.put("totalPointsA", 180).put("totalPointsB", 180);
        match.put("gmvA", BigDecimal.valueOf(300_000)).put("gmvB", BigDecimal.valueOf(300_000));
        var record = new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test");
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        var blindBoxes = mock(com.acedicearena.repository.PlayerBlindBoxRepository.class);

        service(states, mock(com.acedicearena.repository.UserAccountRepository.class),
                mock(LobbyEventService.class), blindBoxes).rematch("admin", "g1");

        ObjectNode after = (ObjectNode) mapper.readTree(record.getContent());
        ObjectNode rematched = (ObjectNode) after.path("matches").path("g1");
        assertThat(rematched.path("phase").asText()).isEqualTo("PENDING");
        assertThat(rematched.has("winner")).isFalse();
        assertThat(rematched.has("tieBreak")).isFalse();
        assertThat(rematched.path("winsA").asInt()).isZero();
        assertThat(rematched.path("rounds")).isEmpty();
        assertThat(after.path("stage").asText()).isEqualTo("ROLL");
        // 重赛复用同一 day/round 的唯一键：开始新 ROLL 前定向删除当前在赛玩家的旧盲盒结果
        verify(blindBoxes).deleteByGameDayAndBracketRoundAndPlayerIdIn(anyInt(), anyInt(), any());
    }

    @Test
    void rematchRejectsMatchNotWaitingForOvertime() {
        ObjectNode root = battleRoot();
        var record = new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test");
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service(states,
                mock(com.acedicearena.repository.UserAccountRepository.class),
                mock(LobbyEventService.class)).rematch("admin", "g1"))
                .hasMessage("该场次不在待加赛状态");
    }

    /* ---------- 队长投票 ---------- */

    @Test
    void captainVoteTieBreaksByRosterOrderAndEmptyBallotTakesFirstMember() {
        ParallelTournamentService service = service();
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", "CAPTAIN_VOTE");
        ArrayNode teams = root.putArray("teams");
        ObjectNode t1 = team("t1", 1.0); t1.put("roleVoteDeadlineAt", 1_000L);
        ObjectNode t2 = team("t2", 1.0); t2.put("roleVoteDeadlineAt", 1_000L);
        teams.add(t1); teams.add(t2);
        // t1 平票：u2 与 u3 各一票，名单靠前者当选；t2 无人投票，名单第一人当选
        t1.withObject("/roleVotes").put("u1", "u2").put("u4", "u3");

        assertThat(service.expireVoting(root, 999L)).isFalse();
        assertThat(service.expireVoting(root, 1_000L)).isTrue();

        assertThat(t1.at("/roles/captain").asText()).isEqualTo("u2");
        assertThat(t2.at("/roles/captain").asText()).isEqualTo("u101");
        assertThat(root.path("stage").asText()).isEqualTo("SQUAD_FORM");
        assertThat(root.path("stageDeadlineAt").asLong()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void managedTeammateCannotVoteAndIsNotCountedAsARequiredVoter() {
        ParallelTournamentService service = service();
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", "CAPTAIN_VOTE");
        ObjectNode team = team("t1", 1.0);
        team.put("roleVoteDeadlineAt", System.currentTimeMillis() + 10_000L);
        ((ObjectNode) team.path("players").get(0)).put("managed", true);
        root.putArray("teams").add(team);
        UserAccount managed = user(1L, "t1");

        assertThatThrownBy(() -> service.submitRoleVote(root, managed, List.of("u2")))
                .hasMessage("托管队友不能参与队长投票");
        assertThat(team.path("roleVotes").has("u1")).isFalse();
    }

    /* ---------- 分队 ---------- */

    @Test
    void squadFormRequiresTheFullRosterExactlyOnce() {
        ParallelTournamentService service = service();
        ObjectNode root = squadFormRoot();
        UserAccount captain = user(1L, "t1");
        List<String> roster = rosterIds(team(root, "t1"));

        assertThatThrownBy(() -> service.submitSquadForm(root, captain, roster.subList(0, 29)))
                .hasMessageContaining("分队必须包含本队全部 30 名队员且不重复");
        List<String> duplicated = new ArrayList<>(roster);
        duplicated.set(29, roster.get(0));
        assertThatThrownBy(() -> service.submitSquadForm(root, captain, duplicated))
                .hasMessageContaining("分队必须包含本队全部 30 名队员且不重复");
        List<String> foreign = new ArrayList<>(roster);
        foreign.set(0, "u999");
        assertThatThrownBy(() -> service.submitSquadForm(root, captain, foreign))
                .hasMessageContaining("分队必须包含本队全部 30 名队员且不重复");
        assertThatThrownBy(() -> service.submitSquadForm(root, user(2L, "t1"), roster))
                .hasMessage("只有当选队长可以分队");

        service.submitSquadForm(root, captain, roster);
        JsonNode squads = team(root, "t1").path("squads");
        assertThat(squads).hasSize(6);
        squads.forEach(squad -> assertThat(squad).hasSize(5));
        assertThat(squads.get(0).get(0).asText()).isEqualTo(roster.get(0));
        // 另一队也分队后立即进入 ROLL
        service.submitSquadForm(root, user(101L, "t2"), rosterIds(team(root, "t2")));
        assertThat(root.path("stage").asText()).isEqualTo("ROLL");
    }

    /* ---------- 重掷 ---------- */

    @Test
    void rerollKeepsRollTimestampAndBlindBoxAndIsLimitedToFivePerMatch() {
        ParallelTournamentService service = service();
        ObjectNode root = tacticsRoot(10);
        UserAccount captain = user(1L, "t1");
        ObjectNode target = player(team(root, "t1"), "u5");

        service.submitReroll(root, captain, List.of("u5"));

        assertThat(target.path("diceFinal").asInt()).isBetween(1, 6);
        assertThat(target.path("rerolled").asBoolean()).isTrue();
        assertThat(target.path("rollTs").asLong()).isEqualTo(123_456L);
        assertThat(target.path("blindBox").asInt()).isEqualTo(2);
        assertThat(team(root, "t1").path("rerollUsed").asInt()).isEqualTo(1);
        JsonNode log = team(root, "t1").path("rerollLog").get(0);
        assertThat(log.path("playerId").asText()).isEqualTo("u5");
        assertThat(log.path("from").asInt()).isEqualTo(3);

        for (int i = 0; i < 4; i++) service.submitReroll(root, captain, List.of("u5"));
        assertThatThrownBy(() -> service.submitReroll(root, captain, List.of("u5")))
                .hasMessage("本场重掷次数已用完");
        assertThatThrownBy(() -> service.submitReroll(root, user(2L, "t1"), List.of("u5")))
                .hasMessage("只有当选队长可以重掷");
    }

    @Test
    void rerollIsAlsoBoundedByTheGmvQuotaWhenItIsSmallerThanFive() {
        ParallelTournamentService service = service();
        ObjectNode root = tacticsRoot(2);
        UserAccount captain = user(1L, "t1");

        service.submitReroll(root, captain, List.of("u5"));
        service.submitReroll(root, captain, List.of("u6"));
        assertThatThrownBy(() -> service.submitReroll(root, captain, List.of("u7")))
                .hasMessage("本场重掷次数已用完");
    }

    /* ---------- 盲盒 ---------- */

    @Test
    void blindBoxOpenDrawsOnceAndRequiresTheStage() {
        ParallelTournamentService service = service();
        ObjectNode root = blindBoxRoot();
        UserAccount member = user(1L, "t1");

        ObjectNode notOpenYet = blindBoxRoot();
        notOpenYet.put("stage", "TACTICS");
        assertThatThrownBy(() -> service.openBlindBox(notOpenYet, member))
                .hasMessage("当前不在开盲盒阶段");

        service.openBlindBox(root, member);
        ObjectNode opened = player(team(root, "t1"), "u1");
        assertThat(opened.path("blindBox").asInt()).isBetween(-2, 3);
        assertThat(opened.path("blindBoxOpened").asBoolean()).isTrue();
        assertThat(opened.has("autoOpened")).isFalse();
        assertThatThrownBy(() -> service.openBlindBox(root, member))
                .hasMessage("你已经开过本轮盲盒");
    }

    @Test
    void blindBoxOpenRejectsOutOfRangeBoxIndex() {
        ParallelTournamentService service = service();
        ObjectNode root = blindBoxRoot();
        UserAccount member = user(1L, "t1");

        assertThatThrownBy(() -> service.openBlindBox(root, member, List.of("3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("盲盒序号超出范围");
        assertThatThrownBy(() -> service.openBlindBox(root, member, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("盲盒序号必须是数字");
    }

    /* ---------- 排阵 ---------- */

    @Test
    void squadOrderReordersSquadsLocksAndRejectsResubmission() {
        ParallelTournamentService service = service();
        ObjectNode root = tacticsRoot(10);
        formSquads(team(root, "t1"));
        formSquads(team(root, "t2"));
        UserAccount captain = user(1L, "t1");

        service.submitSquadOrder(root, captain, List.of("3", "1", "2", "4", "5", "6"));

        JsonNode squads = team(root, "t1").path("squads");
        assertThat(squads.get(0).get(0).asText()).isEqualTo("u11");  // 原 3 号小队出任 1 号位
        assertThat(squads.get(1).get(0).asText()).isEqualTo("u1");
        assertThat(squads.get(2).get(0).asText()).isEqualTo("u6");
        assertThat(team(root, "t1").path("squadOrderLocked").asBoolean()).isTrue();

        assertThatThrownBy(() -> service.submitSquadOrder(root, captain, List.of("1", "2", "3", "4", "5", "6")))
                .hasMessage("本队出场顺序已经锁定");
    }

    @Test
    void squadOrderValidatesCaptainPermutationAndFormedSquads() {
        ParallelTournamentService service = service();
        ObjectNode root = tacticsRoot(10);
        formSquads(team(root, "t1"));
        formSquads(team(root, "t2"));
        UserAccount captain = user(1L, "t1");
        List<String> identity = List.of("1", "2", "3", "4", "5", "6");

        assertThatThrownBy(() -> service.submitSquadOrder(root, user(2L, "t1"), identity))
                .hasMessage("只有当选队长可以调整出场顺序");
        assertThatThrownBy(() -> service.submitSquadOrder(root, captain, List.of("1", "1", "2", "3", "4", "5")))
                .hasMessage("小队编号必须是 1~6 且不重复");
        assertThatThrownBy(() -> service.submitSquadOrder(root, captain, List.of("0", "1", "2", "3", "4", "5")))
                .hasMessage("小队编号必须是 1~6 且不重复");
        assertThatThrownBy(() -> service.submitSquadOrder(root, captain, List.of("1", "2", "3", "4", "5")))
                .hasMessage("必须提交 6 个小队的出场顺序");

        ObjectNode noSquads = tacticsRoot(10);
        assertThatThrownBy(() -> service.submitSquadOrder(noSquads, captain, identity))
                .hasMessage("本队尚未完成分队");
    }

    /* ---------- 阶段截止守卫 ---------- */

    @Test
    void tacticAndBlindBoxActionsRejectAfterTheStageDeadline() {
        ParallelTournamentService service = service();
        ObjectNode tactics = tacticsRoot(10);
        formSquads(team(tactics, "t1"));
        formSquads(team(tactics, "t2"));
        tactics.put("stageDeadlineAt", System.currentTimeMillis() - 1L);
        UserAccount captain = user(1L, "t1");

        assertThatThrownBy(() -> service.submitReroll(tactics, captain, List.of("u5")))
                .hasMessage("战术阶段时间已经结束");
        assertThatThrownBy(() -> service.submitSquadOrder(tactics, captain, List.of("1", "2", "3", "4", "5", "6")))
                .hasMessage("战术阶段时间已经结束");

        ObjectNode blindBox = blindBoxRoot();
        blindBox.put("stageDeadlineAt", System.currentTimeMillis() - 1L);
        assertThatThrownBy(() -> service.openBlindBox(blindBox, user(1L, "t1")))
                .hasMessage("开盲盒时间已经结束");
    }

    /* ---------- 玩家视角脱敏 ---------- */

    @Test
    void publicStateViewHidesEnemySquadsDiceAndSealsGuesses() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 6, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 1, 1_000L, 600L, false);
        team(root, "t2").withArray("rerollLog").addObject().put("playerId", "u101").put("from", 1).put("to", 6);
        service.submitRoundGuess(root, user(1L, "t1"), squadIds(team(root, "t2"), 0));

        ObjectNode view = (ObjectNode) service.publicStateView(root, "t1", "u1");

        // 本队完整：squads、点数、盲盒、投票明细全部保留
        ObjectNode own = team(view, "t1");
        assertThat(own.path("squads")).hasSize(6);
        assertThat(player(own, "u1").path("dice").asInt()).isEqualTo(6);
        // 敌方：剥离小队编排、球员级点数/盲盒/时刻、重掷日志、投票明细，只留花名册与公开数值
        ObjectNode enemy = team(view, "t2");
        assertThat(enemy.has("squads")).isFalse();
        assertThat(enemy.has("rerollLog")).isFalse();
        assertThat(enemy.has("roleVotes")).isFalse();
        assertThat(enemy.has("squadOrderLocked")).isFalse();
        assertThat(enemy.path("rerollQuota").asInt()).isEqualTo(10);
        ObjectNode enemyPlayer = player(enemy, "u101");
        assertThat(enemyPlayer.path("name").asText()).isNotBlank();
        for (String field : List.of("dice", "diceFinal", "rollTs", "blindBox", "autoRolled"))
            assertThat(enemyPlayer.has(field)).as(field).isFalse();
        // 猜阵密封：内容不可见，只能看到提交状态
        ObjectNode viewMatch = match(view);
        assertThat(viewMatch.has("guesses")).isFalse();
        assertThat(viewMatch.at("/guessStatus/A/u1").asBoolean()).isTrue();
        assertThat(viewMatch.path("guessStatus").path("B").size()).isZero();
        // 原始状态不被视图过滤改动
        assertThat(match(root).path("guesses").path("A").has("u1")).isTrue();
        assertThat(player(team(root, "t2"), "u101").has("dice")).isTrue();
    }

    @Test
    void publicStateViewTreatsTeamlessViewerAsOutsiderAndFallsBackToPlayerId() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 6, 1_000L, 600L, false);

        ObjectNode outsider = (ObjectNode) service.publicStateView(root, null, null);
        assertThat(team(outsider, "t1").has("squads")).isFalse();
        assertThat(player(team(outsider, "t1"), "u1").has("dice")).isFalse();

        // 账号没有 teamId 时按球员 id 反查本队（沙盘顶替场景）
        ObjectNode sandbox = (ObjectNode) service.publicStateView(root, null, "u101");
        assertThat(team(sandbox, "t2").has("squads")).isTrue();
        assertThat(team(sandbox, "t1").has("squads")).isFalse();
    }

    @Test
    void playerStateViewKeepsOnlyOwnTeamCurrentOpponentAndCurrentMatch() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        root.withArray("teams").add(team("t3", 0.9)).add(team("t4", 0.8));
        ObjectNode unrelated = root.withObject("/matches").putObject("g2");
        unrelated.put("id", "g2").put("a", "t3").put("b", "t4").put("status", "active");
        unrelated.putArray("rounds");
        root.put("champion", "t3");
        root.putObject("dayResults").putObject("day1").put("largeHistory", true);

        ObjectNode view = (ObjectNode) service.playerStateView(root, "t1", "u1");

        List<String> teamIds = new ArrayList<>();
        view.path("teams").forEach(team -> teamIds.add(team.path("id").asText()));
        assertThat(teamIds).containsExactly("t1", "t2");
        assertThat(view.at("/teams/0/players")).hasSize(30);
        assertThat(view.at("/teams/1/players")).hasSize(30);
        List<String> matchIds = new ArrayList<>();
        view.path("matches").fieldNames().forEachRemaining(matchIds::add);
        assertThat(matchIds).containsExactly("g1");
        assertThat(view.path("championName").asText()).isEqualTo("t3");
        assertThat(view.has("dayResults")).isFalse();
        assertThat(root.path("teams")).hasSize(4);
        assertThat(root.has("dayResults")).isTrue();
    }

    /* ---------- 猜阵与单局结算 ---------- */

    @Test
    void roundGuessCountsHitsPerGuessAndRevealsEarlyWhenBothSidesAreComplete() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        // t1 出战小队每人 6 点但不同步；t2 每人 1 点
        giveDice(team(root, "t1"), 6, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 1, 1_000L, 600L, false);
        ObjectNode match = match(root);
        List<String> enemySquadB = squadIds(team(root, "t2"), 0);
        for (long id = 1; id <= 5; id++)
            service.submitRoundGuess(root, user(id, "t1"), enemySquadB);
        assertThat(match.path("rounds")).isEmpty();   // B 方未交齐，密封不揭晓

        List<String> wrongGuesses = List.of("u6", "u7", "u8", "u9", "u10");
        for (long id = 101; id <= 105; id++)
            service.submitRoundGuess(root, user(id, "t2"), wrongGuesses);

        assertThat(match.path("roundPhase").asText()).isEqualTo("REVEAL");
        JsonNode round = match.path("rounds").get(0);
        // 每人 5 猜全中：5 人 × 5 命中 = 25 人次，×0.4 = 10 触发单局上限
        assertThat(round.path("guessHitsA").asInt()).isEqualTo(25);
        assertThat(round.path("guessBonusA").asDouble()).isEqualTo(10d);
        assertThat(round.path("guessHitsB").asInt()).isEqualTo(0);
        assertThat(round.path("guessBonusB").asDouble()).isEqualTo(0d);
        assertThat(round.path("powerA").asDouble()).isEqualTo(40d);  // 30 基础 + 10 猜阵
        assertThat(round.path("powerB").asDouble()).isEqualTo(5d);
        assertThat(round.path("winner").asText()).isEqualTo("A");
        assertThat(match.path("winsA").asInt()).isEqualTo(1);
    }

    @Test
    void roundGuessValidationRejectsStrangersWrongTargetsAndDuplicates() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 3, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 3, 1_000L, 600L, false);
        List<String> enemySquadB = squadIds(team(root, "t2"), 0);

        assertThatThrownBy(() -> service.submitRoundGuess(root, user(6L, "t1"), enemySquadB))
                .hasMessage("只有本轮出战小队成员可以提交猜阵");
        assertThatThrownBy(() -> service.submitRoundGuess(root, user(1L, "t1"), enemySquadB.subList(0, 4)))
                .hasMessage("猜阵必须选择 5 名敌方队员");
        assertThatThrownBy(() -> service.submitRoundGuess(root, user(1L, "t1"),
                        List.of("u1", "u2", "u3", "u4", "u5")))
                .hasMessage("猜阵目标必须是敌方队员");
        service.submitRoundGuess(root, user(1L, "t1"), enemySquadB);
        assertThatThrownBy(() -> service.submitRoundGuess(root, user(1L, "t1"), enemySquadB))
                .hasMessage("你已经提交过本轮猜阵");
    }

    @Test
    void equalPowersMakeTheRoundATieAndNobodyScores() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 3, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 3, 1_000L, 600L, false);
        ObjectNode match = match(root);

        service.revealRound(root, match);

        JsonNode round = match.path("rounds").get(0);
        assertThat(round.path("powerA").asDouble()).isEqualTo(round.path("powerB").asDouble());
        assertThat(round.path("winner").isNull()).isTrue();
        assertThat(match.path("winsA").asInt() + match.path("winsB").asInt()).isZero();
    }

    /* ---------- 真人联机掷骰 ---------- */

    @Test
    void liveRollRejectsOutsideTheRollStageAndOutsideTheSquadWindow() {
        long now = System.currentTimeMillis();
        UserAccount player = user(1L, "t1");

        // 不在 ROLL 阶段
        ObjectNode notRoll = rollRoot(now - 1_000L, now + 30_000L);
        notRoll.put("stage", "SQUAD_FORM");
        assertThatThrownBy(() -> rollingService(notRoll, player).recordLiveRoll("user1", now))
                .hasMessage("当前不在掷骰阶段");

        // go 之前（321 倒计时中）
        ObjectNode beforeGo = rollRoot(now + 3_000L, now + 33_000L);
        assertThatThrownBy(() -> rollingService(beforeGo, player).recordLiveRoll("user1", now))
                .hasMessage("掷骰还未开始，请等待倒计时结束");

        // 窗口已截止
        ObjectNode afterDeadline = rollRoot(now - 31_000L, now - 1L);
        assertThatThrownBy(() -> rollingService(afterDeadline, player).recordLiveRoll("user1", now))
                .hasMessage("本轮掷骰已截止");

        // 本队本轮没有比赛（t3 不在任何 active 场次）
        ObjectNode root = rollRoot(now - 1_000L, now + 30_000L);
        UserAccount outsider = user(201L, "t3");
        assertThatThrownBy(() -> rollingService(root, outsider).recordLiveRoll("user201", now))
                .hasMessage("本队本轮没有比赛");
    }

    @Test
    void liveRollWritesDiceAndClampedTimestampAndRejectsRepeats() throws Exception {
        long before = System.currentTimeMillis();
        ObjectNode root = rollRoot(before - 1_000L, before + 30_000L);
        UserAccount player = user(1L, "t1");
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        when(users.findByUsername("user1")).thenReturn(Optional.of(player));
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        var record = new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        LobbyEventService events = mock(LobbyEventService.class);
        ParallelTournamentService service = service(states, users, events);

        ParallelTournamentService.LiveRoll roll = service.recordLiveRoll("user1", before);
        long after = System.currentTimeMillis();

        assertThat(roll.die()).isBetween(1, 6);
        assertThat(roll.rollTs()).isBetween(before - ParallelTournamentService.ROLL_TOLERANCE_MS, after);
        ObjectNode savedRoot = (ObjectNode) mapper.readTree(record.getContent());
        ObjectNode saved = player(team(savedRoot, "t1"), "u1");
        assertThat(saved.path("dice").asInt()).isEqualTo(roll.die());
        assertThat(saved.path("diceFinal").asInt()).isEqualTo(roll.die());
        assertThat(saved.path("rollTs").asLong()).isEqualTo(roll.rollTs());
        assertThat(saved.has("autoRolled")).isFalse();
        verify(events).gameChanged();

        assertThatThrownBy(() -> service.recordLiveRoll("user1", System.currentTimeMillis()))
                .hasMessage("你本轮已经掷过骰子");
    }

    @Test
    void liveRollClampsForgedTimestampsAndCountsEarlyClicksAsGo() {
        // go 刚过 50ms：夹取下限是 go 本身，抢跑按 go 时刻计；伪造未来时刻夹到收包时刻
        long go = System.currentTimeMillis() - 50L;
        ObjectNode root = rollRoot(go, go + 30_000L);
        UserAccount early = user(1L, "t1");
        UserAccount future = user(2L, "t1");
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        when(users.findByUsername("user1")).thenReturn(Optional.of(early));
        when(users.findByUsername("user2")).thenReturn(Optional.of(future));
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        when(states.findLockedById(1L)).thenReturn(
                Optional.of(new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test")));
        ParallelTournamentService service = service(states, users, mock(LobbyEventService.class));

        long before = System.currentTimeMillis();
        assertThat(service.recordLiveRoll("user1", go - 5_000L).rollTs()).isEqualTo(go);
        assertThat(service.recordLiveRoll("user2", before + 60_000L).rollTs())
                .isBetween(before, System.currentTimeMillis());

        // go 已过 5 秒：夹取下限落到 250ms 容差线上，伪造远古时刻最多只能提前 250ms
        long oldGo = System.currentTimeMillis() - 5_000L;
        ObjectNode oldRoot = rollRoot(oldGo, oldGo + 30_000L);
        UserAccount past = user(3L, "t1");
        var oldUsers = mock(com.acedicearena.repository.UserAccountRepository.class);
        when(oldUsers.findByUsername("user3")).thenReturn(Optional.of(past));
        var oldStates = mock(com.acedicearena.repository.GameStateRepository.class);
        when(oldStates.findLockedById(1L)).thenReturn(
                Optional.of(new com.acedicearena.domain.GameStateRecord(1L, oldRoot.toString(), "test")));
        ParallelTournamentService oldService = service(oldStates, oldUsers, mock(LobbyEventService.class));

        long oldBefore = System.currentTimeMillis();
        assertThat(oldService.recordLiveRoll("user3", oldGo - 60_000L).rollTs())
                .isBetween(oldBefore - ParallelTournamentService.ROLL_TOLERANCE_MS - 50,
                        System.currentTimeMillis() - 200);
    }

    @Test
    void autoRollSkipsPlayersWhoAlreadyRolledLive() {
        long go = System.currentTimeMillis() - 31_000L;
        ObjectNode root = rollRoot(go, System.currentTimeMillis() - 1L);
        ObjectNode live = player(team(root, "t1"), "u1");
        live.put("dice", 5).put("diceFinal", 5).put("rollTs", 111_111L);

        assertThat(service().expireRoll(root, System.currentTimeMillis())).isTrue();

        assertThat(live.path("dice").asInt()).isEqualTo(5);
        assertThat(live.path("rollTs").asLong()).isEqualTo(111_111L);
        assertThat(live.has("autoRolled")).isFalse();
        ObjectNode absent = player(team(root, "t1"), "u2");
        assertThat(absent.path("autoRolled").asBoolean()).isTrue();
        assertThat(absent.path("rollTs").asLong()).isEqualTo(go + ParallelTournamentService.AUTO_ROLL_OFFSET_MS);
    }

    @Test
    void fourLiveRollsPlusOneAutoRollCannotCrit() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        // t1 五人时刻完全对齐，但出战小队中一人是系统代掷
        giveDice(team(root, "t1"), 6, 1_000L, 0L, false);
        giveDice(team(root, "t2"), 1, 1_000L, 600L, false);
        player(team(root, "t1"), squadIds(team(root, "t1"), 0).get(4)).put("autoRolled", true);
        ObjectNode match = match(root);

        service.revealRound(root, match);

        JsonNode round = match.path("rounds").get(0);
        assertThat(round.path("critA").asBoolean()).isFalse();
        assertThat(round.path("powerA").asDouble()).isEqualTo(30d);
    }

    /* ---------- 小队错峰掷骰 ---------- */

    @Test
    void liveRollRejectsBeforeOwnSquadWindowOpensAndAfterGlobalDeadline() {
        long now = System.currentTimeMillis();
        // 2 号小队（u6~u10）在 go+1s 才开掷，提前提交被拒
        ObjectNode root = rollRoot(now - 500L, now + 19_500L);
        assertThatThrownBy(() -> rollingService(root, user(6L, "t1")).recordLiveRoll("user6", now))
                .hasMessage("还没轮到你们小队掷骰");

        // 本小队已开掷、全局截止未到：接受（截止时刻全员统一，不再按小队截断）
        ObjectNode open = rollRoot(now - 17_000L, now + 3_000L);
        assertThat(rollingService(open, user(6L, "t1")).recordLiveRoll("user6", now).rollTs()).isPositive();

        // 全局截止时间已过才被拒
        ObjectNode late = rollRoot(now - 21_000L, now - 1L);
        assertThatThrownBy(() -> rollingService(late, user(6L, "t1")).recordLiveRoll("user6", now))
                .hasMessage("本轮掷骰已截止");
    }

    @Test
    void lastLiveRollFastForwardsToBlindBox() throws Exception {
        long now = System.currentTimeMillis();
        long go = now - 2_000L;
        ObjectNode root = rollRoot(go, go + 20_000L);
        // 除 u1 外全员已有掷骰
        root.path("teams").forEach(teamNode -> teamNode.path("players").forEach(playerNode -> {
            ObjectNode player = (ObjectNode) playerNode;
            if (!"u1".equals(player.path("id").asText()))
                player.put("dice", 3).put("diceFinal", 3).put("rollTs", go + 100L);
        }));
        UserAccount account = user(1L, "t1");
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        when(users.findByUsername("user1")).thenReturn(Optional.of(account));
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        var record = new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        ParallelTournamentService service = service(states, users, mock(LobbyEventService.class));

        service.recordLiveRoll("user1", now);

        ObjectNode saved = (ObjectNode) mapper.readTree(record.getContent());
        assertThat(saved.path("stage").asText()).isEqualTo("BLIND_BOX");
        assertThat(saved.path("stageDeadlineAt").asLong()).isGreaterThan(now);
    }

    /* ---------- 战术确认 ---------- */

    @Test
    void tacticsConfirmLocksRerollAndSquadOrderUntilCancelled() {
        ParallelTournamentService service = service();
        ObjectNode root = tacticsRoot(10);
        formSquads(team(root, "t1"));
        formSquads(team(root, "t2"));
        UserAccount captain = user(1L, "t1");

        assertThatThrownBy(() -> service.submitTacticsConfirm(root, user(2L, "t1"), true))
                .hasMessage("只有当选队长可以确认战术布置");

        service.submitTacticsConfirm(root, captain, true);
        assertThat(team(root, "t1").path("tacticsConfirmed").asBoolean()).isTrue();
        assertThatThrownBy(() -> service.submitReroll(root, captain, List.of("u5")))
                .hasMessage("已确认完成战术布置，请先取消确认再调整");
        assertThatThrownBy(() -> service.submitSquadOrder(root, captain, List.of("1", "2", "3", "4", "5", "6")))
                .hasMessage("已确认完成战术布置，请先取消确认再调整");
        assertThat(root.path("stage").asText()).as("t2 未确认，不提前推进").isEqualTo("TACTICS");

        service.submitTacticsConfirm(root, captain, false);
        assertThat(team(root, "t1").path("tacticsConfirmed").asBoolean()).isFalse();
        service.submitReroll(root, captain, List.of("u5"));
        assertThat(player(team(root, "t1"), "u5").path("rerolled").asBoolean()).isTrue();
    }

    @Test
    void battleStartsImmediatelyWhenEveryActiveTeamConfirms() {
        ParallelTournamentService service = service();
        ObjectNode root = tacticsRoot(10);
        formSquads(team(root, "t1"));
        formSquads(team(root, "t2"));

        service.submitTacticsConfirm(root, user(101L, "t2"), true);
        assertThat(root.path("stage").asText()).isEqualTo("TACTICS");

        service.submitTacticsConfirm(root, user(1L, "t1"), true);
        assertThat(root.path("stage").asText()).isEqualTo("BATTLE");
        ObjectNode match = match(root);
        assertThat(match.path("phase").asText()).isEqualTo("BATTLE");
        assertThat(match.path("round").asInt()).isEqualTo(1);
        assertThat(match.path("roundPhase").asText()).isEqualTo("GUESS");
        assertThat(match.path("guessOpenedAt").asLong()).isPositive();
        assertThat(match.path("preGuesses").isObject()).isTrue();
        assertThat(team(root, "t1").path("squadOrderLocked").asBoolean()).isTrue();
        assertThat(team(root, "t2").path("squadOrderLocked").asBoolean()).isTrue();
    }

    /* ---------- 提前猜阵 ---------- */

    @Test
    void preGuessSubmitsOverwritesAndRejectsCurrentRound() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 3, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 3, 1_000L, 600L, false);
        ObjectNode match = match(root);
        // u11 属于 3 号小队，第 3 局出战；当前第 1 局
        List<String> targets = List.of("u101", "u102", "u103", "u104", "u105");

        service.submitPreGuess(root, user(11L, "t1"), targets);
        assertThat(match.at("/preGuesses/3/A/u11")).hasSize(5);

        // 改投：覆盖旧值
        service.submitPreGuess(root, user(11L, "t1"), List.of("u106", "u107", "u108", "u109", "u110"));
        assertThat(match.at("/preGuesses/3/A/u11").get(0).asText()).isEqualTo("u106");

        // 当前轮（1 号小队 u1）请走普通猜阵通道
        assertThatThrownBy(() -> service.submitPreGuess(root, user(1L, "t1"), targets))
                .hasMessage("当前轮次请直接提交猜阵");
        // 已结束轮次同样被拒
        match.put("round", 2);
        assertThatThrownBy(() -> service.submitPreGuess(root, user(1L, "t1"), targets))
                .hasMessage("你们小队的轮次已经结束");
        match.put("round", 1);
        assertThatThrownBy(() -> service.submitPreGuess(root, user(11L, "t1"),
                        List.of("u1", "u2", "u3", "u4", "u5")))
                .hasMessage("猜阵目标必须是敌方队员");
    }

    @Test
    void retractGuessRemovesLiveAndFutureGuesses() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 3, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 3, 1_000L, 600L, false);
        ObjectNode match = match(root);
        service.submitPreGuess(root, user(11L, "t1"), List.of("u101", "u102", "u103", "u104", "u105"));
        service.submitRoundGuess(root, user(1L, "t1"), squadIds(team(root, "t2"), 0));

        // 撤回当前轮未揭晓的 live 猜阵，撤回后可重投
        service.retractGuess(root, user(1L, "t1"));
        assertThat(match.at("/guesses/A").has("u1")).isFalse();
        service.submitRoundGuess(root, user(1L, "t1"), squadIds(team(root, "t2"), 0));
        assertThat(match.at("/guesses/A").has("u1")).isTrue();

        // 撤回后续轮次的提前猜阵
        service.retractGuess(root, user(11L, "t1"));
        assertThat(match.at("/preGuesses/3/A").has("u11")).isFalse();
        assertThatThrownBy(() -> service.retractGuess(root, user(11L, "t1")))
                .hasMessage("你没有可撤回的猜阵");
    }

    @Test
    void preGuessesMergeIntoTheRoundWhenItOpensAndStaleTargetsAreFiltered() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 3, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 3, 1_000L, 600L, false);
        ObjectNode match = match(root);
        service.submitPreGuess(root, user(11L, "t1"), List.of("u101", "u102", "u103", "u104", "u105"));

        // 进到第 2 局：第 3 局的提前猜阵还不生效
        match.put("roundPhase", "REVEAL");
        match.put("revealUntil", System.currentTimeMillis() - 1);
        assertThat(service.completeRoundReveals(root, System.currentTimeMillis())).isTrue();
        assertThat(match.path("round").asInt()).isEqualTo(2);
        assertThat(match.at("/guesses/A").has("u11")).isFalse();

        // 沙盘中途换人：敌方花名册移除 u101，合并时按当前花名册过滤失效目标
        ArrayNode enemyPlayers = (ArrayNode) team(root, "t2").path("players");
        for (int i = enemyPlayers.size() - 1; i >= 0; i--)
            if ("u101".equals(enemyPlayers.get(i).path("id").asText())) enemyPlayers.remove(i);

        match.put("roundPhase", "REVEAL");
        match.put("revealUntil", System.currentTimeMillis() - 1);
        long before = System.currentTimeMillis();
        assertThat(service.completeRoundReveals(root, System.currentTimeMillis())).isTrue();
        assertThat(match.path("round").asInt()).isEqualTo(3);
        assertThat(match.path("guessOpenedAt").asLong()).isGreaterThanOrEqualTo(before);
        List<String> mergedIds = new ArrayList<>();
        match.at("/guesses/A/u11").forEach(id -> mergedIds.add(id.asText()));
        assertThat(mergedIds).containsExactly("u102", "u103", "u104", "u105");
        assertThat(match.path("preGuesses").has("3")).isFalse();
    }

    @Test
    void publicStateViewSealsPreGuessesIntoStatusFlags() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 3, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 3, 1_000L, 600L, false);
        service.submitPreGuess(root, user(11L, "t1"), List.of("u101", "u102", "u103", "u104", "u105"));

        ObjectNode view = (ObjectNode) service.publicStateView(root, "t1", "u1");
        ObjectNode viewMatch = match(view);
        assertThat(viewMatch.has("preGuesses")).isFalse();
        assertThat(viewMatch.at("/preGuessStatus/3/A/u11").asBoolean()).isTrue();
        assertThat(viewMatch.path("preGuessStatus").path("3").path("B").size()).isZero();
        // 原始状态不被视图过滤改动
        assertThat(match(root).at("/preGuesses/3/A/u11")).hasSize(5);
    }

    /* ---------- 猜阵 5 秒下限 ---------- */

    @Test
    void earlyRevealWaitsForTheFiveSecondMinimumAndTheScannerRevealsAfterwards() {
        ParallelTournamentService service = service();
        ObjectNode root = battleRoot();
        giveDice(team(root, "t1"), 6, 1_000L, 600L, false);
        giveDice(team(root, "t2"), 1, 1_000L, 600L, false);
        ObjectNode match = match(root);
        long openedAt = System.currentTimeMillis();
        match.put("guessOpenedAt", openedAt);
        for (long id = 1; id <= 5; id++)
            service.submitRoundGuess(root, user(id, "t1"), squadIds(team(root, "t2"), 0));
        // B 方最后一份提交也不立即揭晓：未满 5 秒
        for (long id = 101; id <= 105; id++)
            service.submitRoundGuess(root, user(id, "t2"), squadIds(team(root, "t1"), 0));
        assertThat(match.path("roundPhase").asText()).isEqualTo("GUESS");

        // 5 秒下限之前扫描器不揭晓
        assertThat(service.expireGuesses(root, openedAt + 3_000L)).isFalse();
        assertThat(match.path("roundPhase").asText()).isEqualTo("GUESS");
        // 满 5 秒后扫描器揭晓，不用等 30 秒硬截止
        assertThat(service.expireGuesses(root, openedAt + 5_000L)).isTrue();
        assertThat(match.path("roundPhase").asText()).isEqualTo("REVEAL");
    }

    /* ---------- 总冠军判定 ---------- */

    @Test
    void sameTeamWinningBothDaysBecomesOverallChampionDirectly() {
        ObjectNode dayResults = mapper.createObjectNode();
        dayResults.set("day1", dayResult("t1", List.of(
                new OverallTeam("t1", 3, "300000"), new OverallTeam("t2", 2, "900000"))));
        dayResults.set("day2", dayResult("t1", List.of(
                new OverallTeam("t1", 3, "300000"), new OverallTeam("t2", 2, "900000"))));

        ObjectNode overall = ParallelTournamentService.decideOverall(dayResults);

        assertThat(overall.path("status").asText()).isEqualTo("DECIDED");
        assertThat(overall.path("champion").asText()).isEqualTo("t1");
        assertThat(overall.path("decidedBy").asText()).isEqualTo("BOTH_DAYS");
        assertThat(overall.path("candidates")).hasSize(1);
        // 排名先看累计胜场再比 GMV：t1 胜场多排第一，t2 GMV 高也只能排第二
        assertThat(overall.path("standings").get(0).path("id").asText()).isEqualTo("t1");
        assertThat(overall.path("standings").get(1).path("totalGmv").decimalValue())
                .isEqualByComparingTo("1800000");
    }

    @Test
    void differentDayChampionsAreDecidedByTotalMatchWins() {
        // t1 赢第 1 天后第 2 天首轮出局；t2 第 1 天亚军、第 2 天夺冠，累计胜场反超
        ObjectNode dayResults = mapper.createObjectNode();
        dayResults.set("day1", dayResult("t1", List.of(
                new OverallTeam("t1", 3, "100000"), new OverallTeam("t2", 2, "100000"))));
        dayResults.set("day2", dayResult("t2", List.of(
                new OverallTeam("t1", 0, "100000"), new OverallTeam("t2", 3, "100000"))));

        ObjectNode overall = ParallelTournamentService.decideOverall(dayResults);

        assertThat(overall.path("status").asText()).isEqualTo("DECIDED");
        assertThat(overall.path("champion").asText()).isEqualTo("t2");
        assertThat(overall.path("decidedBy").asText()).isEqualTo("MATCH_WINS");
    }

    @Test
    void equalMatchWinsAreDecidedByTotalGmv() {
        ObjectNode dayResults = mapper.createObjectNode();
        dayResults.set("day1", dayResult("t1", List.of(
                new OverallTeam("t1", 3, "100000"), new OverallTeam("t2", 2, "200000"))));
        dayResults.set("day2", dayResult("t2", List.of(
                new OverallTeam("t1", 2, "100000"), new OverallTeam("t2", 3, "300000"))));

        ObjectNode overall = ParallelTournamentService.decideOverall(dayResults);

        assertThat(overall.path("status").asText()).isEqualTo("DECIDED");
        assertThat(overall.path("champion").asText()).isEqualTo("t2");
        assertThat(overall.path("decidedBy").asText()).isEqualTo("GMV");
    }

    @Test
    void equalWinsAndEqualGmvLeaveTheChampionshipPendingOvertime() {
        ObjectNode dayResults = mapper.createObjectNode();
        dayResults.set("day1", dayResult("t1", List.of(
                new OverallTeam("t1", 3, "100000"), new OverallTeam("t2", 2, "300000"))));
        dayResults.set("day2", dayResult("t2", List.of(
                new OverallTeam("t1", 2, "300000"), new OverallTeam("t2", 3, "100000"))));

        ObjectNode overall = ParallelTournamentService.decideOverall(dayResults);

        assertThat(overall.path("status").asText()).isEqualTo("OVERTIME_PENDING");
        assertThat(overall.path("champion").isNull()).isTrue();
        assertThat(overall.path("candidates")).hasSize(2);
        assertThat(overall.path("candidates").get(0).path("id").asText()).isEqualTo("t1");
        assertThat(overall.path("candidates").get(1).path("id").asText()).isEqualTo("t2");
        assertThat(overall.path("candidates").get(0).path("totalMatchWins").asInt()).isEqualTo(5);
        assertThat(overall.path("candidates").get(0).path("totalGmv").decimalValue())
                .isEqualByComparingTo("400000");
    }

    /* ---------- 总冠军加赛 ---------- */

    @Test
    void overtimeBuildsASingleFinalFromDayTwoSnapshotAndDecidesTheChampion() throws Exception {
        // 两天冠军不同且累计胜场、GMV 全平 → 待加赛
        ObjectNode dayResults = mapper.createObjectNode();
        dayResults.set("day1", dayResult("t1", List.of(
                new OverallTeam("t1", 3, "100000"), new OverallTeam("t2", 2, "300000"))));
        ObjectNode day2 = dayResult("t2", List.of(
                new OverallTeam("t1", 2, "300000"), new OverallTeam("t2", 3, "100000")));
        // day2 快照携带完整花名册，加赛队伍以此重建
        for (JsonNode teamNode : day2.path("teams")) {
            ObjectNode snapshot = (ObjectNode) teamNode;
            String teamId = snapshot.path("id").asText();
            long firstId = "t1".equals(teamId) ? 1L : 101L;
            ArrayNode players = snapshot.putArray("players");
            for (int i = 0; i < 30; i++)
                players.addObject().put("id", "u" + (firstId + i)).put("name", teamId + "队员" + (i + 1))
                        .put("department", "销售部").put("standIn", false);
        }
        dayResults.set("day2", day2);
        ObjectNode pending = ParallelTournamentService.decideOverall(dayResults);
        assertThat(pending.path("status").asText()).isEqualTo("OVERTIME_PENDING");
        ObjectNode previous = mapper.createObjectNode();
        previous.put("mode", "parallel"); previous.put("day", 2);
        previous.set("dayResults", dayResults);
        previous.set("overallResult", pending);
        var record = new com.acedicearena.domain.GameStateRecord(1L, previous.toString(), "test");
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        when(states.findById(1L)).thenReturn(Optional.of(record));
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        // u1 回查到前端挂机账号：role/afk/managed 由 users 表补全
        UserAccount afkFront = user(1L, "t1");
        afkFront.setPerformance(true, BigDecimal.valueOf(100_000L));
        afkFront.setAfk(true);
        when(users.findAll()).thenReturn(List.of(afkFront));
        ParallelTournamentService service = service(states, users, mock(LobbyEventService.class));

        service.startOvertime("admin");

        ObjectNode root = (ObjectNode) mapper.readTree(record.getContent());
        assertThat(root.path("mode").asText()).isEqualTo("overtime");
        assertThat(root.path("day").asInt()).isEqualTo(3);
        assertThat(root.path("stage").asText()).isEqualTo("CAPTAIN_VOTE");
        assertThat(root.path("teams")).hasSize(2);
        assertThat(root.path("matches").path("f1").path("a").asText()).isEqualTo("t1");
        assertThat(root.path("matches").path("f1").path("b").asText()).isEqualTo("t2");
        ObjectNode overtimePlayer = player(team(root, "t1"), "u1");
        assertThat(overtimePlayer.path("role").asText()).isEqualTo("front");
        assertThat(overtimePlayer.path("afk").asBoolean()).isTrue();
        assertThat(overtimePlayer.path("managed").asBoolean()).isTrue();

        // 用测试推进跑完整场加赛，直到产生总冠军
        for (int step = 0; step < 200; step++) {
            if (mapper.readTree(record.getContent()).hasNonNull("champion")) break;
            service.simulateStep("admin");
        }
        ObjectNode finished = (ObjectNode) mapper.readTree(record.getContent());
        String champion = finished.path("champion").asText(null);
        assertThat(champion).isNotNull().isIn("t1", "t2");
        ObjectNode overall = (ObjectNode) finished.path("overallResult");
        assertThat(overall.path("champion").asText()).isEqualTo(champion);
        assertThat(overall.path("status").asText()).isEqualTo("DECIDED");
        assertThat(overall.path("decidedBy").asText()).isEqualTo("OVERTIME");
        assertThat(finished.path("overallChampion").asText()).isEqualTo(champion);
        assertThat(finished.path("matches").path("f1").path("status").asText()).isEqualTo("done");
        // 加赛不回写每日结果
        assertThat(finished.path("dayResults").has("day3")).isFalse();
    }

    @Test
    void overtimeCannotStartWithoutAPendingChampionship() {
        ObjectNode previous = mapper.createObjectNode();
        previous.put("mode", "parallel"); previous.put("day", 2);
        previous.putObject("dayResults");
        var record = new com.acedicearena.domain.GameStateRecord(1L, previous.toString(), "test");
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        when(states.findById(1L)).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service(states,
                mock(com.acedicearena.repository.UserAccountRepository.class),
                mock(LobbyEventService.class)).startOvertime("admin"))
                .hasMessage("当前不需要总冠军加赛");
    }

    /* ---------- 构造 ---------- */

    private ObjectNode dayResult(String champion, List<OverallTeam> teams) {
        ObjectNode result = mapper.createObjectNode();
        result.put("champion", champion);
        ArrayNode teamsNode = result.putArray("teams");
        for (OverallTeam team : teams) {
            ObjectNode node = teamsNode.addObject();
            node.put("id", team.id()); node.put("name", team.id() + "战区");
            node.put("matchWins", team.matchWins());
            node.put("gmv", new BigDecimal(team.gmv()));
        }
        return result;
    }

    private record OverallTeam(String id, int matchWins, String gmv) {}

    private ParallelTournamentService service() {
        return service(org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(LobbyEventService.class));
    }

    private ParallelTournamentService service(com.acedicearena.repository.GameStateRepository states,
                                              com.acedicearena.repository.UserAccountRepository users,
                                              LobbyEventService events) {
        return service(states, users, events,
                org.mockito.Mockito.mock(com.acedicearena.repository.PlayerBlindBoxRepository.class));
    }

    /** 桩事务管理器：同步执行回调并视为已提交，供运行态 seam 在单测中工作。 */
    private static org.springframework.transaction.PlatformTransactionManager stubTransactions() {
        var txManager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> new org.springframework.transaction.support.SimpleTransactionStatus());
        return txManager;
    }

    private ParallelTournamentService service(com.acedicearena.repository.GameStateRepository states,
                                              com.acedicearena.repository.UserAccountRepository users,
                                              LobbyEventService events,
                                              com.acedicearena.repository.PlayerBlindBoxRepository blindBoxes) {
        return new ParallelTournamentService(states, users,
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                events, 6_000L,
                org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.MatchReportRepository.class),
                blindBoxes,
                // 运行态用真实实例（同一批 mock 仓库 + 桩事务），simulateStep/forceMatch 才能走完盲盒关闭
                new BlindBoxRoundService(states, users, blindBoxes, mapper, events, stubTransactions()),
                stubTransactions());
    }

    /** ROLL 阶段：t1/t2 各 30 人，g1 进行中，go 与截止时间由参数指定；小队错峰时刻表与真实状态一致。 */
    private ObjectNode rollRoot(long rollGoAt, long stageDeadlineAt) {
        ObjectNode root = twoTeamRoot("ROLL");
        root.put("rollGoAt", rollGoAt);
        ArrayNode rollOpenAts = root.putArray("rollOpenAts");
        for (int k = 0; k < 6; k++) rollOpenAts.add(rollGoAt + k * 1_000L);
        root.put("stageDeadlineAt", stageDeadlineAt);
        formSquads(team(root, "t1"));
        formSquads(team(root, "t2"));
        return root;
    }

    /** 带持久化仓库的 service：findLockedById 返回装有 root 的记录，账号按用户名可查。 */
    private ParallelTournamentService rollingService(ObjectNode root, UserAccount... accounts) {
        var states = mock(com.acedicearena.repository.GameStateRepository.class);
        when(states.findLockedById(1L)).thenReturn(
                Optional.of(new com.acedicearena.domain.GameStateRecord(1L, root.toString(), "test")));
        var users = mock(com.acedicearena.repository.UserAccountRepository.class);
        for (UserAccount account : accounts)
            when(users.findByUsername(account.getUsername())).thenReturn(Optional.of(account));
        return service(states, users, mock(LobbyEventService.class));
    }

    private UserAccount user(long id, String teamId) {
        UserAccount user = new UserAccount("user" + id, "玩家" + id, "销售部", "USER", "hash", "salt");
        ReflectionTestUtils.setField(user, "id", id);
        user.assignTeam(teamId);
        return user;
    }

    /** t1: u1..u30，t2: u101..u130，各 30 人。 */
    private ObjectNode team(String teamId, double coefficient) {
        ObjectNode team = mapper.createObjectNode();
        team.put("id", teamId); team.put("name", teamId);
        team.put("growthCoefficient", coefficient);
        team.put("rerollQuota", 10); team.put("rerollUsed", 0);
        team.putObject("roles"); team.putObject("roleVotes");
        long firstId = "t1".equals(teamId) ? 1L : 101L;
        ArrayNode players = team.putArray("players");
        for (int i = 0; i < 30; i++)
            players.addObject().put("id", "u" + (firstId + i)).put("name", teamId + "队员" + (i + 1))
                    .put("role", i < 15 ? "front" : "back").put("managed", false);
        return team;
    }

    private ObjectNode twoTeamRoot(String stage) {
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", stage);
        root.putArray("teams").add(team("t1", 1.2)).add(team("t2", 1.0));
        ObjectNode match = root.putObject("matches").putObject("g1");
        match.put("id", "g1"); match.put("a", "t1"); match.put("b", "t2");
        match.put("winsA", 0); match.put("winsB", 0); match.put("round", 1);
        match.put("status", "active"); match.put("phase", "PENDING");
        match.putArray("rounds");
        return root;
    }

    private ObjectNode squadFormRoot() {
        ObjectNode root = twoTeamRoot("SQUAD_FORM");
        root.put("stageDeadlineAt", System.currentTimeMillis() + 60_000L);
        team(root, "t1").withObject("/roles").put("captain", "u1");
        team(root, "t2").withObject("/roles").put("captain", "u101");
        return root;
    }

    /** BLIND_BOX 阶段：t1/t2 的 g1 进行中，截止时间在将来。 */
    private ObjectNode blindBoxRoot() {
        ObjectNode root = twoTeamRoot("BLIND_BOX");
        root.put("stageDeadlineAt", System.currentTimeMillis() + 60_000L);
        return root;
    }

    private ObjectNode tacticsRoot(int rerollQuota) {
        ObjectNode root = twoTeamRoot("TACTICS");
        root.put("stageDeadlineAt", System.currentTimeMillis() + 60_000L);
        team(root, "t1").withObject("/roles").put("captain", "u1");
        team(root, "t1").put("rerollQuota", rerollQuota);
        team(root, "t2").withObject("/roles").put("captain", "u101");
        ObjectNode target = player(team(root, "t1"), "u5");
        target.put("dice", 3).put("diceFinal", 3).put("rollTs", 123_456L).put("blindBox", 2);
        player(team(root, "t1"), "u6").put("diceFinal", 2);
        player(team(root, "t1"), "u7").put("diceFinal", 1);
        return root;
    }

    private ObjectNode battleRoot() {
        ObjectNode root = twoTeamRoot("BATTLE");
        formSquads(team(root, "t1"));
        formSquads(team(root, "t2"));
        ObjectNode match = match(root);
        match.put("phase", "BATTLE").put("round", 1).put("roundPhase", "GUESS");
        match.put("guessDeadlineAt", System.currentTimeMillis() + 30_000L);
        match.putObject("guesses").putObject("A");
        ((ObjectNode) match.path("guesses")).putObject("B");
        return root;
    }

    private void formSquads(ObjectNode team) {
        ArrayNode squads = team.putArray("squads");
        List<String> roster = rosterIds(team);
        for (int s = 0; s < 6; s++) {
            ArrayNode squad = squads.addArray();
            for (int i = s * 5; i < s * 5 + 5; i++) squad.add(roster.get(i));
        }
    }

    private void giveDice(ObjectNode team, int die, long firstRollTs, long stepMs, boolean auto) {
        int index = 0;
        for (JsonNode playerNode : team.path("players")) {
            ObjectNode player = (ObjectNode) playerNode;
            player.put("dice", die).put("diceFinal", die);
            player.put("rollTs", firstRollTs + index * stepMs);
            player.put("blindBox", 0).put("blindBoxOpened", true);
            if (auto) player.put("autoRolled", true);
            index++;
        }
    }

    private List<String> rosterIds(ObjectNode team) {
        List<String> ids = new ArrayList<>();
        team.path("players").forEach(player -> ids.add(player.path("id").asText()));
        return ids;
    }

    private List<String> squadIds(ObjectNode team, int squadIndex) {
        List<String> ids = new ArrayList<>();
        team.path("squads").path(squadIndex).forEach(id -> ids.add(id.asText()));
        return ids;
    }

    private ObjectNode team(ObjectNode root, String teamId) {
        for (JsonNode team : root.path("teams")) if (teamId.equals(team.path("id").asText())) return (ObjectNode) team;
        throw new AssertionError("team not found: " + teamId);
    }

    private ObjectNode player(ObjectNode team, String playerId) {
        for (JsonNode player : team.path("players"))
            if (playerId.equals(player.path("id").asText())) return (ObjectNode) player;
        throw new AssertionError("player not found: " + playerId);
    }

    private ObjectNode match(ObjectNode root) {
        return (ObjectNode) root.path("matches").path("g1");
    }
}
