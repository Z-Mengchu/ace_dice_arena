package com.acedicearena.repository;

import com.acedicearena.domain.PerformanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceRecordRepository extends JpaRepository<PerformanceRecord, Long> {}
