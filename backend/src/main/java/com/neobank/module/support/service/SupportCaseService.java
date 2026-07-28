package com.neobank.module.support.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
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
import org.springframework.web.client.RestClientException;

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
    private static final Set<String> CASE_STATUSES =
            Set.of("NEW", "OPEN", "PENDING_CUSTOMER", "RESOLVED", "CLOSED");
    private static final String NO_APPLICATION_MATCH = "__no_application_match__";

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

    @Transactional
    public List<SupportCaseView> searchCases(String query, String status, int limit) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            return List.of();
        }
        if (limit < 1 || limit > 10) {
            throw new IllegalArgumentException("limit must be between 1 and 10");
        }
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : status.trim().toUpperCase();
        if (normalizedStatus != null && !CASE_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("unknown case status: " + status);
        }

        LinkedHashSet<String> applicationIds = new LinkedHashSet<>();
        applicationIds.add(NO_APPLICATION_MATCH);
        try {
            orchestrator.applicationsByName(normalizedQuery).stream()
                    .map(Application::applicationId)
                    .filter(applicationId -> applicationId != null && !applicationId.isBlank())
                    .forEach(applicationIds::add);
        } catch (RestClientException ex) {
            log.warn("Applicant name search unavailable for '{}': {}",
                    normalizedQuery, ex.toString());
        }

        Instant now = clock.instant();
        return supportCases.search(
                        normalizedQuery,
                        normalizedStatus,
                        List.copyOf(applicationIds),
                        PageRequest.of(0, limit))
                .stream()
                .map(supportCase -> SupportCaseView.of(
                        supportCase, refreshBreached(supportCase, now)))
                .toList();
    }

    @Transactional
    public SupportCaseQueueResponse queue() {
        Instant now = clock.instant();
        List<SupportCaseQueueRow> allOpenCases = supportCases.findAll().stream()
                .filter(supportCase -> isOpen(supportCase.getStatus()))
                .map(supportCase -> {
                    refreshBreached(supportCase, now);
                    return toQueueRow(supportCase, now);
                })
                .sorted(queueOrder())
                .toList();

        List<SupportCaseQueueRow> visibleCases = allOpenCases.stream()
                .limit(10)
                .toList();
        int breached = (int) allOpenCases.stream()
                .filter(SupportCaseQueueRow::breached)
                .count();

        return new SupportCaseQueueResponse(allOpenCases.size(), breached, visibleCases);
    }

    @Transactional
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
                supportCase.getChannel(),
                supportCase.getPriority(),
                supportCase.getSlaDeadline(),
                refreshBreached(supportCase, clock.instant()),
                supportCase.getApplicationId(),
                supportCase.getCorrelationId(),
                supportCase.getConfigVersion(),
                supportCase.getDescription(),
                supportCase.getAssignee(),
                supportCase.getPausedMinutes(),
                supportCase.getResolutionNote(),
                supportCase.getOpenedAt(),
                supportCase.getResolvedAt(),
                supportCase.getClosedAt(),
                events);
    }

    public Application applicant(String caseId) {
        SupportCase supportCase = supportCases.findByCaseId(caseId)
                .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        try {
            return orchestrator.application(supportCase.getApplicationId());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ex) {
            throw new NoSuchElementException("application not found — link may be stale");
        } catch (RestClientException ex) {
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
                supportCase.isBreached(),
                overdueHours(supportCase, now),
                supportCase.getApplicationId(),
                supportCase.getSlaDeadline(),
                supportCase.getOpenedAt());
    }

    private Comparator<SupportCaseQueueRow> queueOrder() {
        return Comparator.comparing(SupportCaseQueueRow::breached).reversed()
                .thenComparing(
                        SupportCaseQueueRow::slaDeadline,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SupportCaseQueueRow::caseId);
    }

    private boolean isOpen(String status) {
        return "NEW".equals(status) || "OPEN".equals(status) || "PENDING_CUSTOMER".equals(status);
    }

    private boolean currentBreach(SupportCase supportCase, Instant now) {
        return supportCase.getSlaDeadline() != null
                && supportCase.getSlaDeadline().isBefore(now)
                && ("NEW".equals(supportCase.getStatus())
                    || "OPEN".equals(supportCase.getStatus()));
    }

    private boolean refreshBreached(SupportCase supportCase, Instant now) {
        boolean breached = currentBreach(supportCase, now);
        if (supportCase.isBreached() != breached) {
            supportCase.markBreached(breached);
        }
        return breached;
    }

    private double overdueHours(SupportCase supportCase, Instant now) {
        if (!currentBreach(supportCase, now)) {
            return 0.0;
        }
        return Math.max(0.0, Duration.between(supportCase.getSlaDeadline(), now).toMinutes() / 60.0);
    }
}
