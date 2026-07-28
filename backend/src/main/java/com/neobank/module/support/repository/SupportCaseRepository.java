package com.neobank.module.support.repository;

import com.neobank.module.support.model.SupportCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportCaseRepository extends JpaRepository<SupportCase, Long> {

    Optional<SupportCase> findByCorrelationId(String correlationId);

    Optional<SupportCase> findByCaseId(String caseId);

    List<SupportCase> findAllByOrderByOpenedAtDescIdDesc(Pageable pageable);

    @Query("""
            select supportCase
            from SupportCase supportCase
            where (:status is null or supportCase.status = :status)
              and (
                lower(supportCase.caseId) like lower(concat('%', :query, '%'))
                or lower(supportCase.applicationId) like lower(concat('%', :query, '%'))
                or supportCase.applicationId in :applicationIds
              )
            order by supportCase.openedAt desc, supportCase.id desc
            """)
    List<SupportCase> search(
            @Param("query") String query,
            @Param("status") String status,
            @Param("applicationIds") List<String> applicationIds,
            Pageable pageable);
}
