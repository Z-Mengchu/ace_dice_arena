package com.acedicearena.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "user_account", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class UserAccount {
    private static final String EXTERNAL_DIRECTORY_PASSWORD_MARKER = "external-directory";
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32)
    private String username;
    @Column(nullable = false, length = 32)
    private String displayName;
    @Column(nullable = false, length = 64)
    private String department;
    @Column(nullable = false, length = 16)
    private String role;
    @Column(length = 4)
    private String teamId;
    @Column(nullable = false)
    private boolean ready;
    @Column(nullable = false)
    private boolean afk;
    @Column(nullable = false)
    private boolean frontEnd;
    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal gmv = BigDecimal.ZERO;
    @Column(nullable = false, length = 64)
    private String passwordHash;
    @Column(nullable = false, length = 32)
    private String salt;
    @Column(nullable = false)
    private Instant createdAt;

    protected UserAccount() {}

    public UserAccount(String username, String displayName, String department, String role, String passwordHash, String salt) {
        this.username = username;
        this.displayName = displayName;
        this.department = department;
        this.role = role;
        this.passwordHash = passwordHash;
        this.salt = salt;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getDepartment() { return department; }
    public String getRole() { return role; }
    public String getTeamId() { return teamId; }
    public boolean isReady() { return ready; }
    public boolean isAfk() { return afk; }
    public boolean isFrontEnd() { return frontEnd; }
    public BigDecimal getGmv() { return gmv == null ? BigDecimal.ZERO : gmv; }
    public String getPasswordHash() { return passwordHash; }
    public String getSalt() { return salt; }
    public void assignTeam(String teamId) { this.teamId = teamId; this.ready = false; this.afk = false; }
    public void setReady(boolean ready) { this.ready = ready; }
    public void setAfk(boolean afk) { this.afk = afk; }
    public void setPerformance(boolean frontEnd, BigDecimal gmv) {
        this.frontEnd = frontEnd;
        this.gmv = gmv == null ? BigDecimal.ZERO : gmv;
    }
    public void syncProfile(String displayName, String department) {
        this.displayName = displayName;
        this.department = department;
    }
    public boolean syncExternalDirectoryProfile(String displayName, String department) {
        boolean changed = !displayName.equals(this.displayName) || !department.equals(this.department)
                || !"USER".equals(role) || !EXTERNAL_DIRECTORY_PASSWORD_MARKER.equals(passwordHash);
        this.displayName = displayName;
        this.department = department;
        this.role = "USER";
        this.passwordHash = EXTERNAL_DIRECTORY_PASSWORD_MARKER;
        return changed;
    }
    public boolean isExternalDirectoryManaged() {
        return EXTERNAL_DIRECTORY_PASSWORD_MARKER.equals(passwordHash);
    }
    public boolean deactivateExternalDirectoryAccount() {
        if (!isExternalDirectoryManaged() || "INACTIVE".equals(role)) return false;
        this.role = "INACTIVE";
        this.teamId = null;
        this.ready = false;
        this.afk = false;
        return true;
    }
}
