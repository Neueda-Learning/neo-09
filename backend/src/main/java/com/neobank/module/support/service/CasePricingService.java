package com.neobank.module.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.support.model.CaseConfig;
import com.neobank.module.support.model.SupportCase;
import com.neobank.module.support.repository.CaseConfigRepository;
import com.neobank.module.support.repository.SupportCaseRepository;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CasePricingService {

    private static final Logger log = LoggerFactory.getLogger(CasePricingService.class);
    private static final TypeReference<Map<String, String>> PRIORITY_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Integer>> SLA_MAP = new TypeReference<>() { };

    private final SupportCaseRepository supportCases;
    private final CaseConfigRepository configs;
    private final ObjectMapper objectMapper;

    public CasePricingService(
            SupportCaseRepository supportCases,
            CaseConfigRepository configs,
            ObjectMapper objectMapper) {
        this.supportCases = supportCases;
        this.configs = configs;
        this.objectMapper = objectMapper;
    }

    /**
     * A fresh transaction is required even with the same-thread test executor: afterCommit still
     * runs while Spring is cleaning up the intake transaction's thread-bound state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void price(String caseId) {
        try {
            SupportCase supportCase = supportCases.findByCaseId(caseId).orElseThrow();
            if (supportCase.getPriority() != null) {
                return;
            }
            CaseConfig current = configs.findTopByOrderByVersionDesc().orElseThrow();
            Map<String, String> priorities =
                    objectMapper.readValue(current.getPriorityMapJson(), PRIORITY_MAP);
            Map<String, Integer> slaHours =
                    objectMapper.readValue(current.getSlaHoursJson(), SLA_MAP);
            String priority = priorities.get(supportCase.getCategory());
            Integer hours = slaHours.get(priority);
            if (priority == null || hours == null) {
                throw new IllegalStateException(
                        "current config cannot price category " + supportCase.getCategory());
            }
            supportCase.price(
                    priority,
                    supportCase.getOpenedAt().plus(hours, ChronoUnit.HOURS),
                    current.getVersion());
            log.info("Priced {} as {} using config v{}", caseId, priority, current.getVersion());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("current pricing configuration is invalid", ex);
        }
    }
}
