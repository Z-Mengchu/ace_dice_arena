package com.acedicearena.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "game_state")
public class GameStateRecord {
    @Id
    private Long id;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(length = 32)
    private String updatedBy;

    protected GameStateRecord() {}

    public GameStateRecord(Long id, String content, String updatedBy) {
        this.id = id;
        this.content = content;
        this.version = 1;
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    public void update(String content, String updatedBy) {
        this.content = content;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
        this.version++;
    }

    public String getContent() { return content; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
