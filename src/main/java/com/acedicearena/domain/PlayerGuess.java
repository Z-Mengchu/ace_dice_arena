package com.acedicearena.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 统一猜阵按玩家独立成行：提交/改投是同一 (game_day, match_id, player_id) 键的 upsert，
 * 并发由 MySQL 行级机制承载，不碰 game_state 行锁；唯一键是数据完整性的最后防线。
 * 揭晓时一次性读表结算 6 局写回 game_state，之后行在下一轮 ROLL 开始前定向清理。
 */
@Entity
@Table(name = "player_guess", uniqueConstraints =
        @UniqueConstraint(name = "uk_player_guess_match_player",
                columnNames = {"game_day", "match_id", "player_id"}))
public class PlayerGuess {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private int gameDay;
    @Column(nullable = false)
    private int bracketRound;
    @Column(length = 8, nullable = false)
    private String matchId;
    @Column(length = 16, nullable = false)
    private String playerId;
    @Column(length = 4, nullable = false)
    private String teamId;
    @Column(length = 1, nullable = false)
    private String side;
    @Column(nullable = false)
    private int roundNo;
    @Column(length = 128, nullable = false)
    private String targetsJson;
    @Column(nullable = false)
    private Instant updatedAt;

    protected PlayerGuess() {}

    public PlayerGuess(int gameDay, int bracketRound, String matchId, String playerId,
                       String teamId, String side, int roundNo, String targetsJson) {
        this.gameDay = gameDay;
        this.bracketRound = bracketRound;
        this.matchId = matchId;
        this.playerId = playerId;
        this.teamId = teamId;
        this.side = side;
        this.roundNo = roundNo;
        this.targetsJson = targetsJson;
        this.updatedAt = Instant.now();
    }

    public void retarget(int roundNo, String targetsJson) {
        this.roundNo = roundNo;
        this.targetsJson = targetsJson;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public int getGameDay() { return gameDay; }
    public int getBracketRound() { return bracketRound; }
    public String getMatchId() { return matchId; }
    public String getPlayerId() { return playerId; }
    public String getTeamId() { return teamId; }
    public String getSide() { return side; }
    public int getRoundNo() { return roundNo; }
    public String getTargetsJson() { return targetsJson; }
    public Instant getUpdatedAt() { return updatedAt; }
}
