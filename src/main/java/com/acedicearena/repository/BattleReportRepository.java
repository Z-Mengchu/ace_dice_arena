package com.acedicearena.repository;

import com.acedicearena.domain.BattleReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface BattleReportRepository extends JpaRepository<BattleReport, Long> {
    List<BattleReport> findTop300ByOrderByIdDesc();
}
