package com.acedicearena.repository;

import com.acedicearena.domain.MatchReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    Optional<MatchReport> findTopByDayAndMatchIdOrderByIdDesc(int day, String matchId);
}
