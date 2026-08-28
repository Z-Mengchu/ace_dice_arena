package com.acedicearena.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "request_audit", indexes = @Index(name = "idx_request_time", columnList = "requested_at"))
public class RequestAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 10)
    private String method;
    @Column(nullable = false, length = 512)
    private String path;
    @Column(length = 32)
    private String username;
    @Column(length = 64)
    private String remoteAddress;
    private int statusCode;
    private long durationMs;
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    protected RequestAudit() {}

    public RequestAudit(String method, String path, String username, String remoteAddress,
                        int statusCode, long durationMs, Instant requestedAt) {
        this.method = method;
        this.path = path;
        this.username = username;
        this.remoteAddress = remoteAddress;
        this.statusCode = statusCode;
        this.durationMs = durationMs;
        this.requestedAt = requestedAt;
    }
}
