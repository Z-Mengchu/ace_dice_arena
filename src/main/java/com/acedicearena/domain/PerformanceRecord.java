package com.acedicearena.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "performance_record")
public class PerformanceRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "ranking_no")
    private Integer ranking;
    @Column(length = 64)
    private String department;
    @Column(name = "group_name", length = 64)
    private String group;
    @Column(nullable = false, length = 32)
    private String leader;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal orderCount;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal salesQuantity;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal salesAmount;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal lastWeekSalesAmount;
    @Column(nullable = false, length = 16)
    private String matchStatus;
    @Column
    private Long matchedUserId;
    @Column(nullable = false)
    private Instant importedAt;

    protected PerformanceRecord() {}

    public PerformanceRecord(Integer ranking, String department, String group, String leader,
                             BigDecimal orderCount, BigDecimal salesQuantity, BigDecimal salesAmount,
                             BigDecimal lastWeekSalesAmount, String matchStatus, Long matchedUserId) {
        this.ranking = ranking;
        this.department = department;
        this.group = group;
        this.leader = leader;
        this.orderCount = value(orderCount);
        this.salesQuantity = value(salesQuantity);
        this.salesAmount = value(salesAmount);
        this.lastWeekSalesAmount = value(lastWeekSalesAmount);
        this.matchStatus = matchStatus;
        this.matchedUserId = matchedUserId;
        this.importedAt = Instant.now();
    }

    private BigDecimal value(BigDecimal number) { return number == null ? BigDecimal.ZERO : number; }
    public String getLeader() { return leader; }
    public BigDecimal getSalesAmount() { return salesAmount; }
    public BigDecimal getLastWeekSalesAmount() { return lastWeekSalesAmount; }
    public String getMatchStatus() { return matchStatus; }
    public Long getMatchedUserId() { return matchedUserId; }
}
