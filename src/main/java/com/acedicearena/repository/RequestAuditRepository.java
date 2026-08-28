package com.acedicearena.repository;

import com.acedicearena.domain.RequestAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestAuditRepository extends JpaRepository<RequestAudit, Long> {}
