package com.acedicearena.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

/**
 * 状态行。{@code @DynamicUpdate} 是性能要求而非可选优化：
 * 开盲盒/掷骰等路径只通过 {@link #touch} 推进版本，若不生成动态 UPDATE，
 * Hibernate 会把 55KB+ 的 {@code content} 一并写回（实测 0.53ms/次），
 * 与 {@link #touch} 注释声明的「不重写正文」相矛盾。
 *
 * <p>省下的是回写的字节数（含 binlog/redo 量），不是锁：{@code version}/{@code updatedAt}
 * 每次 {@link #touch} 必然变脏，UPDATE 语句照发、{@code findLockedById} 持有的行锁照旧。
 * 附带收益是读改写不再用陈旧 {@code content} 覆盖正文（只写自己改动的列）。
 *
 * <p>注意动态 UPDATE 只对同一持久化上下文中加载或 merge 的实体生效，普通单测（仓储为 mock）
 * 覆盖不到——改动这里需要集成测试兜底，否则删掉本注解不会有测试失败。
 */
@Entity
@DynamicUpdate
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
        touch(updatedBy);
    }

    /** 玩家可见的关联数据变化时，只推进状态版本，不重写正文。 */
    public void touch(String updatedBy) {
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
        this.version++;
    }

    public String getContent() { return content; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
