package com.acedicearena.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 开盲盒结果按玩家独立成行：开盒请求不再写 game_state 全局行，
 * (game_day, bracket_round, player_id) 唯一键保证同一玩家同轮只开一次（幂等）。
 * 推进 TACTICS 时批量合并回 game_state，结算与脱敏视图仍只读 JSON。
 */
@Entity
@Table(name = "player_blind_box", uniqueConstraints =
        @UniqueConstraint(name = "uk_player_blind_box_round", columnNames = {"game_day", "bracket_round", "player_id"}))
public class PlayerBlindBox {
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
    private int boxValue;
    @Column(nullable = false)
    private Instant openedAt;

    protected PlayerBlindBox() {}

    public PlayerBlindBox(int gameDay, int bracketRound, String playerId, String teamId, int boxValue) {
        this.gameDay = gameDay;
        this.bracketRound = bracketRound;
        this.playerId = playerId;
        this.teamId = teamId;
        this.boxValue = boxValue;
        this.openedAt = Instant.now();
    }

    public Long getId() { return id; }
    public int getGameDay() { return gameDay; }
    public int getBracketRound() { return bracketRound; }
    public String getPlayerId() { return playerId; }
    public String getTeamId() { return teamId; }
    public int getBoxValue() { return boxValue; }
    public Instant getOpenedAt() { return openedAt; }
}
