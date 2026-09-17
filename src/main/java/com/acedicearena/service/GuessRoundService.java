package com.acedicearena.service;

import com.acedicearena.domain.GameStateRecord;
import com.acedicearena.domain.PlayerGuess;
import com.acedicearena.domain.UserAccount;
import com.acedicearena.repository.GameStateRepository;
import com.acedicearena.repository.PlayerGuessRepository;
import com.acedicearena.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一猜阵的玩家级写路径（独立于 game_state 行锁）。
 *
 * 与盲盒内存运行态不同，猜阵本身就是「每玩家一条记录」：提交/改投/撤回全部是
 * player_guess 表上的单行 upsert/delete，并发由 MySQL 行级机制承载；
 * game_state 在整个猜阵窗口内不变，快照缓存与 ETag 全程有效（读路径零退化）。
 * 揭晓（提前/到点/强制）由 ParallelTournamentService 在既有锁事务里一次性读表结算 6 局。
 *
 * 视图一致性：每次写入在事务提交后推进内存 revision；GameStateSnapshotStore 把它并入
 * 复合 ETag，revision 变化即重载快照并调用 injectCommittedGuesses 把猜阵注入 JSON 树，
 * 上层的 sealGuesses 密封逻辑因此无需变化。
 */
@Service
public class GuessRoundService {
    private static final Logger log = LoggerFactory.getLogger(GuessRoundService.class);
    private static final long STATE_ID = 1L;

    private final GameStateRepository gameStates;
    private final UserAccountRepository users;
    private final PlayerGuessRepository guesses;
    private final ObjectMapper mapper;
    private final LobbyEventService events;
    private final TransactionTemplate transactions;
    /** 猜阵写入修订号：单调递增，快照复合 ETag 的组成部分。 */
    private final AtomicLong revision = new AtomicLong();

    public GuessRoundService(GameStateRepository gameStates, UserAccountRepository users,
                             PlayerGuessRepository guesses, ObjectMapper mapper,
                             LobbyEventService events, PlatformTransactionManager transactionManager) {
        this.gameStates = gameStates;
        this.users = users;
        this.guesses = guesses;
        this.mapper = mapper;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public long revision() {
        return revision.get();
    }

    /**
     * 统一猜阵提交：每位队员提交「敌方在本人所在局出战的 5 人」，窗口内重复提交视为改投（覆盖）。
     * 只读校验 + 单行 upsert，不锁 game_state、不解析写回整份 JSON。
     */
    public void submit(String username, List<String> values) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        GuessContext ctx = validate(user, readState(), values);
        String targetsJson = toJsonArray(values);
        try {
            transactions.executeWithoutResult(status -> {
                upsert(ctx, targetsJson);
                ensureStillGuessing(ctx.matchId());
            });
        } catch (DataIntegrityViolationException e) {
            // 同玩家并发双击撞唯一键：退化为新事务更新（原事务的持久化上下文已不可用）
            transactions.executeWithoutResult(status -> {
                PlayerGuess existing = guesses.findByGameDayAndMatchIdAndPlayerId(
                        ctx.gameDay(), ctx.matchId(), ctx.playerId()).orElseThrow(() -> e);
                existing.retarget(ctx.round(), targetsJson);
                guesses.saveAndFlush(existing);
                ensureStillGuessing(ctx.matchId());
            });
        }
        bumpRevisionAfterCommit();
    }

    private void upsert(GuessContext ctx, String targetsJson) {
        PlayerGuess row = guesses.findByGameDayAndMatchIdAndPlayerId(
                ctx.gameDay(), ctx.matchId(), ctx.playerId()).orElse(null);
        if (row == null) {
            guesses.saveAndFlush(new PlayerGuess(ctx.gameDay(), ctx.bracketRound(), ctx.matchId(),
                    ctx.playerId(), ctx.teamId(), ctx.side(), ctx.round(), targetsJson));
        } else {
            row.retarget(ctx.round(), targetsJson);
            guesses.saveAndFlush(row);
        }
    }

    /** 撤回本人猜阵：删除本行，撤回后可重投。 */
    public void retract(String username) {
        UserAccount user = users.findByUsername(username).orElseThrow();
        GuessContext ctx = validate(user, readState(), null);
        transactions.executeWithoutResult(status -> {
            long removed = guesses.deleteByGameDayAndMatchIdAndPlayerId(ctx.gameDay(), ctx.matchId(), ctx.playerId());
            if (removed == 0) throw new IllegalStateException("你没有可撤回的猜阵");
        });
        bumpRevisionAfterCommit();
    }

    /**
     * 读取层注入：把表中的猜阵按 {round:{side:{pid:[...]}}} 形状写入比赛状态副本的
     * guesses 节点（仅统一猜阵窗口内），供密封视图与原逻辑保持一致。
     */
    public void injectCommittedGuesses(ObjectNode state) {
        if (state == null || !"BATTLE".equals(state.path("stage").asText())) return;
        int day = state.path("day").asInt(1);
        for (JsonNode matchNode : state.path("matches")) {
            ObjectNode match = (ObjectNode) matchNode;
            if (!"active".equals(match.path("status").asText())
                    || !"BATTLE".equals(match.path("phase").asText())
                    || !"GUESS".equals(match.path("roundPhase").asText())) continue;
            ObjectNode node = mapper.createObjectNode();
            for (int round = 1; round <= TournamentLayout.SQUAD_COUNT; round++) {
                ObjectNode bucket = node.putObject(String.valueOf(round));
                bucket.putObject("A");
                bucket.putObject("B");
            }
            for (PlayerGuess row : guesses.findByGameDayAndMatchId(day, match.path("id").asText())) {
                try {
                    JsonNode targets = mapper.readTree(row.getTargetsJson());
                    ObjectNode bucket = (ObjectNode) node.path(String.valueOf(row.getRoundNo())).path(row.getSide());
                    if (bucket.isObject()) bucket.set(row.getPlayerId(), targets);
                } catch (Exception e) {
                    log.warn("猜阵行解析失败，跳过 match={} player={}", row.getMatchId(), row.getPlayerId());
                }
            }
            match.set("guesses", node);
        }
    }

    /* ---------- 内部 ---------- */

    /** 提交/撤回共用的校验上下文。 */
    private record GuessContext(int gameDay, int bracketRound, String matchId, String playerId,
                                String teamId, String side, int round) {
    }

    /**
     * 只读校验（不锁 game_state）：阶段/场次/窗口/名额/目标名单。
     * values 为 null 表示撤回（跳过目标校验）。
     */
    private GuessContext validate(UserAccount user, ObjectNode root, List<String> values) {
        if (!"USER".equals(user.getRole()) || user.getTeamId() == null)
            throw new IllegalStateException("只有本轮已分组玩家可以提交比赛操作");
        if (user.isAfk()) throw new IllegalStateException("你当前处于挂机状态，请先取消挂机再操作");
        if (!"BATTLE".equals(root.path("stage").asText()))
            throw new IllegalStateException("当前不在对局阶段");
        ObjectNode match = null;
        for (JsonNode candidate : root.path("matches")) {
            if (!"active".equals(candidate.path("status").asText())) continue;
            if (user.getTeamId().equals(candidate.path("a").asText())
                    || user.getTeamId().equals(candidate.path("b").asText())) {
                match = (ObjectNode) candidate;
                break;
            }
        }
        if (match == null || !"BATTLE".equals(match.path("phase").asText())
                || !"GUESS".equals(match.path("roundPhase").asText()))
            throw new IllegalStateException("当前不接受猜阵");
        if (match.path("guessDeadlineAt").asLong(Long.MAX_VALUE) <= System.currentTimeMillis())
            throw new IllegalStateException("猜阵已截止");
        String side = user.getTeamId().equals(match.path("a").asText()) ? "A" : "B";
        ObjectNode team = TournamentLayout.findTeam(root, user.getTeamId());
        String playerId = "u" + user.getId();
        int round = TournamentLayout.squadIndexOf(team, playerId) + 1;
        if (values != null) {
            if (values.size() != TournamentLayout.SQUAD_SIZE || new HashSet<>(values).size() != values.size())
                throw new IllegalArgumentException("猜阵必须选择 5 名敌方队员");
            ObjectNode enemy = TournamentLayout.findTeam(root, "A".equals(side) ? match.path("b").asText() : match.path("a").asText());
            Set<String> enemyRoster = new HashSet<>();
            enemy.path("players").forEach(player -> enemyRoster.add(player.path("id").asText()));
            if (!enemyRoster.containsAll(values)) throw new IllegalArgumentException("猜阵目标必须是敌方队员");
        }
        return new GuessContext(root.path("day").asInt(1), TournamentLayout.bracketRoundOf(match.path("id").asText()),
                match.path("id").asText(), playerId, user.getTeamId(), side, round);
    }

    /**
     * 保存后复查（仍在事务内，失败即回滚本次写入）：提交前的校验读的是未加锁的旧状态，
     * 若揭晓事务在此期间已提交，这里重读到的场次已离开猜阵窗口，必须拒绝，
     * 避免按时提交的行在结算完成后才落库成为游离行。
     */
    private void ensureStillGuessing(String matchId) {
        for (JsonNode candidate : readState().path("matches")) {
            if (!matchId.equals(candidate.path("id").asText())) continue;
            if ("BATTLE".equals(candidate.path("phase").asText())
                    && "GUESS".equals(candidate.path("roundPhase").asText())) return;
            break;
        }
        throw new IllegalStateException("猜阵已截止");
    }

    /** 提交事务已成功返回（失败会抛异常走不到这里）：推进修订号并触发合并广播。 */
    private void bumpRevisionAfterCommit() {
        revision.incrementAndGet();
        events.gameChanged();
    }

    private ObjectNode readState() {
        GameStateRecord record = gameStates.findById(STATE_ID)
                .orElseThrow(() -> new IllegalStateException("主持人尚未创建比赛"));
        try {
            return (ObjectNode) mapper.readTree(record.getContent());
        } catch (Exception e) {
            throw new IllegalStateException("比赛状态无法读取");
        }
    }

    private String toJsonArray(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array.toString();
    }
}
