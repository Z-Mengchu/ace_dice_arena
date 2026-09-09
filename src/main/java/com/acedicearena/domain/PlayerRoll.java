package com.acedicearena.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 真人掷骰结果按玩家独立成行：掷骰请求不再写 game_state 全局行，
 * (game_day, bracket_round, player_id) 唯一键保证同一玩家同轮只掷一次（幂等）。
 * 推进 BLIND_BOX 时批量合并回 game_state，结算与脱敏视图仍只读 JSON。
 */
@Entity
@Table(name = "player_roll", uniqueConstraints =
        @UniqueConstraint(name = "uk_player_roll_round", columnNames = {"game_day", "bracket_round", "player_id"}))
public class PlayerRoll {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private int gameDay;
    @Column(nullable = false)
    private int bracketRound;
    @Column(length = 16, nullable = false)
    private String playerId;
    @Column(length = 4, nullable = false)
    private String teamId;
    @Column(nullable = false)
    private int dice;
    @Column(nullable = false)
    private int diceFinal;
    @Column(nullable = false)
    private long rollTs;
    @Column(nullable = false)
    private boolean autoRolled;
    @Column(nullable = false)
    private Instant createdAt;

    protected PlayerRoll() {}

    public PlayerRoll(int gameDay, int bracketRound, String playerId, String teamId,
                      int dice, int diceFinal, long rollTs, boolean autoRolled) {
        this.gameDay = gameDay;
        this.bracketRound = bracketRound;
        this.playerId = playerId;
        this.teamId = teamId;
        this.dice = dice;
        this.diceFinal = diceFinal;
        this.rollTs = rollTs;
        this.autoRolled = autoRolled;
        this.createdAt = Instant.now();
    }

    /** 锁内路径（代掷/未来重掷）更新最终点数；dice 保留首次掷出值。 */
    public void updateDiceFinal(int diceFinal) { this.diceFinal = diceFinal; }

    /** 标记为系统代掷；同步暴击判定据此排除。 */
    public void markAutoRolled() { this.autoRolled = true; }

    public Long getId() { return id; }
    public int getGameDay() { return gameDay; }
    public int getBracketRound() { return bracketRound; }
    public String getPlayerId() { return playerId; }
    public String getTeamId() { return teamId; }
    public int getDice() { return dice; }
    public int getDiceFinal() { return diceFinal; }
    public long getRollTs() { return rollTs; }
    public boolean isAutoRolled() { return autoRolled; }
    public Instant getCreatedAt() { return createdAt; }
}
