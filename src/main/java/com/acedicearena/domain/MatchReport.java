package com.acedicearena.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 完赛场次的逐局战力明细归档：GameState 行内只保留 {round, winner} 摘要，
 * 详情接口按 (day, matchId) 从这里读取结算时刻的完整对局节点。
 */
@Entity
@Table(name = "match_report")
public class MatchReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "game_day", nullable = false)
    private int day;
    @Column(name = "match_id", length = 8, nullable = false)
    private String matchId;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;
    @Column(nullable = false)
    private Instant createdAt;

    protected MatchReport() {}

    public MatchReport(int day, String matchId, String content) {
        this.day = day;
        this.matchId = matchId;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public int getDay() { return day; }
    public String getMatchId() { return matchId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
