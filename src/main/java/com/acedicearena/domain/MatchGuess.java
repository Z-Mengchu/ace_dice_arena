package com.acedicearena.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 猜阵按 (场次, 类型, 轮次, 方, 玩家) 独立成行：提交不再写 game_state 全局行，
 * 唯一键保证同一玩家同轮只有一份猜阵（round 幂等；pre 改投 = 同键覆盖 targets）。
 * 揭晓(reveal)/开窗(mergePreGuesses)时批量合并回 game_state，结算与密封视图仍只读 JSON。
 */
@Entity
@Table(name = "match_guess", uniqueConstraints =
        @UniqueConstraint(name = "uk_match_guess", columnNames =
                {"game_day", "match_id", "guess_type", "round_no", "side", "player_id"}))
public class MatchGuess {
    /** 本轮猜阵（当前出战轮）。 */
    public static final String TYPE_ROUND = "round";
    /** 提前猜阵（本人小队后续出战轮）。 */
    public static final String TYPE_PRE = "pre";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private int gameDay;
    @Column(length = 8, nullable = false)
    private String matchId;
    @Column(length = 8, nullable = false)
    private String guessType;
    @Column(nullable = false)
    private int roundNo;
    @Column(length = 1, nullable = false)
    private String side;
    @Column(length = 16, nullable = false)
    private String playerId;
    /** JSON 数组字符串：5 个敌方玩家 id。 */
    @Column(length = 128, nullable = false)
    private String targets;
    @Column(nullable = false)
    private Instant createdAt;

    protected MatchGuess() {}

    public MatchGuess(int gameDay, String matchId, String guessType, int roundNo,
                      String side, String playerId, String targets) {
        this.gameDay = gameDay;
        this.matchId = matchId;
        this.guessType = guessType;
        this.roundNo = roundNo;
        this.side = side;
        this.playerId = playerId;
        this.targets = targets;
        this.createdAt = Instant.now();
    }

    /** 提前猜阵改投：同键覆盖目标名单。 */
    public void updateTargets(String targets) { this.targets = targets; }

    public Long getId() { return id; }
    public int getGameDay() { return gameDay; }
    public String getMatchId() { return matchId; }
    public String getGuessType() { return guessType; }
    public int getRoundNo() { return roundNo; }
    public String getSide() { return side; }
    public String getPlayerId() { return playerId; }
    public String getTargets() { return targets; }
    public Instant getCreatedAt() { return createdAt; }
}
