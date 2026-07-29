package com.neobank.module.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.support.model.SupportCase;
import com.neobank.module.support.repository.CaseEventRepository;
import com.neobank.module.support.repository.SupportCaseRepository;

/**
 * UC 04 · SLA & Breach View — {@code GET /api/v1/support/sla}: priority is never typed (rule 1),
 * age escalation raises P3→P2→P1 exactly once per level (rule 1+2), the deadline is recomputed
 * from openedAt + the new level's SLA hours + paused minutes (rule 3), and a PENDING_CUSTOMER
 * case cannot breach (rule 2).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:supportslatest;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportSlaFlowTest {

    @TestConfiguration
    static class SameThreadExecutor {
        @Bean(name = "applicationTaskExecutor")
        Executor applicationTaskExecutor() {
            return Runnable::run;
        }
    }

    private static final String VALID = """
            {
              "applicationId": "app-1001",
              "correlationId": "%s",
              "command": "open-case",
              "application": {
                "applicant": {"fullName": "Maria Nowak", "email": "maria@example.test"},
                "finances": {"annualIncome": 900000}
              },
              "outputs": {"approvedLimit": 2800, "apr": 24.9},
              "request": {
                "category": "%s",
                "description": "%s",
                "channel": "MOBILE_APP"
              }
            }
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    SupportCaseRepository supportCases;

    @Autowired
    CaseEventRepository caseEvents;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    OrchestratorClient orchestrator;

    @BeforeEach
    void clean() {
        caseEvents.deleteAll();
        supportCases.deleteAll();
    }

    @Test
    void emptyCaseBookReturnsZerosNeverA500() throws Exception {
        mvc.perform(get("/api/v1/support/sla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNow").exists())
                .andExpect(jsonPath("$.byPriority.length()").value(3))
                .andExpect(jsonPath("$.byPriority[0].priority").value("P1"))
                .andExpect(jsonPath("$.byPriority[0].open").value(0))
                .andExpect(jsonPath("$.byPriority[0].breached").value(0))
                .andExpect(jsonPath("$.breachedCases").isEmpty());
    }

    @Test
    void overduePriorityThreeEscalatesOneLevelAtATimeWithOneEventPerLevel() throws Exception {
        deliver(valid("corr-escalate", "OTHER", "Slow leak")); // priced P3, 72h SLA
        SupportCase created = supportCases.findByCorrelationId("corr-escalate").orElseThrow();
        assertThat(created.getPriority()).isEqualTo("P3");

        // Push it just past the P3 deadline — one level of escalation expected: P3 -> P2.
        Instant now = Instant.now();
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.minus(Duration.ofHours(1)), "corr-escalate");

        mvc.perform(get("/api/v1/support/sla")).andExpect(status().isOk());

        SupportCase afterFirstSweep = supportCases.findByCorrelationId("corr-escalate").orElseThrow();
        assertThat(afterFirstSweep.getPriority()).isEqualTo("P2");
        assertThat(caseEvents.countByCaseIdAndEventType(
                afterFirstSweep.getCaseId(), "PRIORITY_ESCALATED")).isOne();

        // Re-reading with the case still not past its NEW (P2) deadline must not escalate again.
        mvc.perform(get("/api/v1/support/sla")).andExpect(status().isOk());
        SupportCase stable = supportCases.findByCorrelationId("corr-escalate").orElseThrow();
        assertThat(stable.getPriority()).isEqualTo("P2");
        assertThat(caseEvents.countByCaseIdAndEventType(
                stable.getCaseId(), "PRIORITY_ESCALATED")).isOne();
    }

    @Test
    void concurrentSlaReadsStillWriteOneEscalationEventForTheLevel() throws Exception {
        deliver(valid("corr-concurrent", "OTHER", "Concurrent sweep")); // priced P3, 72h SLA
        SupportCase created = supportCases.findByCorrelationId("corr-concurrent").orElseThrow();
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                Instant.now().minus(Duration.ofHours(1)), "corr-concurrent");

        int readers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        List<Future<?>> requests = new ArrayList<>();
        try {
            for (int reader = 0; reader < readers; reader++) {
                requests.add(pool.submit(() -> {
                    start.await();
                    mvc.perform(get("/api/v1/support/sla"))
                            .andExpect(status().isOk());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) {
                request.get();
            }
        } finally {
            pool.shutdownNow();
        }

        SupportCase afterReads = supportCases.findByCaseId(created.getCaseId()).orElseThrow();
        assertThat(afterReads.getPriority()).isEqualTo("P2");
        assertThat(caseEvents.countByCaseIdAndEventType(
                created.getCaseId(), "PRIORITY_ESCALATED")).isOne();
    }

    @Test
    void caseNotYetPastItsDeadlineDoesNotEscalate() throws Exception {
        deliver(valid("corr-not-due", "CARD_NOT_ARRIVED", "Still on the clock")); // priced P2, 24h SLA
        String caseId = supportCases.findByCorrelationId("corr-not-due").orElseThrow().getCaseId();

        // A hair before the deadline — must not be treated as breached or escalated.
        Instant now = Instant.now();
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.plusSeconds(2), "corr-not-due");

        mvc.perform(get("/api/v1/support/sla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breachedCases").isEmpty());

        SupportCase untouched = supportCases.findByCaseId(caseId).orElseThrow();
        assertThat(untouched.isBreached()).isFalse();
        assertThat(untouched.getPriority()).isEqualTo("P2");
        assertThat(caseEvents.countByCaseIdAndEventType(caseId, "PRIORITY_ESCALATED")).isZero();
    }

    @Test
    void pendingCustomerCaseNeverBreachesOrEscalates() throws Exception {
        deliver(valid("corr-paused", "COMPLAINT", "Waiting on the customer")); // P1, 4h SLA
        String caseId = supportCases.findByCorrelationId("corr-paused").orElseThrow().getCaseId();

        Instant now = Instant.now();
        jdbc.update("update support_case set sla_deadline = ?, status = 'PENDING_CUSTOMER' "
                        + "where correlation_id = ?",
                now.minus(Duration.ofHours(10)), "corr-paused");

        mvc.perform(get("/api/v1/support/sla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breachedCases").isEmpty());

        SupportCase paused = supportCases.findByCaseId(caseId).orElseThrow();
        assertThat(paused.isBreached()).isFalse();
        assertThat(paused.getPriority()).isEqualTo("P1");
        assertThat(caseEvents.countByCaseIdAndEventType(caseId, "PRIORITY_ESCALATED")).isZero();
    }

    @Test
    void boardTalliesByPriorityAndListsWorstBreachFirst() throws Exception {
        deliver(valid("corr-p1-worst", "COMPLAINT", "Very late"));
        deliver(valid("corr-p1-less", "COMPLAINT", "A bit late"));
        deliver(valid("corr-p2-healthy", "CARD_NOT_ARRIVED", "On track"));

        Instant now = Instant.now();
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.minus(Duration.ofHours(10)), "corr-p1-worst");
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.minus(Duration.ofHours(1)), "corr-p1-less");
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.plus(Duration.ofHours(1)), "corr-p2-healthy");

        String worstCaseId = supportCases.findByCorrelationId("corr-p1-worst").orElseThrow().getCaseId();

        mvc.perform(get("/api/v1/support/sla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byPriority[0].priority").value("P1"))
                .andExpect(jsonPath("$.byPriority[0].open").value(2))
                .andExpect(jsonPath("$.byPriority[0].breached").value(2))
                .andExpect(jsonPath("$.byPriority[1].priority").value("P2"))
                .andExpect(jsonPath("$.byPriority[1].open").value(1))
                .andExpect(jsonPath("$.byPriority[1].breached").value(0))
                .andExpect(jsonPath("$.breachedCases[0].caseId").value(worstCaseId))
                .andExpect(jsonPath("$.breachedCases[0].overdueHours").value(10.0));
    }

    @Test
    void slaEndpointIsPublishedInOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/support/sla'].get").exists());
    }

    private void deliver(String body) throws Exception {
        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    private String valid(String correlationId, String category, String description) {
        return VALID.formatted(correlationId, category, description);
    }
}
