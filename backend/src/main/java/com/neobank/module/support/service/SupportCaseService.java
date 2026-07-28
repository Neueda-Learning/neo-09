package com.neobank.module.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.support.api.SupportCaseView;
import com.neobank.module.support.model.CaseConfig;
import com.neobank.module.support.model.CaseEvent;
import com.neobank.module.support.model.SupportCase;
import com.neobank.module.support.repository.CaseConfigRepository;
import com.neobank.module.support.repository.CaseEventRepository;
import com.neobank.module.support.repository.SupportCaseRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SupportCaseService {

    private static final Logger log = LoggerFactory.getLogger(SupportCaseService.class);
    private static final TypeReference<Set<String>> CATEGORY_SET = new TypeReference<>() { };

    private final Executor executor;
    private final SupportCaseRepository supportCases;
    private final CaseEventRepository caseEvents;
    private final CaseConfigRepository configs;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CasePricingService pricing;

    public SupportCaseService(
            @Qualifier("applicationTaskExecutor") Executor executor,
            SupportCaseRepository supportCases,
            CaseEventRepository caseEvents,
            CaseConfigRepository configs,
            ObjectMapper objectMapper,
            Clock clock,
            CasePricingService pricing) {
        this.executor = executor;
        this.supportCases = supportCases;
        this.caseEvents = caseEvents;
        this.configs = configs;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.pricing = pricing;
    }

    /**
     * Commits the NEW case and CASE_OPENED event before returning to the controller. The current
     * config row is locked briefly so concurrent deliveries with one correlation id cannot both
     * pass the existence check.
     */
    @Transactional
    public SupportCaseView accept(OpenCaseCommand command) {
        CaseConfig current = configs.lockCurrent()
                .orElseThrow(() -> new IllegalStateException("no case configuration is available"));

        SupportCase existing = supportCases.findByCorrelationId(command.correlationId())
                .orElse(null);
        if (existing != null) {
            return SupportCaseView.of(existing);
        }
        validateCategory(command.category(), current);

        Instant openedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String caseId = caseId(command.correlationId());
        SupportCase created = new SupportCase(
                caseId,
                command.applicationId(),
                command.correlationId(),
                command.category(),
                command.description(),
                command.channel(),
                openedAt);

        supportCases.save(created);
        caseEvents.save(CaseEvent.opened(caseId, command.description(), openedAt));
        supportCases.flush();
        caseEvents.flush();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    executor.execute(() -> {
                        try {
                            pricing.price(caseId);
                        } catch (RuntimeException ex) {
                            log.error("Pricing failed for {}", caseId, ex);
                        }
                    });
                } catch (RuntimeException ex) {
                    log.error("Could not schedule pricing for {}", caseId, ex);
                }
            }
        });
        return SupportCaseView.of(created);
    }

    public List<SupportCaseView> listCases() {
        return supportCases.findAllByOrderByOpenedAtDescIdDesc(PageRequest.of(0, 10)).stream()
                .map(SupportCaseView::of)
                .toList();
    }

    private void validateCategory(String category, CaseConfig config) {
        try {
            Set<String> categories =
                    objectMapper.readValue(config.getCategoriesJson(), CATEGORY_SET);
            if (!categories.contains(category)) {
                throw new InvalidOpenCaseException(
                        "request.category is outside the current taxonomy");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("current category configuration is invalid", ex);
        }
    }

    private String caseId(String correlationId) {
        UUID value = UUID.nameUUIDFromBytes(correlationId.getBytes(StandardCharsets.UTF_8));
        return "case-" + value;
    }
}
