package com.neobank.module.support.repository;

import com.neobank.module.support.model.CaseConfig;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CaseConfigRepository extends JpaRepository<CaseConfig, Integer> {

    Optional<CaseConfig> findTopByOrderByVersionDesc();

    java.util.List<CaseConfig> findAllByOrderByVersionAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CaseConfig c where c.version = (select max(c2.version) from CaseConfig c2)")
    Optional<CaseConfig> lockCurrent();
}
