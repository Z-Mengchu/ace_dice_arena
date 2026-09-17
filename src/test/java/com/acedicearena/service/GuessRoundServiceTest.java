package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.PlayerGuess;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.PlayerGuessRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一猜阵的玩家级写路径：提交/改投/撤回直写 player_guess 单行，揭晓由 PTS 读表结算。 */
class GuessRoundServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GameStateRepository states = mock(GameStateRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PlayerGuessRepository guesses = mock(PlayerGuessRepository.class);
    private final LobbyEventService events = mock(LobbyEventService.class);
    /** 内存表模拟 player_guess：key = gameDay|matchId|playerId。 */
    private final Map<String, PlayerGuess> store = new HashMap<>();

    @BeforeEach
    void wireInMemoryTable() {
        when(guesses.findByGameDayAndMatchIdAndPlayerId(anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(key(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2)))));
        when(guesses.findByGameDayAndMatchId(anyInt(), anyString()))
                .thenAnswer(inv -> store.values().stream()
                        .filter(row -> row.getGameDay() == (int) inv.getArgument(0)
                                && row.getMatchId().equals(inv.getArgument(1)))
                        .toList());
        when(guesses.saveAndFlush(any(PlayerGuess.class))).thenAnswer(inv -> {
            PlayerGuess row = inv.getArgument(0);
            store.put(key(row.getGameDay(), row.getMatchId(), row.getPlayerId()), row);
            return row;
        });
        when(guesses.deleteByGameDayAndMatchIdAndPlayerId(anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> store.remove(key(inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2))) != null ? 1L : 0L);
    }

    private static String key(int gameDay, String matchId, String playerId) {
        return gameDay + "|" + matchId + "|" + playerId;
    }

    /* ---------- 提交与改投 ---------- */

    @Test
    void submitUpsertsANewRowKeyedByMatchAndPlayer() {
        GuessRoundService service = serviceOn(battleRoot());
        when(users.findByUsername("user11")).thenReturn(Optional.of(user(11L, "t1")));
        List<String> targets = List.of("u101", "u102", "u103", "u104", "u105");

        service.submit("user11", targets);

        assertThat(store).hasSize(1);
        PlayerGuess row = store.get(key(1, "g1", "u11"));
        assertThat(row.getBracketRound()).isEqualTo(1);
        assertThat(row.getTeamId()).isEqualTo("t1");
        assertThat(row.getSide()).isEqualTo("A");
        // u11 在 t1 的 3 号小队（u11~u15）：猜阵写入第 3 局
        assertThat(row.getRoundNo()).isEqualTo(3);
        assertThat(row.getTargetsJson()).isEqualTo("[\"u101\",\"u102\",\"u103\",\"u104\",\"u105\"]");
        assertThat(row.getUpdatedAt()).isNotNull();
        // 提交推进修订号并触发合并广播（快照按 revision 失效重载注入）
        assertThat(service.revision()).isEqualTo(1L);
        verify(events).gameChanged();
    }

    @Test
    void resubmissionInsideTheWindowRetargetsTheSameRow() {
        GuessRoundService service = serviceOn(battleRoot());
        when(users.findByUsername("user11")).thenReturn(Optional.of(user(11L, "t1")));

        service.submit("user11", List.of("u101", "u102", "u103", "u104", "u105"));
        service.submit("user11", List.of("u106", "u107", "u108", "u109", "u110"));

        assertThat(store).hasSize(1);
        PlayerGuess row = store.get(key(1, "g1", "u11"));
        assertThat(row.getTargetsJson()).isEqualTo("[\"u106\",\"u107\",\"u108\",\"u109\",\"u110\"]");
        assertThat(row.getRoundNo()).isEqualTo(3);
        assertThat(service.revision()).isEqualTo(2L);
        verify(events, times(2)).gameChanged();
    }

    @Test
    void sideAndRoundFollowThePlayersOwnSquad() {
        GuessRoundService service = serviceOn(battleRoot());
        // u101 在 t2 的 1 号小队，t2 是 g1 的 B 方
        when(users.findByUsername("user101")).thenReturn(Optional.of(user(101L, "t2")));

        service.submit("user101", List.of("u1", "u2", "u3", "u4", "u5"));

        PlayerGuess row = store.get(key(1, "g1", "u101"));
        assertThat(row.getSide()).isEqualTo("B");
        assertThat(row.getRoundNo()).isEqualTo(1);
    }

    /* ---------- 校验 ---------- */

    @Test
    void submitRejectsNonGroupedOrNonPlayerAccounts() {
        ObjectNode root = battleRoot();
        GuessRoundService service = serviceOn(root);
        UserAccount ungrouped = user(1L, null);
        when(users.findByUsername("user1")).thenReturn(Optional.of(ungrouped));

        assertThatThrownBy(() -> service.submit("user1", List.of("u101", "u102", "u103", "u104", "u105")))
                .hasMessage("只有本轮已分组玩家可以提交比赛操作");
        assertThat(store).isEmpty();
    }

    @Test
    void submitRejectsAfkPlayers() {
        GuessRoundService service = serviceOn(battleRoot());
        UserAccount afk = user(1L, "t1");
        afk.setAfk(true);
        when(users.findByUsername("user1")).thenReturn(Optional.of(afk));

        assertThatThrownBy(() -> service.submit("user1", List.of("u101", "u102", "u103", "u104", "u105")))
                .hasMessage("你当前处于挂机状态，请先取消挂机再操作");
    }

    @Test
    void submitRejectsOutsideTheBattleStage() {
        ObjectNode root = battleRoot();
        root.put("stage", "ROLL");
        GuessRoundService service = serviceOn(root);
        when(users.findByUsername("user1")).thenReturn(Optional.of(user(1L, "t1")));

        assertThatThrownBy(() -> service.submit("user1", List.of("u101", "u102", "u103", "u104", "u105")))
                .hasMessage("当前不在对局阶段");
    }

    @Test
    void submitRejectsWhenTheMatchIsNotInTheGuessWindow() {
        ObjectNode root = battleRoot();
        ((ObjectNode) root.path("matches").path("g1")).put("roundPhase", "REVEAL");
        GuessRoundService service = serviceOn(root);
        when(users.findByUsername("user1")).thenReturn(Optional.of(user(1L, "t1")));

        assertThatThrownBy(() -> service.submit("user1", List.of("u101", "u102", "u103", "u104", "u105")))
                .hasMessage("当前不接受猜阵");
    }

    @Test
    void submitRejectsAfterTheGuessDeadline() {
        ObjectNode root = battleRoot();
        ((ObjectNode) root.path("matches").path("g1"))
                .put("guessDeadlineAt", System.currentTimeMillis() - 1L);
        GuessRoundService service = serviceOn(root);
        when(users.findByUsername("user1")).thenReturn(Optional.of(user(1L, "t1")));

        assertThatThrownBy(() -> service.submit("user1", List.of("u101", "u102", "u103", "u104", "u105")))
                .hasMessage("猜阵已截止");
    }

    @Test
    void submitRejectsWrongCountsDuplicatesAndNonEnemyTargets() {
        GuessRoundService service = serviceOn(battleRoot());
        when(users.findByUsername("user1")).thenReturn(Optional.of(user(1L, "t1")));

        assertThatThrownBy(() -> service.submit("user1", List.of("u101", "u102", "u103", "u104")))
                .hasMessage("猜阵必须选择 5 名敌方队员");
        assertThatThrownBy(() -> service.submit("user1", List.of("u101", "u101", "u103", "u104", "u105")))
                .hasMessage("猜阵必须选择 5 名敌方队员");
        assertThatThrownBy(() -> service.submit("user1", List.of("u1", "u2", "u3", "u4", "u5")))
                .hasMessage("猜阵目标必须是敌方队员");
        assertThat(store).isEmpty();
    }

    /* ---------- 并发与竞态 ---------- */

    @Test
    void doubleClickUniqueKeyRaceFallsBackToUpdatingTheCommittedRow() {
        GuessRoundService service = serviceOn(battleRoot());
        when(users.findByUsername("user11")).thenReturn(Optional.of(user(11L, "t1")));
        // 并发双击：双方都查到空，对方先插入提交，本方 insert 撞唯一键
        PlayerGuess committed = new PlayerGuess(1, 1, "g1", "u11", "t1", "A", 3, "[\"u106\"]");
        ReflectionTestUtils.setField(committed, "id", 42L);
        when(guesses.saveAndFlush(any(PlayerGuess.class))).thenAnswer(inv -> {
            PlayerGuess row = inv.getArgument(0);
            if (row.getId() == null) {
                // 模拟对方事务先提交同一唯一键后本方 insert 冲突
                store.putIfAbsent(key(row.getGameDay(), row.getMatchId(), row.getPlayerId()), committed);
                throw new DataIntegrityViolationException("uk_player_guess_match_player");
            }
            store.put(key(row.getGameDay(), row.getMatchId(), row.getPlayerId()), row);
            return row;
        });

        List<String> targets = List.of("u101", "u102", "u103", "u104", "u105");
        service.submit("user11", targets);

        // 冲突退化为更新已提交行：内容以本次提交为准，流程正常返回
        assertThat(store).hasSize(1);
        assertThat(store.get(key(1, "g1", "u11")).getTargetsJson())
                .isEqualTo("[\"u101\",\"u102\",\"u103\",\"u104\",\"u105\"]");
        assertThat(service.revision()).isEqualTo(1L);
        verify(events).gameChanged();
    }

    @Test
    void uniqueKeyRaceWithoutAFindableRowRethrowsTheOriginalViolation() {
        GuessRoundService service = serviceOn(battleRoot());
        when(users.findByUsername("user11")).thenReturn(Optional.of(user(11L, "t1")));
        // check-then-act 竞态变体：insert 撞键，但回退事务里仍查不到对方行（对方随后回滚）
        when(guesses.saveAndFlush(any(PlayerGuess.class)))
                .thenThrow(new DataIntegrityViolationException("uk_player_guess_match_player"));

        assertThatThrownBy(() -> service.submit("user11", List.of("u101", "u102", "u103", "u104", "u105")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store).isEmpty();
        assertThat(service.revision()).isZero();
    }

    @Test
    void submitRollsBackWhenRevealCommittedBeforeTheSaveFinishes() {
        ObjectNode guessRoot = battleRoot();
        ObjectNode revealedRoot = battleRoot();
        ((ObjectNode) revealedRoot.path("matches").path("g1")).put("roundPhase", "REVEAL");
        // 校验时读到 GUESS；事务内保存后复查时揭晓事务已提交
        when(states.findById(1L)).thenReturn(
                Optional.of(new GameStateRecord(1L, guessRoot.toString(), "test")),
                Optional.of(new GameStateRecord(1L, revealedRoot.toString(), "test")));
        GuessRoundService service = new GuessRoundService(states, users, guesses, mapper, events, stubTransactions());
        when(users.findByUsername("user11")).thenReturn(Optional.of(user(11L, "t1")));

        assertThatThrownBy(() -> service.submit("user11", List.of("u101", "u102", "u103", "u104", "u105")))
                .hasMessage("猜阵已截止");
        assertThat(service.revision()).isZero();
    }

    @Test
    void submitFallsBackToFirstRoundWhenPlayerIsNotFoundInSquads() {
        ObjectNode root = battleRoot();
        // 手工构造状态：t1 没有 squads 节点，所在小队按 0 号兜底（猜阵写入第 1 局）
        ((ObjectNode) root.path("teams").get(0)).remove("squads");
        GuessRoundService service = serviceOn(root);
        when(users.findByUsername("user11")).thenReturn(Optional.of(user(11L, "t1")));

        service.submit("user11", List.of("u101", "u102", "u103", "u104", "u105"));

        assertThat(store.get(key(1, "g1", "u11")).getRoundNo()).isEqualTo(1);
    }

    /* ---------- 撤回 ---------- */

    @Test
    void retractDeletesTheOwnRowAndAllowsResubmission() {
        GuessRoundService service = serviceOn(battleRoot());
        when(users.findByUsername("user11")).thenReturn(Optional.of(user(11L, "t1")));
        List<String> targets = List.of("u101", "u102", "u103", "u104", "u105");
        service.submit("user11", targets);
        assertThat(store).hasSize(1);

        service.retract("user11");
        assertThat(store).isEmpty();
        assertThat(service.revision()).isEqualTo(2L);

        assertThatThrownBy(() -> service.retract("user11"))
                .hasMessage("你没有可撤回的猜阵");
        // 撤回后可重投
        service.submit("user11", targets);
        assertThat(store).hasSize(1);
    }

    /* ---------- 读取层注入 ---------- */

    @Test
    void injectCommittedGuessesBucketsRowsByRoundAndSide() {
        ObjectNode root = battleRoot();
        GuessRoundService service = serviceOn(root);
        store.put(key(1, "g1", "u11"),
                new PlayerGuess(1, 1, "g1", "u11", "t1", "A", 3, "[\"u101\",\"u102\"]"));
        store.put(key(1, "g1", "u101"),
                new PlayerGuess(1, 1, "g1", "u101", "t2", "B", 1, "[\"u1\"]"));

        service.injectCommittedGuesses(root);

        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        assertThat(match.at("/guesses/3/A/u11")).hasSize(2);
        assertThat(match.at("/guesses/3/A/u11").get(0).asText()).isEqualTo("u101");
        assertThat(match.at("/guesses/1/B/u101").get(0).asText()).isEqualTo("u1");
        // 6 局分桶齐全，空桶也在（密封视图按桶产出提交状态）
        for (int round = 1; round <= 6; round++) {
            assertThat(match.at("/guesses/" + round + "/A").isObject()).isTrue();
            assertThat(match.at("/guesses/" + round + "/B").isObject()).isTrue();
        }
    }

    @Test
    void injectCommittedGuessesSkipsNonBattleStatesAndNonGuessWindows() {
        GuessRoundService service = serviceOn(battleRoot());
        store.put(key(1, "g1", "u11"),
                new PlayerGuess(1, 1, "g1", "u11", "t1", "A", 3, "[\"u101\"]"));

        ObjectNode rollStage = battleRoot();
        rollStage.put("stage", "ROLL");
        service.injectCommittedGuesses(rollStage);
        assertThat(((ObjectNode) rollStage.path("matches").path("g1")).has("guesses")).isFalse();

        ObjectNode revealed = battleRoot();
        ((ObjectNode) revealed.path("matches").path("g1")).put("roundPhase", "REVEAL");
        service.injectCommittedGuesses(revealed);
        assertThat(((ObjectNode) revealed.path("matches").path("g1")).has("guesses")).isFalse();
    }

    @Test
    void injectCommittedGuessesSkipsRowsWithMalformedTargetsJson() {
        ObjectNode root = battleRoot();
        GuessRoundService service = serviceOn(root);
        store.put(key(1, "g1", "u11"),
                new PlayerGuess(1, 1, "g1", "u11", "t1", "A", 3, "not-json"));
        store.put(key(1, "g1", "u12"),
                new PlayerGuess(1, 1, "g1", "u12", "t1", "A", 3, "[\"u101\"]"));

        service.injectCommittedGuesses(root);

        ObjectNode match = (ObjectNode) root.path("matches").path("g1");
        // 坏行跳过（视为未提交），好行正常注入
        assertThat(match.at("/guesses/3/A/u11").isMissingNode()).isTrue();
        assertThat(match.at("/guesses/3/A/u12").get(0).asText()).isEqualTo("u101");
    }

    /* ---------- 构造 ---------- */

    /** 桩事务管理器：同步执行回调并视为已提交。 */
    private static org.springframework.transaction.PlatformTransactionManager stubTransactions() {
        var txManager = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(txManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> new org.springframework.transaction.support.SimpleTransactionStatus());
        return txManager;
    }

    /** findById(1L) 返回装有 root 的记录后构造服务（猜阵写路径只读 game_state）。 */
    private GuessRoundService serviceOn(ObjectNode root) {
        when(states.findById(1L)).thenReturn(Optional.of(new GameStateRecord(1L, root.toString(), "test")));
        return new GuessRoundService(states, users, guesses, mapper, events, stubTransactions());
    }

    private UserAccount user(long id, String teamId) {
        UserAccount user = new UserAccount("user" + id, "玩家" + id, "销售部", "USER", "hash", "salt");
        ReflectionTestUtils.setField(user, "id", id);
        if (teamId != null) user.assignTeam(teamId);
        return user;
    }

    /** BATTLE 阶段统一猜阵窗口：t1(u1..u30)/t2(u101..u130) 已分队，g1 进行中，截止在未来。 */
    private ObjectNode battleRoot() {
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel"); root.put("stage", "BATTLE"); root.put("day", 1);
        root.putArray("teams").add(team("t1", 1L)).add(team("t2", 101L));
        ObjectNode match = root.putObject("matches").putObject("g1");
        match.put("id", "g1"); match.put("a", "t1"); match.put("b", "t2");
        match.put("winsA", 0); match.put("winsB", 0);
        match.put("status", "active"); match.put("phase", "BATTLE"); match.put("roundPhase", "GUESS");
        match.put("guessOpenedAt", System.currentTimeMillis());
        match.put("guessDeadlineAt", System.currentTimeMillis() + 60_000L);
        match.putArray("rounds");
        return root;
    }

    private ObjectNode team(String teamId, long firstId) {
        ObjectNode team = mapper.createObjectNode();
        team.put("id", teamId); team.put("name", teamId);
        ArrayNode players = team.putArray("players");
        for (int i = 0; i < 30; i++)
            players.addObject().put("id", "u" + (firstId + i)).put("name", teamId + "队员" + (i + 1));
        ArrayNode squads = team.putArray("squads");
        for (int s = 0; s < 6; s++) {
            ArrayNode squad = squads.addArray();
            for (int i = s * 5; i < s * 5 + 5; i++) squad.add("u" + (firstId + i));
        }
        return team;
    }
}
