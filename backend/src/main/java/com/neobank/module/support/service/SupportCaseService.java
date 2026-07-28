package com.neobank.module.support.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.support.api.SupportCaseDetailView;
import com.neobank.module.support.api.SupportCaseEventView;
import com.neobank.module.support.api.SupportCaseQueueResponse;
import com.neobank.module.support.api.SupportCaseQueueRow;
import com.neobank.module.support.api.SupportCaseView;
import com.neobank.module.support.model.CaseConfig;
import com.neobank.module.support.model.CaseEvent;
import com.neobank.module.support.model.SupportCase;
import com.neobank.module.support.repository.CaseConfigRepository;
import com.neobank.module.support.repository.CaseEventRepository;
import com.neobank.module.support.repository.SupportCaseRepository;

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
    private final OrchestratorClient orchestrator;

    public SupportCaseService(
            @Qualifier("applicationTaskExecutor") Executor executor,
            SupportCaseRepository supportCases,
            CaseEventRepository caseEvents,
            CaseConfigRepository configs,
            ObjectMapper objectMapper,
            Clock clock,
            CasePricingService pricing,
            OrchestratorClient orchestrator) {
        this.executor = executor;
        this.supportCases = supportCases;
        this.caseEvents = caseEvents;
        this.configs = configs;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.pricing = pricing;
        this.orchestrator = orchestrator;
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

        public SupportCaseQueueResponse queue() {
        Instant now = clock.instant();
        List<SupportCaseQueueRow> openCases = supportCases.findAll().stream()
            .filter(supportCase -> isOpen(supportCase.getStatus()))
            .map(supportCase -> toQueueRow(supportCase, now))
            .sorted(queueOrder())
            .limit(10)
            .toList();

        int totalOpen = (int) supportCases.findAll().stream()
            .filter(supportCase -> isOpen(supportCase.getStatus()))
            .count();
        int breached = (int) supportCases.findAll().stream()
            .filter(supportCase -> isOpen(supportCase.getStatus()))
            .map(supportCase -> toQueueRow(supportCase, now))
            .filter(SupportCaseQueueRow::breached)
            .count();

        return new SupportCaseQueueResponse(totalOpen, breached, openCases);
        }

        public SupportCaseDetailView getCase(String caseId) {
        SupportCase supportCase = supportCases.findByCaseId(caseId)
            .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        List<SupportCaseEventView> events = caseEvents.findAllByCaseIdOrderByCreatedAtAscIdAsc(caseId)
            .stream()
            .map(SupportCaseEventView::of)
            .toList();
        return new SupportCaseDetailView(
            supportCase.getCaseId(),
            supportCase.getStatus(),
            supportCase.getCategory(),
            supportCase.getPriority(),
            supportCase.getSlaDeadline(),
            breached(supportCase, clock.instant()),
            supportCase.getApplicationId(),
            supportCase.getConfigVersion(),
            events);
        }

        public Application applicant(String caseId) {
        SupportCase supportCase = supportCases.findByCaseId(caseId)
            .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        try {
                return orchestrator.application(supportCase.getApplicationId());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            throw new NoSuchElementException("application not found — link may be stale");
        } catch (org.springframework.web.client.RestClientException ex) {
            throw new ApplicantLookupFailedException("application lookup unavailable");
        }
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

    private SupportCaseQueueRow toQueueRow(SupportCase supportCase, Instant now) {
        return new SupportCaseQueueRow(
                supportCase.getCaseId(),
                supportCase.getStatus(),
                supportCase.getCategory(),
                supportCase.getPriority(),
                breached(supportCase, now),
                overdueHours(supportCase, now),
                supportCase.getApplicationId());
    }

    private Comparator<SupportCaseQueueRow> queueOrder() {
        return Comparator.comparing(SupportCaseQueueRow::breached).reversed()
                .thenComparing(SupportCaseQueueRow::overdueHours, Comparator.reverseOrder())
                .thenComparing(SupportCaseQueueRow::caseId);
    }

    private boolean isOpen(String status) {
        return "NEW".equals(status) || "OPEN".equals(status) || "PENDING_CUSTOMER".equals(status);
    }

    private boolean breached(SupportCase supportCase, Instant now) {
        return supportCase.getSlaDeadline() != null
                && supportCase.getSlaDeadline().isBefore(now)
                && isOpen(supportCase.getStatus());
    }

    private double overdueHours(SupportCase supportCase, Instant now) {
        if (supportCase.getSlaDeadline() == null) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(supportCase.getSlaDeadline(), now).toMinutes() / 60.0);
    }
}
