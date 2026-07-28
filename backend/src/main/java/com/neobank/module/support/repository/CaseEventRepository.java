package com.neobank.module.support.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.neobank.module.support.model.CaseEvent;

public interface CaseEventRepository extends JpaRepository<CaseEvent, Long> {

    long countByCaseIdAndEventType(String caseId, String eventType);

    List<CaseEvent> findAllByCaseIdOrderByCreatedAtAscIdAsc(String caseId);
}
