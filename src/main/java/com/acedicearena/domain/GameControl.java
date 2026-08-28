package com.acedicearena.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "game_control")
public class GameControl {
    @Id private Long id;
    @Column(nullable = false, length = 16) private String phase;
    @Column(nullable = false) private Instant updatedAt;

    protected GameControl() {}
    public GameControl(Long id) { this.id = id; this.phase = "PREPARING"; this.updatedAt = Instant.now(); }
    public String getPhase() { return phase; }
    public void changePhase(String phase) { this.phase = phase; this.updatedAt = Instant.now(); }
}
