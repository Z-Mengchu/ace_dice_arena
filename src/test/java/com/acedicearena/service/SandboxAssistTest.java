package com.acedicearena.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 沙盘助攻只在必要时介入，且介入后骰子与攻击力必须依然自洽。 */
class SandboxAssistTest {

    @Test
    void assistOnlyAppliesWhenExactlyOneSideHasARealPlayer() {
        assertThat(ParallelTournamentService.forcedSandboxSide(true, false)).isEqualTo("A");
        assertThat(ParallelTournamentService.forcedSandboxSide(false, true)).isEqualTo("B");
        // 两边都是真人：让他们真打
        assertThat(ParallelTournamentService.forcedSandboxSide(true, true)).isNull();
        // 两边都是虚拟队员：不能再默认判给 A，否则整个签表被固定成编号靠前的队伍晋级
        assertThat(ParallelTournamentService.forcedSandboxSide(false, false)).isNull();
    }

    @Test
    void assistedRollKeepsDiceConsistentWithTheScore() {
        ObjectMapper mapper = new ObjectMapper();
        ParallelTournamentService service = service(mapper);
        ObjectNode match = mapper.createObjectNode();
        ObjectNode rolls = match.putObject("rolls");
        ObjectNode winner = rolls.putObject("A");
        winner.putArray("dice").add(1).add(1).add(2).add(1).add(1); // 弱手：和 6
        winner.put("syncOk", false); winner.put("attack", 6.0);
        rolls.putObject("B").put("attack", 24.0).putArray("dice").add(6).add(6).add(4).add(4).add(4);

        service.assistSandboxSide(match, "A");

        assertThat(winner.path("sandboxAssisted").asBoolean()).isTrue();
        assertThat(winner.path("attack").asDouble()).isGreaterThanOrEqualTo(34.0); // 输家 24 + 10
        assertThat(attackFromDice(winner)).isEqualTo(winner.path("attack").asDouble());
    }

    @Test
    void assistLeavesAnAlreadyWinningRollUntouched() {
        ObjectMapper mapper = new ObjectMapper();
        ParallelTournamentService service = service(mapper);
        ObjectNode match = mapper.createObjectNode();
        ObjectNode rolls = match.putObject("rolls");
        ObjectNode winner = rolls.putObject("A");
        winner.putArray("dice").add(6).add(6).add(6).add(5).add(4);
        winner.put("syncOk", true); winner.put("attack", 40.5);
        rolls.putObject("B").put("attack", 12.0).putArray("dice").add(3).add(3).add(2).add(2).add(2);

        service.assistSandboxSide(match, "A");

        assertThat(winner.has("sandboxAssisted")).isFalse();
        assertThat(winner.path("attack").asDouble()).isEqualTo(40.5);
    }

    @Test
    void generatedDiceAreAlwaysFiveLegalFaces() {
        ParallelTournamentService service = service(new ObjectMapper());
        for (int target = -5; target <= 40; target++) {
            List<Integer> dice = service.diceWithSum(target);
            assertThat(dice).hasSize(5).allMatch(die -> die >= 1 && die <= 6);
            int sum = dice.stream().mapToInt(Integer::intValue).sum();
            assertThat(sum).isEqualTo(Math.min(30, Math.max(5, target)));
        }
    }

    /** 用与结算完全相同的公式，从骰子反推攻击力。 */
    private double attackFromDice(ObjectNode roll) {
        int sum = 0;
        for (var die : roll.path("dice")) sum += die.asInt();
        boolean leopard = roll.path("dice").size() == 5
                && roll.path("dice").findValues("").isEmpty()
                && allSame(roll);
        double attack = sum * (roll.path("syncOk").asBoolean() ? 1.5 : 1) * (leopard ? 3 : 1);
        return Math.round(attack * 100d) / 100d;
    }

    private boolean allSame(ObjectNode roll) {
        int first = roll.path("dice").get(0).asInt();
        for (var die : roll.path("dice")) if (die.asInt() != first) return false;
        return true;
    }

    private ParallelTournamentService service(ObjectMapper mapper) {
        return new ParallelTournamentService(
                org.mockito.Mockito.mock(com.acedicearena.repository.GameStateRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.UserAccountRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.PerformanceRecordRepository.class),
                org.mockito.Mockito.mock(com.acedicearena.repository.GameControlRepository.class), mapper,
                org.mockito.Mockito.mock(LobbyEventService.class), 6_000L,
                org.mockito.Mockito.mock(com.acedicearena.repository.BattleReportRepository.class), org.mockito.Mockito.mock(com.acedicearena.service.OnlineGameService.class));
    }
}
