package com.acedicearena.repository;

import com.acedicearena.domain.GameStateRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface GameStateRepository extends JpaRepository<GameStateRecord, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GameStateRecord g where g.id = :id")
    Optional<GameStateRecord> findLockedById(Long id);
}
