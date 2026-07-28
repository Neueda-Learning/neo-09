package com.neobank.module.support.repository;

import com.neobank.module.support.model.CaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseEventRepository extends JpaRepository<CaseEvent, Long> {

    long countByCaseIdAndEventType(String caseId, String eventType);
}
