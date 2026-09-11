package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.PlayerBlindBox;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.PlayerBlindBoxRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 盲盒内存运行态规格：幂等并发、事务边界（提交前不改内存）、读锁/写锁线性化与启动恢复。
 * 用桩事务管理器模拟真实提交语义：回调内写入暂存区，commit 才落入“已提交”存储，rollback 丢弃。
 */
class BlindBoxRoundServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private GameStateRepository states;
    private UserAccountRepository users;
    private PlayerBlindBoxRepository blindBoxes;
    private LobbyEventService events;
    private PlatformTransactionManager txManager;
    private BlindBoxRoundService service;

    /** 已提交的结果行（playerId → 行），模拟 player_blind_box 表。 */
    private final Map<String, PlayerBlindBox> committed = new ConcurrentHashMap<>();
    /** 事务内暂存：只有 commit 才并入 committed。 */
    private final ThreadLocal<Map<String, PlayerBlindBox>> pending = ThreadLocal.withInitial(LinkedHashMap::new);
    private final AtomicBoolean failCommit = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        states = mock(GameStateRepository.class);
        users = mock(UserAccountRepository.class);
        blindBoxes = mock(PlayerBlindBoxRepository.class);
        events = mock(LobbyEventService.class);
        txManager = mock(PlatformTransactionManager.class);
        committed.clear();
        failCommit.set(false);

        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        doAnswer(inv -> {
            if (failCommit.get()) throw new TransactionSystemException("模拟提交失败");
            committed.putAll(pending.get());
            pending.remove();
            return null;
        }).when(txManager).commit(any());
        doAnswer(inv -> {
            pending.remove();
            return null;
        }).when(txManager).rollback(any());

        doAnswer(inv -> {
            PlayerBlindBox row = inv.getArgument(0);
            pending.get().put(row.getPlayerId(), row);
            return row;
        }).when(blindBoxes).saveAndFlush(any());
        when(blindBoxes.findByGameDayAndBracketRoundAndPlayerId(anyInt(), anyInt(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(committed.get(inv.getArgument(2))));
        when(blindBoxes.findByGameDayAndBracketRound(anyInt(), anyInt()))
                .thenAnswer(inv -> List.copyOf(committed.values()));

        service = new BlindBoxRoundService(states, users, blindBoxes, mapper, events, txManager);
    }

    /* ---------- 幂等与并发 ---------- */

    @Test
    void drawsFollowTheDeclaredWeightTable() {
        int weightSum = 0;
        for (int weight : BlindBoxRoundService.BLIND_BOX_WEIGHTS) weightSum += weight;
        assertThat(weightSum).isEqualTo(100);
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int total = 20_000;
        for (int i = 0; i < total; i++) {
            int value = BlindBoxRoundService.drawBlindBox();
            counts.merge(value, 1, Integer::sum);
        }
        for (int i = 0; i < BlindBoxRoundService.BLIND_BOX_VALUES.length; i++) {
            double expected = BlindBoxRoundService.BLIND_BOX_WEIGHTS[i] / 100d;
            double ratio = counts.getOrDefault(BlindBoxRoundService.BLIND_BOX_VALUES[i], 0) / (double) total;
            assertThat(ratio).isBetween(Math.max(0d, expected - 0.02d), expected + 0.02d);
        }
        double debuff = (counts.getOrDefault(-1, 0) + counts.getOrDefault(-2, 0)) / (double) total;
        assertThat(debuff).isBetween(0.22d, 0.28d);
    }

    @Test
    void concurrentSamePlayerOpensProduceOneRowOneValueOneRevision() throws Exception {
        activate();
        UserAccount alice = player(1L, "alice", "t1");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<BlindBoxRoundService.BlindBoxResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.open("alice", 1);
            }));
        }
        ready.await();
        go.countDown();
        List<BlindBoxRoundService.BlindBoxResult> results = new ArrayList<>();
        for (Future<BlindBoxRoundService.BlindBoxResult> future : futures) results.add(future.get(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(committed).hasSize(1);
        assertThat(service.revision()).isEqualTo(1);
        int value = committed.get("u1").getBoxValue();
        long firstWins = results.stream().filter(r -> r.boxes() != null).count();
        assertThat(firstWins).isEqualTo(1);
        for (BlindBoxRoundService.BlindBoxResult result : results) {
            assertThat(result.value()).isEqualTo(value);
            if (result.boxes() == null) assertThat(result.picked()).isEqualTo(-1);
        }
    }

    @Test
    void differentPlayersOpenConcurrentlyWithoutSerializingOnStageLock() throws Exception {
        activate();
        for (long id : List.of(1L, 2L, 101L, 102L)) {
            String name = "p" + id;
            when(users.findByUsername(name)).thenReturn(Optional.of(player(id, name, id < 100 ? "t1" : "t2")));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (long id : List.of(1L, 2L, 101L, 102L)) {
            futures.add(pool.submit(() -> {
                go.await();
                return service.open("p" + id, 0);
            }));
        }
        go.countDown();
        for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(committed).hasSize(4);
        assertThat(service.revision()).isEqualTo(4);
        // 进度按 eligible 玩家计入：每人首开各 +1
        assertThat(committed.keySet()).containsExactlyInAnyOrder("u1", "u2", "u101", "u102");
    }

    /* ---------- 事务边界 ---------- */

    @Test
    void insertFailureLeavesMemoryUntouched() {
        activate();
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        doAnswer(inv -> {
            throw new RuntimeException("模拟插入失败");
        }).when(blindBoxes).saveAndFlush(any());

        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("模拟插入失败");
        assertThat(service.revision()).isZero();
        assertThat(committed).isEmpty();

        // 恢复后首开仍返回三盒（内存没有残留结果），revision 只增一次
        doAnswer(inv -> {
            PlayerBlindBox row = inv.getArgument(0);
            pending.get().put(row.getPlayerId(), row);
            return row;
        }).when(blindBoxes).saveAndFlush(any());
        BlindBoxRoundService.BlindBoxResult result = service.open("alice", 0);
        assertThat(result.boxes()).isNotNull();
        assertThat(service.revision()).isEqualTo(1);
    }

    @Test
    void commitFailureLeavesMemoryUntouched() {
        activate();
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        failCommit.set(true);

        assertThatThrownBy(() -> service.open("alice", 0)).isInstanceOf(TransactionSystemException.class);
        // 提交失败：暂存行被回滚丢弃，内存结果/计数/revision 不变
        assertThat(committed).isEmpty();
        assertThat(service.revision()).isZero();

        failCommit.set(false);
        BlindBoxRoundService.BlindBoxResult result = service.open("alice", 0);
        assertThat(result.boxes()).isNotNull();
        assertThat(service.revision()).isEqualTo(1);
    }

    @Test
    void existingDbRowReturnsIdempotentValueAndRepairsMemory() {
        activate();
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        committed.put("u1", new PlayerBlindBox(1, 1, "u1", "t1", 4));

        BlindBoxRoundService.BlindBoxResult result = service.open("alice", 0);

        assertThat(result.value()).isEqualTo(4);
        assertThat(result.boxes()).isNull();
        assertThat(result.picked()).isEqualTo(-1);
        assertThat(committed).hasSize(1);
        // 数据库有而内存缺失：修补计入一次 revision
        assertThat(service.revision()).isEqualTo(1);
        verify(blindBoxes, never()).saveAndFlush(any());
    }

    @Test
    void roleAfkAndRosterChecksKeepExistingMessages() {
        activate();
        UserAccount afk = player(1L, "afk", "t1");
        afk.setAfk(true);
        when(users.findByUsername("afk")).thenReturn(Optional.of(afk));
        UserAccount noTeam = player(9L, "noteam", null);
        when(users.findByUsername("noteam")).thenReturn(Optional.of(noTeam));
        UserAccount benched = player(50L, "benched", "t9");
        when(users.findByUsername("benched")).thenReturn(Optional.of(benched));

        assertThatThrownBy(() -> service.open("afk", 0)).hasMessage("你当前处于挂机状态，请先取消挂机再操作");
        assertThatThrownBy(() -> service.open("noteam", 0)).hasMessage("只有本轮已分组玩家可以提交比赛操作");
        assertThatThrownBy(() -> service.open("benched", 0)).hasMessage("本队本轮没有比赛");
        assertThatThrownBy(() -> service.open("benched", 9)).hasMessage("盲盒序号超出范围");
        assertThat(committed).isEmpty();
        assertThat(service.revision()).isZero();
    }

    /* ---------- 开盒与截止线性化 ---------- */

    @Test
    void advanceWaitsForInflightOpenAndMergesCommittedRows() throws Exception {
        ObjectNode root = activate();
        GameStateRecord record = new GameStateRecord(1L, root.toString(), "test");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));

        // 开盒事务停在提交前：写事务暂存后等待放行
        CountDownLatch inTransaction = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            PlayerBlindBox row = inv.getArgument(0);
            pending.get().put(row.getPlayerId(), row);
            inTransaction.countDown();
            release.await(5, TimeUnit.SECONDS);
            return row;
        }).when(blindBoxes).saveAndFlush(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<BlindBoxRoundService.BlindBoxResult> openFuture = pool.submit(() -> service.open("alice", 0));
        assertThat(inTransaction.await(5, TimeUnit.SECONDS)).as("开盒应进入写事务").isTrue();
        Future<Boolean> advanceFuture = pool.submit(() -> service.advanceIfReady(System.currentTimeMillis(), true));
        // 截止线程持写锁前必须等待已取得读锁的开盒事务提交
        Thread.sleep(200);
        assertThat(advanceFuture.isDone()).isFalse();
        release.countDown();

        assertThat(openFuture.get(5, TimeUnit.SECONDS).boxes()).isNotNull();
        assertThat(advanceFuture.get(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        ObjectNode advanced = (ObjectNode) mapper.readTree(record.getContent());
        assertThat(advanced.path("stage").asText()).isEqualTo("TACTICS");
        int value = committed.get("u1").getBoxValue();
        assertThat(advanced.at("/teams/0/players/0/blindBox").asInt()).isEqualTo(value);
        assertThat(advanced.at("/teams/0/players/0/blindBoxOpened").asBoolean()).isTrue();
        // 关闭后读锁请求看到阶段已离开盲盒并失败，数据库无新增行
        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("当前不在开盲盒阶段");
        assertThat(committed).hasSize(1);
    }

    @Test
    void advanceWithoutForceKeepsIncompleteRoundOpen() {
        ObjectNode root = blindBoxRoot(System.currentTimeMillis() + 60_000L);
        service.activateAfterCommit(root);
        // 未全员完成且未到点：不推进
        assertThat(service.advanceIfReady(System.currentTimeMillis(), false)).isFalse();
        // context 仍开放，开盒不受影响
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        assertThat(service.open("alice", 0).boxes()).isNotNull();
    }

    @Test
    void openAfterDeadlineIsRejected() throws Exception {
        ObjectNode root = blindBoxRoot(System.currentTimeMillis() + 60L);
        service.activateAfterCommit(root);
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        Thread.sleep(120L);
        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("开盲盒时间已经结束");
        assertThat(committed).isEmpty();
    }

    /* ---------- 启动恢复 ---------- */

    @Test
    void recoveryRestoresCommittedProgressAndRevision() {
        ObjectNode root = blindBoxRoot(System.currentTimeMillis() + 60_000L);
        when(states.findById(1L)).thenReturn(Optional.of(new GameStateRecord(1L, root.toString(), "test")));
        committed.put("u1", new PlayerBlindBox(1, 1, "u1", "t1", 3));
        // 非本轮名单内的历史行不计入进度
        committed.put("u999", new PlayerBlindBox(1, 1, "u999", "t1", 5));
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        when(users.findByUsername("bob")).thenReturn(Optional.of(player(2L, "bob", "t1")));

        service.ensureInitialized();

        assertThat(service.revision()).isEqualTo(1);
        BlindBoxRoundService.BlindBoxResult again = service.open("alice", 2);
        assertThat(again.value()).isEqualTo(3);
        assertThat(again.boxes()).isNull();
        assertThat(service.open("bob", 0).boxes()).isNotNull();
        assertThat(service.revision()).isEqualTo(2);
    }

    @Test
    void recoverySkipsNonBlindBoxStage() {
        ObjectNode root = blindBoxRoot(System.currentTimeMillis() + 60_000L);
        root.put("stage", "TACTICS");
        when(states.findById(1L)).thenReturn(Optional.of(new GameStateRecord(1L, root.toString(), "test")));
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));

        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("当前不在开盲盒阶段");
        assertThat(service.revision()).isZero();
    }

    @Test
    void recoveryFailureFailsClosedAndRetriesLater() {
        when(states.findById(1L)).thenThrow(new RuntimeException("数据库不可用"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));

        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("比赛状态暂不可用");

        // 恢复失败不发布开放 context；故障消除后下一次调用重试并成功
        ObjectNode root = blindBoxRoot(System.currentTimeMillis() + 60_000L);
        // 已有 thenThrow 桩，改用 doReturn 重打桩避免打桩时触发异常
        org.mockito.Mockito.doReturn(Optional.of(new GameStateRecord(1L, root.toString(), "test")))
                .when(states).findById(1L);
        assertThat(service.open("alice", 0).boxes()).isNotNull();
        assertThat(service.revision()).isEqualTo(1);
    }

    /* ---------- 换人与清理（阶段写锁 seam） ---------- */

    @Test
    void replaceDefinitionLetsNewPlayerInheritMigratedResultWithoutDoubleCounting() {
        ObjectNode root = activate();
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        service.open("alice", 0);
        assertThat(service.revision()).isEqualTo(1);

        // 沙盘中途换人：u1 被 u9 替换，数据库行已改挂到新玩家（由赛事模块在 seam 事务内完成）
        int value = committed.get("u1").getBoxValue();
        committed.remove("u1");
        committed.put("u9", new PlayerBlindBox(1, 1, "u9", "t1", value));
        ObjectNode replaced = root.deepCopy();
        ((ObjectNode) replaced.at("/teams/0/players/0")).put("id", "u9").put("name", "玩家9");
        service.replaceDefinitionAfterCommit(replaced);

        // 新玩家幂等命中继承的结果：不重新抽奖、计数与 revision 不增加
        when(users.findByUsername("newbie")).thenReturn(Optional.of(player(9L, "newbie", "t1")));
        BlindBoxRoundService.BlindBoxResult inherited = service.open("newbie", 0);
        assertThat(inherited.value()).isEqualTo(value);
        assertThat(inherited.boxes()).isNull();
        assertThat(service.revision()).isEqualTo(1);
        assertThat(committed).hasSize(1);
        // 被替换玩家已不在名单
        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("当前账号不在本队参赛名单中");
    }

    @Test
    void seamRollbackLeavesContextUntouched() {
        activate();
        java.util.concurrent.atomic.AtomicBoolean afterCommitRan = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> service.runExclusiveInTransaction(() -> {
            throw new IllegalStateException("模拟换人事务回滚");
        }, value -> afterCommitRan.set(true))).hasMessage("模拟换人事务回滚");
        assertThat(afterCommitRan).isFalse();
        // 回滚后旧 context 仍可用
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        assertThat(service.open("alice", 0).boxes()).isNotNull();
    }

    @Test
    void clearAfterCommitRejectsFurtherOpens() {
        activate();
        service.clearAfterCommit();
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("当前不在开盲盒阶段");
        assertThat(service.revision()).isZero();
    }

    @Test
    void forceDueAdvancesDeadlineAndClosesViaUnifiedPath() throws Exception {
        ObjectNode root = activate();
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        service.open("alice", 0);

        // 强制推进：数据库截止被提前后同步内存截止；此后开盒按新截止拒绝
        long past = System.currentTimeMillis() - 1;
        root.put("stageDeadlineAt", past);
        GameStateRecord record = new GameStateRecord(1L, root.toString(), "test");
        when(states.findLockedById(1L)).thenReturn(Optional.of(record));
        service.markForceDue(past);
        assertThatThrownBy(() -> service.open("alice", 0)).hasMessage("开盲盒时间已经结束");

        // 统一关闭入口：未全员完成但已强制/到点，合并已开结果并推进 TACTICS
        assertThat(service.advanceIfReady(System.currentTimeMillis(), false)).isTrue();
        ObjectNode advanced = (ObjectNode) mapper.readTree(record.getContent());
        assertThat(advanced.path("stage").asText()).isEqualTo("TACTICS");
        assertThat(advanced.at("/teams/0/players/0/blindBox").asInt())
                .isEqualTo(committed.get("u1").getBoxValue());
        assertThat(service.revision()).isZero();
    }

    /* ---------- 结果注入 ---------- */

    @Test
    void injectCommittedResultsWritesOnlyEligiblePlayers() {
        ObjectNode root = activate();
        when(users.findByUsername("alice")).thenReturn(Optional.of(player(1L, "alice", "t1")));
        service.open("alice", 0);

        ObjectNode view = root.deepCopy();
        service.injectCommittedResults(view);

        int value = committed.get("u1").getBoxValue();
        assertThat(view.at("/teams/0/players/0/blindBox").asInt()).isEqualTo(value);
        assertThat(view.at("/teams/0/players/0/blindBoxOpened").asBoolean()).isTrue();
        assertThat(view.at("/teams/0/players/1").has("blindBox")).isFalse();
        // 非盲盒阶段的状态不注入
        ObjectNode tactics = root.deepCopy().put("stage", "TACTICS");
        service.injectCommittedResults(tactics);
        assertThat(tactics.at("/teams/0/players/0").has("blindBox")).isFalse();
    }

    /* ---------- 测试基建 ---------- */

    /** 激活一轮标准盲盒 context（t1:u1-u3 对 t2:u101-u103），截止时间充裕避免后台推进干扰。 */
    private ObjectNode activate() {
        ObjectNode root = blindBoxRoot(System.currentTimeMillis() + 60_000L);
        service.activateAfterCommit(root);
        return root;
    }

    private ObjectNode blindBoxRoot(long deadlineAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("mode", "parallel");
        root.put("day", 1);
        root.put("stage", "BLIND_BOX");
        root.put("stageDeadlineAt", deadlineAt);
        ArrayNode teams = root.putArray("teams");
        addTeam(teams, "t1", 1L, 2L, 3L);
        addTeam(teams, "t2", 101L, 102L, 103L);
        ObjectNode matches = root.putObject("matches");
        ObjectNode g1 = matches.putObject("g1");
        g1.put("id", "g1");
        g1.put("a", "t1");
        g1.put("b", "t2");
        g1.put("status", "active");
        return root;
    }

    private void addTeam(ArrayNode teams, String teamId, long... playerIds) {
        ObjectNode team = teams.addObject();
        team.put("id", teamId);
        ArrayNode players = team.putArray("players");
        for (long id : playerIds) players.addObject().put("id", "u" + id).put("name", "玩家" + id);
    }

    private UserAccount player(long id, String username, String teamId) {
        UserAccount user = new UserAccount(username, "玩家" + id, "技术部", "USER", "hash", "salt");
        if (teamId != null) user.assignTeam(teamId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
