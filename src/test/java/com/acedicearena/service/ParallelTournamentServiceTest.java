package com.acedicearena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.domain.GameStateRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelTournamentServiceTest {
    @Test
    void firstFinishedAttackWaitsUntilOpponentRevealBeforeSettlement() throws Exception {
        var mapper = new ObjectMapper();
        var states = org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class);
        var root = mapper.createObjectNode(); root.put("mode", "parallel"); root.put("stage", "ATTACK");
        var teams = root.putArray("teams");
        teams.addObject().put("id", "t1").put("accumulationPoints", 10).put("growthCoefficient", 1.1);
        teams.addObject().put("id", "t2").put("accumulationPoints", 12).put("growthCoefficient", 1.0);
        var match = root.putObject("matches").putObject("g1");
        match.put("id", "g1"); match.put("a", "t1"); match.put("b", "t2"); match.put("status", "active");
        match.put("phase", "ATTACKING"); match.put("winsA", 0); match.put("winsB", 0);
        match.putObject("sidePhases").put("A", "PITCHER_ROLL").put("B", "PITCHER_ROLL");
        match.putObject("lineups").putArray("A"); ((com.fasterxml.jackson.databind.node.ObjectNode) match.path("lineups")).putArray("B");
        match.putObject("prophet").putArray("A"); ((com.fasterxml.jackson.databind.node.ObjectNode) match.path("prophet")).putArray("B");
        var boosts = match.putObject("attackBoost");
        boosts.putObject("claimsA").put("u10", 1.2).put("u11", 1.4);
        boosts.putObject("claimsB");
        match.putObject("rolls");
        var record = new GameStateRecord(1L, root.toString(), "system");
        org.mockito.Mockito.when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        var service = new ParallelTournamentService(states,
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 1_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var dice = java.util.List.of(
                new OnlineGameService.DiceResult(1, 2, 1d, false), new OnlineGameService.DiceResult(2, 3, 2d, false),
                new OnlineGameService.DiceResult(3, 4, 3d, false), new OnlineGameService.DiceResult(4, 5, 4d, false),
                new OnlineGameService.DiceResult(5, 6, 5d, false));

        service.onDiceReveal(new OnlineGameService.DiceRevealEvent("t1", dice, true, 200d));
        JsonNode afterFirst = mapper.readTree(record.getContent());
        assertThat(afterFirst.at("/matches/g1/phase").asText()).isEqualTo("ATTACKING");
        assertThat(afterFirst.at("/matches/g1/sidePhases/A").asText()).isEqualTo("WAITING");
        assertThat(afterFirst.at("/matches/g1/sidePhases/B").asText()).isEqualTo("PITCHER_ROLL");

        service.onDiceReveal(new OnlineGameService.DiceRevealEvent("t2", dice, false, 700d));
        JsonNode settled = mapper.readTree(record.getContent());
        assertThat(settled.at("/matches/g1/phase").asText()).isEqualTo("RESULT");
        assertThat(settled.at("/matches/g1/rolls/A/finalAttack").isNumber()).isTrue();
        assertThat(settled.at("/matches/g1/rolls/B/finalAttack").isNumber()).isTrue();
        assertThat(settled.at("/matches/g1/rolls/A/attackBoostMultiplier").asDouble()).isEqualTo(1.3);
        assertThat(settled.at("/matches/g1/rolls/A/finalAttack").asDouble()).isEqualTo(57.2);
        assertThat(settled.at("/matches/g1/rolls/B/attackBoostMultiplier").asDouble()).isEqualTo(1.0);
    }

    @Test
    void attackBoostWindowStartsWithParallelAttackAndLastsTwentySeconds() {
        var mapper = new ObjectMapper();
        var service = new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L,
                org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var match = mapper.createObjectNode();
        long before = System.currentTimeMillis();

        service.startParallelAttack(match);

        long deadline = match.at("/attackBoost/deadlineAt").asLong();
        assertThat(deadline).isBetween(before + 20_000L, System.currentTimeMillis() + 20_000L);
        assertThat(match.at("/attackBoost/claimsA").isObject()).isTrue();
        assertThat(match.at("/attackBoost/claimsB").isObject()).isTrue();
    }

    @Test
    void managedTeammateCannotVoteAndIsNotCountedAsARequiredVoter() {
        var mapper = new ObjectMapper();
        var service = new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var root = mapper.createObjectNode(); root.put("stage", "ROLE_VOTE");
        var team = root.putArray("teams").addObject();
        team.put("id", "t1"); team.put("roleVoteStage", "captain");
        team.put("roleVoteDeadlineAt", System.currentTimeMillis() + 10_000L);
        team.putObject("roles"); team.putObject("roleVotes");
        team.putArray("players").addObject().put("id", "unull").put("name", "托管队友")
                .put("role", "front").put("managed", true);
        ((com.fasterxml.jackson.databind.node.ArrayNode) team.path("players")).addObject()
                .put("id", "u2").put("name", "真实队员").put("role", "front");
        var managed = new UserAccount("__arena_stand_in_1", "托管队友", "测试部", "USER", "hash", "salt");
        managed.assignTeam("t1");

        assertThatThrownBy(() -> service.submitRoleVote(root, managed, java.util.List.of("u2")))
                .hasMessage("托管沙盘队友不能参与角色投票");
        assertThat(team.at("/roleVotes/captain").isMissingNode()).isTrue();
    }

    @Test
    void roleVoteTimeoutLeavesMissingPlayersAsAbstentionsWithoutCreatingVotes() {
        var mapper = new ObjectMapper();
        var service = new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var root = mapper.createObjectNode(); root.put("stage", "ROLE_VOTE");
        var team = root.putArray("teams").addObject();
        team.put("id", "t1"); team.put("roleVoteStage", "captain"); team.put("roleVoteDeadlineAt", 1_000L);
        team.putObject("roles");
        team.putObject("roleVotes").putObject("captain").put("u1", "u1");
        var players = team.putArray("players");
        players.addObject().put("id", "u1").put("name", "已投票").put("role", "front");
        players.addObject().put("id", "u2").put("name", "未投票").put("role", "front");
        players.addObject().put("id", "u3").put("name", "托管队友").put("role", "back").put("managed", true);

        assertThat(service.expireVoting(root, 1_000L)).isTrue();

        assertThat(team.at("/roles/captain").asText()).isEqualTo("u1");
        assertThat(team.at("/roleVotes/captain")).hasSize(1);
        assertThat(team.at("/roleVotes/captain/u2").isMissingNode()).isTrue();
        assertThat(team.at("/roleVotes/captain/u3").isMissingNode()).isTrue();
        assertThat(team.path("roleVoteStage").asText()).isEqualTo("strategist");
    }

    @Test
    void adminCanAssignEachCoreRoleInVotingOrder() throws Exception {
        var mapper = new ObjectMapper();
        var states = org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class);
        var events = org.mockito.Mockito.mock(LobbyEventService.class);
        var root = mapper.createObjectNode(); root.put("stage", "ROLE_VOTE");
        root.putObject("matches");
        var team = root.putArray("teams").addObject();
        team.put("id", "t1"); team.put("roleVoteStage", "captain"); team.putObject("roles");
        team.putObject("roleVotes").putObject("captain").put("u9", "u1");
        team.put("accumulationQuota", 0); team.put("accumulationRolled", 0);
        var players = team.putArray("players");
        players.addObject().put("id", "u1").put("name", "前端一").put("role", "front");
        players.addObject().put("id", "u2").put("name", "前端二").put("role", "front");
        players.addObject().put("id", "u3").put("name", "后端一").put("role", "back");
        var record = new GameStateRecord(1L, root.toString(), "system");
        org.mockito.Mockito.when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        var service = new ParallelTournamentService(states,
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                events, 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));

        service.assignCurrentRole("t1", "captain", "u1", "admin");
        var afterCaptain = mapper.readTree(record.getContent());
        assertThat(afterCaptain.at("/teams/0/roles/captain").asText()).isEqualTo("u1");
        assertThat(afterCaptain.at("/teams/0/roleVoteStage").asText()).isEqualTo("strategist");
        assertThat(afterCaptain.at("/teams/0/roleVotes/captain/u9").asText()).isEqualTo("u1");

        service.assignCurrentRole("t1", "strategist", "u2", "admin");
        service.assignCurrentRole("t1", "pitcher", "u3", "admin");
        var completed = mapper.readTree(record.getContent());
        assertThat(completed.at("/teams/0/roles/strategist").asText()).isEqualTo("u2");
        assertThat(completed.at("/teams/0/roles/pitcher").asText()).isEqualTo("u3");
        assertThat(completed.at("/teams/0/roleVoteStage").asText()).isEqualTo("complete");
        assertThat(completed.path("stage").asText()).isEqualTo("ATTACK");
        org.mockito.Mockito.verify(events, org.mockito.Mockito.times(2)).teamGameChanged("t1");
        org.mockito.Mockito.verify(events).gameChanged();
    }

    @Test
    void adminRoleAssignmentAllowsAnyIdentityButKeepsOrderAndUniquePeople() throws Exception {
        var mapper = new ObjectMapper();
        var states = org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class);
        var root = mapper.createObjectNode(); root.put("stage", "ROLE_VOTE");
        var team = root.putArray("teams").addObject();
        team.put("id", "t1"); team.put("roleVoteStage", "captain"); team.putObject("roles"); team.putObject("roleVotes");
        team.putArray("players").addObject().put("id", "u1").put("name", "后端队员一").put("role", "back");
        ((com.fasterxml.jackson.databind.node.ArrayNode) team.path("players"))
                .addObject().put("id", "u2").put("name", "后端队员二").put("role", "back");
        var record = new GameStateRecord(1L, root.toString(), "system");
        org.mockito.Mockito.when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        var service = new ParallelTournamentService(states,
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));

        service.assignCurrentRole("t1", "captain", "u1", "admin");
        assertThat(mapper.readTree(record.getContent()).at("/teams/0/roles/captain").asText()).isEqualTo("u1");
        assertThatThrownBy(() -> service.assignCurrentRole("t1", "pitcher", "u2", "admin"))
                .hasMessageContaining("当前正在选出军师");
        assertThatThrownBy(() -> service.assignCurrentRole("t1", "strategist", "u1", "admin"))
                .hasMessage("三名核心角色不能由同一人兼任");
    }

    @Test
    void missingProphetSubmissionTimesOutAsSkippedAfterThirtySeconds() {
        var mapper = new ObjectMapper();
        var service = new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var root = mapper.createObjectNode(); root.put("stage", "ATTACK");
        var match = root.putObject("matches").putObject("g1");
        match.put("status", "active"); match.put("phase", "PROPHET"); match.put("prophetDeadlineAt", 10_000L);
        match.putObject("submitted").put("prophetA", true);
        match.putObject("prophet").putArray("A").add("u1").add("u2").add("u3").add("u4").add("u5");

        assertThat(service.expireProphets(root, 9_999L)).isFalse();
        assertThat(service.expireProphets(root, 10_000L)).isTrue();

        assertThat(match.path("phase").asText()).isEqualTo("LINEUP");
        assertThat(match.at("/prophet/A")).hasSize(5);
        assertThat(match.at("/prophet/B")).isEmpty();
        assertThat(match.at("/submitted/prophetB").asBoolean()).isTrue();
        assertThat(match.at("/prophetTimedOut/B").asBoolean()).isTrue();
        assertThat(match.has("prophetDeadlineAt")).isFalse();
    }

    @Test
    void prophetSubmissionIsRejectedAfterItsDeadline() {
        var mapper = new ObjectMapper();
        var service = new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var match = mapper.createObjectNode();
        match.put("prophetDeadlineAt", System.currentTimeMillis() - 1L);

        assertThatThrownBy(() -> service.requireProphetOpen(match))
                .hasMessage("军师预言已超时，本局视为放弃预言");
    }

    @Test
    void adminCanRollAllRemainingAccumulationDiceForATeam() throws Exception {
        var mapper = new ObjectMapper();
        var states = org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class);
        var events = org.mockito.Mockito.mock(LobbyEventService.class);
        var root = mapper.createObjectNode();
        root.put("stage", "ACCUMULATION");
        var first = root.putArray("teams").addObject();
        first.put("id", "t1"); first.put("accumulationQuota", 3); first.put("accumulationRolled", 1);
        first.put("accumulationPoints", 4); first.putArray("accumulationDice").add(4);
        var second = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("teams").get(0).deepCopy();
        second.put("id", "t2"); second.put("accumulationQuota", 1); second.put("accumulationRolled", 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("teams")).add(second);
        var record = new GameStateRecord(1L, root.toString(), "system");
        org.mockito.Mockito.when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        var service = new ParallelTournamentService(states,
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                events, 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));

        var result = service.rollRemainingAccumulation("t1", "admin");
        var saved = mapper.readTree(record.getContent());

        assertThat(result.rolledCount()).isEqualTo(2);
        assertThat(saved.at("/teams/0/accumulationRolled").asInt()).isEqualTo(3);
        assertThat(saved.path("accumulationHistory")).hasSize(2);
        assertThat(saved.at("/accumulationHistory/0/playerName").asText()).isEqualTo("管理员代投");
        assertThat(saved.path("stage").asText()).isEqualTo("ATTACK");
        org.mockito.Mockito.verify(states).save(record);
        org.mockito.Mockito.verify(events).gameChanged();
    }

    @Test
    void adminCannotOverrideAnAccumulationRollWaitingToBeRevealed() {
        var mapper = new ObjectMapper();
        var states = org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class);
        var root = mapper.createObjectNode(); root.put("stage", "ACCUMULATION");
        var team = root.putArray("teams").addObject();
        team.put("id", "t1"); team.put("accumulationQuota", 2); team.put("accumulationRolled", 0);
        team.put("accumulationPoints", 0); team.putArray("accumulationDice");
        team.putObject("accumulationRolling").put("playerName", "队员");
        org.mockito.Mockito.when(states.findLockedById(1L))
                .thenReturn(Optional.of(new GameStateRecord(1L, root.toString(), "system")));
        var service = new ParallelTournamentService(states,
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));

        assertThatThrownBy(() -> service.rollRemainingAccumulation("t1", "admin"))
                .hasMessage("该队有玩家正在掷积累骰，请等待本次结果");
        org.mockito.Mockito.verify(states, org.mockito.Mockito.never()).save(org.mockito.Mockito.any());
    }

    @Test
    void accumulationCannotStartBeforeRoleVotingIsComplete() {
        var service = new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), new ObjectMapper(),
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var root = new ObjectMapper().createObjectNode();
        root.put("stage", "ROLE_VOTE");
        var player = new UserAccount("voter", "投票玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");

        assertThatThrownBy(() -> service.beginAccumulation(root, player))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("当前不在积累期");
    }

    @Test
    void accumulationRollIsRevealedAfterTheSharedRollingState() {
        var mapper = new ObjectMapper();
        var service = new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L, org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
        var root = mapper.createObjectNode();
        root.put("stage", "ACCUMULATION");
        var team = root.putArray("teams").addObject();
        team.put("id", "t1"); team.put("accumulationQuota", 1); team.put("accumulationRolled", 0);
        team.put("accumulationPoints", 0); team.putArray("accumulationDice");
        var match = root.putObject("matches").putObject("g1");
        match.put("status", "active"); match.put("phase", "PROPHET");
        var player = new UserAccount("roller", "掷骰玩家", "销售部", "USER", "hash", "salt");
        player.assignTeam("t1");
        long startedAt = System.currentTimeMillis();

        service.beginAccumulation(root, player);

        long revealAt = team.at("/accumulationRolling/revealAt").asLong();
        assertThat(revealAt).isBetween(startedAt + 1_000L, System.currentTimeMillis() + 1_500L);
        assertThat(team.path("accumulationDice")).isEmpty();
        assertThat(service.revealDueAccumulation(root, revealAt - 1)).isFalse();
        assertThat(service.revealDueAccumulation(root, revealAt)).isTrue();
        assertThat(team.path("accumulationRolling").isMissingNode()).isTrue();
        assertThat(team.path("accumulationDice")).hasSize(1);
        assertThat(root.path("stage").asText()).isEqualTo("ATTACK");
        long prophetDeadline = root.at("/matches/g1/prophetDeadlineAt").asLong();
        assertThat(prophetDeadline).isBetween(System.currentTimeMillis() + 29_000L,
                System.currentTimeMillis() + 30_500L);
    }

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
    void equalAttackUsesTheFiveFinalDiceAsSingleRoundTieBreaker() {
        var mapper = new ObjectMapper();
        var diceA = mapper.createArrayNode().add(6).add(5).add(3).add(2).add(1);
        var diceB = mapper.createArrayNode().add(6).add(4).add(4).add(2).add(1);

        assertThat(ParallelTournamentService.compareDice(diceA, diceB)).isPositive();
        assertThat(ParallelTournamentService.compareDice(diceB, diceA)).isNegative();
        assertThat(ParallelTournamentService.compareDice(diceA, diceA.deepCopy())).isZero();
    }

    @Test
    void equalFinalAttackIsDecidedByGrowthCoefficientBeforeDice() {
        var mapper = new ObjectMapper();
        var weakerDice = mapper.createArrayNode().add(1).add(1).add(1).add(1).add(1);
        var strongerDice = mapper.createArrayNode().add(6).add(6).add(6).add(6).add(6);

        assertThat(ParallelTournamentService.compareSingleRound(
                100, 100, 1.20, 1.10, weakerDice, strongerDice)).isPositive();
        assertThat(ParallelTournamentService.compareSingleRound(
                100, 100, 1.10, 1.20, strongerDice, weakerDice)).isNegative();
    }
}
