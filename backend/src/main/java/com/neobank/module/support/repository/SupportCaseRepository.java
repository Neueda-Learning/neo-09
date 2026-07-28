package com.neobank.module.support.repository;

import com.neobank.module.support.model.SupportCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportCaseRepository extends JpaRepository<SupportCase, Long> {

    Optional<SupportCase> findByCorrelationId(String correlationId);

    Optional<SupportCase> findByCaseId(String caseId);

    List<SupportCase> findAllByOrderByOpenedAtDescIdDesc(Pageable pageable);
}
