package com.neobank.module.support.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.Decision;
import com.neobank.module.support.api.SupportCaseDetailView;
import com.neobank.module.support.api.CategorySuggestion;
import com.neobank.module.support.api.SupportCaseEventView;
import com.neobank.module.support.api.SupportCaseQueueResponse;
import com.neobank.module.support.api.SupportCaseQueueRow;
import com.neobank.module.support.api.SupportCaseView;
import com.neobank.module.support.api.SlaBoardResponse;
import com.neobank.module.support.api.SlaBreachedCaseView;
import com.neobank.module.support.api.SlaCategoryCsat;
import com.neobank.module.support.api.SlaPriorityCount;
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
    private static final TypeReference<Map<String, Integer>> SLA_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, List<String>>> KEYWORD_MAP =
            new TypeReference<>() { };
    private static final Set<String> CASE_STATUSES =
            Set.of("NEW", "OPEN", "PENDING_CUSTOMER", "RESOLVED", "CLOSED");
    private static final Set<String> ACTIONS =
            Set.of("PICK_UP", "WAIT_CUSTOMER", "RESUME", "RESOLVE", "CLOSE", "REOPEN");
        private static final Set<String> SUPERVISOR_ACTIONS =
            Set.of("FORCE_CLOSE", "REASSIGN");
    private static final String NO_APPLICATION_MATCH = "__no_application_match__";
    /** Weakest to strongest, so index+1 is always the next escalation target. */
    private static final List<String> PRIORITY_LEVELS_LOW_FIRST = List.of("P3", "P2", "P1");
    private static final List<String> PRIORITY_LEVELS_HIGH_FIRST = List.of("P1", "P2", "P3");

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
        if (limit < 1 || limit > 10) {
            throw new IllegalArgumentException("limit must be between 1 and 10");
        }
        String normalizedStatus = status == null || status.isBlank()
                ? null
                : status.trim().toUpperCase();
        if (normalizedStatus != null && !CASE_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("unknown case status: " + status);
        }
        if (normalizedQuery.isEmpty() && normalizedStatus == null) {
            return List.of();
        }

        LinkedHashSet<String> applicationIds = new LinkedHashSet<>();
        applicationIds.add(NO_APPLICATION_MATCH);
        if (!normalizedQuery.isEmpty()) {
            try {
                orchestrator.applicationsByName(normalizedQuery).stream()
                        // The orchestrator may search names by separate tokens. The support
                        // search contract is stricter: the complete query must occur contiguously.
                        .filter(application -> applicantNameContains(application, normalizedQuery))
                        .map(Application::applicationId)
                        .filter(applicationId -> applicationId != null && !applicationId.isBlank())
                        .forEach(applicationIds::add);
            } catch (RestClientException ex) {
                log.warn("Applicant name search unavailable for '{}': {}",
                        normalizedQuery, ex.toString());
            }
        }

        Instant now = clock.instant();
        return supportCases.search(
                        normalizedQuery,
                        normalizedStatus,
                        List.copyOf(applicationIds),
                        PageRequest.of(0, limit))
                .stream()
                .map(supportCase -> {
                    escalateIfNeeded(supportCase, now);
                    return SupportCaseView.of(supportCase, refreshBreached(supportCase, now));
                })
                .toList();
    }

    private boolean applicantNameContains(Application application, String query) {
        return application != null
                && application.applicant() != null
                && application.applicant().fullName() != null
                && normalizeSearchText(application.applicant().fullName())
                        .contains(normalizeSearchText(query));
    }

    @Transactional
    public SupportCaseQueueResponse queue() {
        Instant now = clock.instant();
        List<SupportCaseQueueRow> allOpenCases = supportCases.findAllOpenForUpdate().stream()
                .map(supportCase -> {
                    escalateIfNeeded(supportCase, now);
                    refreshBreached(supportCase, now);
                    return toQueueRow(supportCase, now);
                })
                .sorted(queueOrder())
                .toList();

        List<SupportCaseQueueRow> visibleCases = allOpenCases.stream()
                .limit(10)
                .toList();
        int breached = Math.toIntExact(allOpenCases.stream()
                .filter(SupportCaseQueueRow::breached)
            .count());

        return new SupportCaseQueueResponse(allOpenCases.size(), breached, visibleCases);
    }

    /**
     * {@code GET /sla} (UC 04) — per-priority open/breached tallies plus the worst breaches,
     * capped at 10, worst (most overdue) first. Runs the same escalation + breach sweep as the
     * queue over the same open rows, so the two screens never disagree.
     */
    @Transactional
    public SlaBoardResponse slaBoard() {
        Instant now = clock.instant();
        Map<String, int[]> tally = new LinkedHashMap<>();
        for (String priority : PRIORITY_LEVELS_HIGH_FIRST) {
            tally.put(priority, new int[2]);
        }

        List<SlaBreachedCaseView> breachedCases = new ArrayList<>();
        supportCases.findAllOpenForUpdate().stream()
                .forEach(supportCase -> {
                    escalateIfNeeded(supportCase, now);
                    boolean breached = refreshBreached(supportCase, now);
                    String priority = supportCase.getPriority();
                    int[] counts = tally.get(priority);
                    if (counts != null) {
                        counts[0]++;
                        if (breached) {
                            counts[1]++;
                        }
                    }
                    if (breached) {
                        breachedCases.add(new SlaBreachedCaseView(
                                supportCase.getCaseId(), priority, overdueHours(supportCase, now)));
                    }
                });

        List<SlaPriorityCount> byPriority = PRIORITY_LEVELS_HIGH_FIRST.stream()
                .map(priority -> new SlaPriorityCount(
                        priority, tally.get(priority)[0], tally.get(priority)[1]))
                .toList();
        List<SlaBreachedCaseView> worstFirst = breachedCases.stream()
                .sorted(Comparator.comparingDouble(SlaBreachedCaseView::overdueHours).reversed())
                .limit(10)
                .toList();

        Map<String, Double> averages = new LinkedHashMap<>();
        supportCases.findClosedCsatAverages().forEach(row ->
                averages.put(row.getCategory(), roundOneDecimal(row.getAverageScore())));

        CaseConfig currentConfig = configs.findTopByOrderByVersionDesc()
                .orElseThrow(() -> new IllegalStateException("no case configuration is available"));
        Set<String> categories = new TreeSet<>(readCategories(currentConfig));
        categories.addAll(averages.keySet());
        List<SlaCategoryCsat> csatByCategory = categories.stream()
                .map(category -> new SlaCategoryCsat(category, averages.get(category)))
                .toList();

        return new SlaBoardResponse(now, byPriority, worstFirst, csatByCategory);
    }

    @Transactional
    public SupportCaseDetailView getCase(String caseId) {
        SupportCase supportCase = supportCases.findByCaseIdForUpdate(caseId)
                .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        List<SupportCaseEventView> events = caseEvents.findAllByCaseIdOrderByCreatedAtAscIdAsc(caseId)
                .stream()
                .map(SupportCaseEventView::of)
                .toList();
        Instant now = clock.instant();
        if (isOpen(supportCase.getStatus())) {
            escalateIfNeeded(supportCase, now);
        }
        return new SupportCaseDetailView(
                supportCase.getCaseId(),
                supportCase.getStatus(),
                supportCase.getCategory(),
                supportCase.getChannel(),
                supportCase.getPriority(),
                supportCase.getSlaDeadline(),
                refreshBreached(supportCase, now),
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
                supportCase.getCsatScore(),
                supportCase.getCsatComment(),
                events);
    }

    /**
     * The applicant behind a case, fetched live — this module stores the id, never the name.
     *
     * <p>A 404 from the orchestrator is the common failure and it is a <em>data</em> problem, not a
     * broken endpoint: only a case created from a real dispatch has an application the orchestrator
     * has ever heard of. A case opened by hand, seeded for a demo, or bridged from a dispatch the
     * orchestrator has since forgotten carries an id nothing can resolve. The message says which,
     * because "not found" alone sends people looking for the bug in the wrong repository.</p>
     */
    public Application applicant(String caseId) {
        SupportCase supportCase = supportCases.findByCaseId(caseId)
                .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        String applicationId = supportCase.getApplicationId();
        if (applicationId == null || applicationId.isBlank()) {
            throw new NoSuchElementException(
                    "case " + caseId + " is not linked to an application, so there is no applicant "
                            + "to fetch");
        }
        try {
            return orchestrator.application(applicationId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new NoSuchElementException("the orchestrator has no application " + applicationId
                    + " — this case was not created from a dispatch, so its applicant cannot be "
                    + "fetched");
        } catch (HttpStatusCodeException ex) {
            // Anything else it answered is about the orchestrator, not about this case. A 405 here
            // means it is an old build with no lookup endpoint, which is worth saying out loud.
            throw new ApplicantLookupFailedException("the orchestrator answered "
                    + ex.getStatusCode() + " when asked for application " + applicationId);
        } catch (RestClientException ex) {
            // Name the exception as well as its message: an UnknownHostException's message is the
            // bare hostname, and "…to fetch application SIM-01: sidecar" explains nothing on its own.
            Throwable cause = ex.getMostSpecificCause();
            String detail = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            throw new ApplicantLookupFailedException("could not reach the orchestrator to fetch "
                    + "application " + applicationId + " (" + detail + ")");
        }
    }

    @Transactional(readOnly = true)
    public List<CategorySuggestion> suggestCategory(String caseId) {
        SupportCase supportCase = supportCases.findByCaseId(caseId)
                .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        CaseConfig current = configs.findTopByOrderByVersionDesc()
                .orElseThrow(() -> new IllegalStateException("no case configuration is available"));
        if (current.getKeywordMapJson() == null || supportCase.getDescription() == null) {
            return List.of();
        }

        try {
            Map<String, List<String>> keywordMap =
                    objectMapper.readValue(current.getKeywordMapJson(), KEYWORD_MAP);
            String description = normalizeSearchText(supportCase.getDescription());
            if (description.isBlank()) {
                return List.of();
            }

            List<CategorySuggestion> suggestions = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : keywordMap.entrySet()) {
                List<String> matched = entry.getValue().stream()
                        .map(this::normalizeSearchText)
                        .filter(keyword -> !keyword.isBlank())
                        .distinct()
                        .filter(keyword -> containsKeyword(description, keyword))
                        .toList();
                if (!matched.isEmpty()) {
                    suggestions.add(new CategorySuggestion(
                            entry.getKey(), matched.size(), matched));
                }
            }
            return suggestions.stream()
                    .sorted(Comparator.comparingInt(CategorySuggestion::score).reversed()
                            .thenComparing(CategorySuggestion::category))
                    .toList();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("current keyword_map_json is invalid", ex);
        }
    }

    @Transactional
    public SupportCaseDetailView transition(String caseId, String action, String actor, String note) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        String normalizedActor = actor == null ? "" : actor.trim();
        String normalizedNote = note == null ? null : note.trim();

        if (!ACTIONS.contains(normalizedAction)) {
            throw new IllegalArgumentException("unknown transition action: " + action);
        }
        if (normalizedActor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }

        SupportCase supportCase = supportCases.findByCaseIdForUpdate(caseId)
                .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String fromStatus = supportCase.getStatus();

        if ("CLOSED".equals(fromStatus)) {
            throw conflict("illegal transition from CLOSED");
        }

        switch (normalizedAction) {
            case "PICK_UP" -> requireStatus(supportCase, "NEW");
            case "WAIT_CUSTOMER" -> requireStatus(supportCase, "OPEN");
            case "RESUME" -> requireStatus(supportCase, "PENDING_CUSTOMER");
            case "RESOLVE" -> requireStatus(supportCase, "OPEN");
            case "CLOSE" -> requireStatus(supportCase, "RESOLVED");
            case "REOPEN" -> requireStatus(supportCase, "RESOLVED");
            default -> throw new IllegalArgumentException("unknown transition action: " + action);
        }

        switch (normalizedAction) {
            case "PICK_UP" -> supportCase.pickUp(normalizedActor);
            case "WAIT_CUSTOMER" -> supportCase.waitCustomer(now);
            case "RESUME" -> supportCase.resume(now);
            case "RESOLVE" -> {
                if (normalizedNote == null || normalizedNote.isBlank()) {
                    throw new IllegalArgumentException("note is required for RESOLVE");
                }
                supportCase.resolve(normalizedNote, now);
                triggerFirstCallbackIfNeeded(supportCase, normalizedNote, now);
            }
            case "CLOSE" -> supportCase.close(now);
            case "REOPEN" -> {
                validateReopenWindow(supportCase, now);
                supportCase.reopen(freshDeadlineFromReopen(supportCase, now));
            }
            default -> throw new IllegalArgumentException("unknown transition action: " + action);
        }

        caseEvents.save(CaseEvent.transition(
                supportCase.getCaseId(),
                normalizedAction,
                fromStatus,
                supportCase.getStatus(),
                normalizedActor,
                normalizedNote,
                now));
        supportCases.save(supportCase);

        return getCase(supportCase.getCaseId());
    }

    @Transactional
    public SupportCaseDetailView supervisorAction(
            String caseId,
            String action,
            String reason,
            String supervisor,
            String assignee) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        String normalizedReason = reason == null ? "" : reason.trim();
        String normalizedSupervisor = supervisor == null ? "" : supervisor.trim();
        String normalizedAssignee = assignee == null ? "" : assignee.trim();

        if (!SUPERVISOR_ACTIONS.contains(normalizedAction)) {
            throw new IllegalArgumentException("unknown supervisor action: " + action);
        }
        if (normalizedReason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        if (normalizedSupervisor.isBlank()) {
            throw new IllegalArgumentException("supervisor is required");
        }

        SupportCase supportCase = supportCases.findByCaseIdForUpdate(caseId)
                .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        String fromStatus = supportCase.getStatus();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        if ("CLOSED".equals(fromStatus)) {
            throw conflict("closed is closed");
        }

        switch (normalizedAction) {
            case "FORCE_CLOSE" -> {
                supportCase.close(now);
                caseEvents.save(CaseEvent.transition(
                        supportCase.getCaseId(),
                        "SUPERVISOR_FORCE_CLOSED",
                        fromStatus,
                        "CLOSED",
                        normalizedSupervisor,
                        normalizedReason,
                        now));
                triggerSupervisorClosureCallbackIfNeeded(supportCase, normalizedReason, now);
            }
            case "REASSIGN" -> {
                if (!Set.of("NEW", "OPEN", "PENDING_CUSTOMER").contains(fromStatus)) {
                    throw conflict("illegal supervisor reassign from " + fromStatus);
                }
                if (normalizedAssignee.isBlank()) {
                    throw new IllegalArgumentException("assignee is required for REASSIGN");
                }
                supportCase.reassign(normalizedAssignee);
                caseEvents.save(CaseEvent.transition(
                        supportCase.getCaseId(),
                        "SUPERVISOR_REASSIGNED",
                        null,
                        null,
                        normalizedSupervisor,
                        normalizedReason,
                        now));
            }
            default -> throw new IllegalArgumentException("unknown supervisor action: " + action);
        }

        supportCases.save(supportCase);
        return getCase(supportCase.getCaseId());
    }

    @Transactional
    public SupportCaseDetailView recordCsat(String caseId, int score, String comment) {
        if (score < 1 || score > 5) {
            throw new IllegalArgumentException("score must be between 1 and 5");
        }

        SupportCase supportCase = supportCases.findByCaseIdForUpdate(caseId)
                .orElseThrow(() -> new NoSuchElementException("case not found: " + caseId));
        if (!"CLOSED".equals(supportCase.getStatus())) {
            throw conflict("CSAT can only be recorded for a CLOSED case");
        }
        if (supportCase.getCsatScore() != null) {
            throw conflict("CSAT has already been recorded");
        }

        String normalizedComment = comment == null || comment.trim().isBlank()
                ? null
                : comment.trim();
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        supportCase.recordCsat(score, normalizedComment);
        caseEvents.save(CaseEvent.transition(
                caseId,
                "CSAT_RECORDED",
                null,
                null,
                "customer via orchestrator",
                normalizedComment,
                now));
        supportCases.save(supportCase);
        return getCase(caseId);
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

    private Set<String> readCategories(CaseConfig config) {
        try {
            return objectMapper.readValue(config.getCategoriesJson(), CATEGORY_SET);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("current category configuration is invalid", ex);
        }
    }

    private Double roundOneDecimal(Double value) {
        return value == null ? null : Math.round(value * 10.0) / 10.0;
    }

    private String normalizeSearchText(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private boolean containsKeyword(String description, String keyword) {
        Pattern boundaryMatch = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(keyword)
                        + "(?![\\p{L}\\p{N}])");
        return boundaryMatch.matcher(description).find();
    }

    private void triggerFirstCallbackIfNeeded(SupportCase supportCase, String note, Instant now) {
        if (supportCase.isCallbackSent()) {
            return;
        }

        String comment = "SUP_RESOLVED" + (note == null || note.isBlank() ? "" : (": " + note));
        orchestrator.applicationStatusUpdate(
                supportCase.getApplicationId(),
                Decision.ACCEPTED,
                comment);
        supportCase.markCallbackSent();
        caseEvents.save(CaseEvent.callbackSent(supportCase.getCaseId(), comment, now));
    }

    private void triggerSupervisorClosureCallbackIfNeeded(
            SupportCase supportCase,
            String reason,
            Instant now) {
        if (supportCase.isCallbackSent()) {
            return;
        }

        String comment = "SUP_CLOSED_UNRESOLVED: " + reason;
        orchestrator.applicationStatusUpdate(
                supportCase.getApplicationId(),
                Decision.ACCEPTED,
                comment);
        supportCase.markCallbackSent();
        caseEvents.save(CaseEvent.callbackSent(supportCase.getCaseId(), comment, now));
    }

    private void validateReopenWindow(SupportCase supportCase, Instant now) {
        Instant resolvedAt = supportCase.getResolvedAt();
        if (resolvedAt == null) {
            throw conflict("cannot reopen case without resolvedAt");
        }
        if (resolvedAt.plus(7, ChronoUnit.DAYS).isBefore(now)) {
            throw conflict("reopen window expired");
        }
    }

    private Instant freshDeadlineFromReopen(SupportCase supportCase, Instant now) {
        Integer configVersion = supportCase.getConfigVersion();
        String priority = supportCase.getPriority();
        if (configVersion == null || priority == null) {
            return supportCase.getSlaDeadline();
        }

        CaseConfig config = configs.findById(configVersion)
                .orElseGet(() -> configs.findTopByOrderByVersionDesc().orElse(null));
        if (config == null) {
            return supportCase.getSlaDeadline();
        }

        try {
            Map<String, Integer> slaHours = objectMapper.readValue(config.getSlaHoursJson(), SLA_MAP);
            Integer hours = slaHours.get(priority);
            if (hours == null) {
                return supportCase.getSlaDeadline();
            }
            return now.plus(hours, ChronoUnit.HOURS);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored sla_hours_json is invalid", ex);
        }
    }

    private void requireStatus(SupportCase supportCase, String expected) {
        if (!expected.equals(supportCase.getStatus())) {
            throw conflict("illegal transition from " + supportCase.getStatus());
        }
    }

    private IllegalCaseTransitionException conflict(String message) {
        return new IllegalCaseTransitionException(message);
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

    /**
     * Age escalation sweep (UC 04, rules 1+2): while the case's clock is actually running
     * (NEW/OPEN — a PENDING_CUSTOMER case is paused, same exclusion as breach) and it is past its
     * current deadline, raises the priority one level, recomputes the deadline from openedAt +
     * the new level's SLA hours + the stored total paused minutes, and writes one
     * PRIORITY_ESCALATED event per level. Stops at P1, and never repeats a level once the case's
     * priority column already reflects it — so re-running this on every read is safe.
     */
    private void escalateIfNeeded(SupportCase supportCase, Instant now) {
        String priority = supportCase.getPriority();
        Integer configVersion = supportCase.getConfigVersion();
        if (priority == null || configVersion == null
                || !("NEW".equals(supportCase.getStatus()) || "OPEN".equals(supportCase.getStatus()))) {
            return;
        }

        Map<String, Integer> slaHours = null;
        while (true) {
            int level = PRIORITY_LEVELS_LOW_FIRST.indexOf(supportCase.getPriority());
            if (level < 0 || level == PRIORITY_LEVELS_LOW_FIRST.size() - 1) {
                return;
            }
            if (supportCase.getSlaDeadline() == null || !now.isAfter(supportCase.getSlaDeadline())) {
                return;
            }
            if (slaHours == null) {
                CaseConfig config = configs.findById(configVersion).orElse(null);
                if (config == null) {
                    log.warn(
                            "case {} pinned to missing config version {} — skipping age escalation",
                            supportCase.getCaseId(), configVersion);
                    return;
                }
                try {
                    slaHours = objectMapper.readValue(config.getSlaHoursJson(), SLA_MAP);
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("stored sla_hours_json is invalid", ex);
                }
            }
            String fromPriority = supportCase.getPriority();
            String toPriority = PRIORITY_LEVELS_LOW_FIRST.get(level + 1);
            Integer hours = slaHours.get(toPriority);
            if (hours == null) {
                log.warn(
                        "case {} config version {} has no sla_hours entry for {} — skipping age escalation",
                        supportCase.getCaseId(), configVersion, toPriority);
                return;
            }
            Instant newDeadline = supportCase.getOpenedAt()
                    .plus(hours, ChronoUnit.HOURS)
                    .plus(supportCase.getPausedMinutes(), ChronoUnit.MINUTES);
            supportCase.escalate(toPriority, newDeadline);
            caseEvents.save(CaseEvent.priorityEscalated(
                    supportCase.getCaseId(), fromPriority, toPriority, now));
        }
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
