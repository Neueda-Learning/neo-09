package com.neobank.module.support;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.support.model.SupportCase;
import com.neobank.module.support.repository.CaseEventRepository;
import com.neobank.module.support.repository.SupportCaseRepository;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:supportcasetest;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportCaseFlowTest {

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
                "applicant": {
                  "fullName": "Maria Nowak",
                  "email": "maria@example.test"
                },
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
    void commitsTheCaseAndOpenedEventBeforeReturningTheExactAck() throws Exception {
        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid("corr-1001", "COMPLAINT", "Card promised 10 days ago")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$.status").value("in-progress"))
                .andExpect(jsonPath("$.applicationId").value("app-1001"))
                .andExpect(jsonPath("$.command").value("open-case"));

        SupportCase saved = supportCases.findByCorrelationId("corr-1001").orElseThrow();
        assertThat(saved.getApplicationId()).isEqualTo("app-1001");
        assertThat(saved.getStatus()).isEqualTo("NEW");
        assertThat(saved.getCategory()).isEqualTo("COMPLAINT");
        assertThat(saved.getDescription()).isEqualTo("Card promised 10 days ago");
        assertThat(saved.getChannel()).isEqualTo("MOBILE_APP");
        assertThat(saved.getPriority()).isEqualTo("P1");
        assertThat(saved.getConfigVersion()).isEqualTo(1);
        assertThat(Duration.between(saved.getOpenedAt(), saved.getSlaDeadline()))
                .isEqualTo(Duration.ofHours(4));

        assertThat(caseEvents.countByCaseIdAndEventType(saved.getCaseId(), "CASE_OPENED"))
                .isOne();
        assertThat(jdbc.queryForObject(
                "select customer_id from support_case where case_id = ?",
                String.class,
                saved.getCaseId())).isNull();
        verifyNoInteractions(orchestrator);
    }

    @Test
    void redeliveryIsIdempotentButANewCorrelationOpensAnotherCase() throws Exception {
        deliver(valid("corr-same", "CARD_NOT_ARRIVED", "First delivery"));
        deliver(valid("corr-same", "CARD_NOT_ARRIVED", "Changed duplicate text"));

        assertThat(supportCases.count()).isOne();
        SupportCase first = supportCases.findByCorrelationId("corr-same").orElseThrow();
        assertThat(first.getDescription()).isEqualTo("First delivery");
        assertThat(caseEvents.countByCaseIdAndEventType(first.getCaseId(), "CASE_OPENED")).isOne();

        deliver(valid("corr-new", "OTHER", "A separate problem"));
        assertThat(supportCases.count()).isEqualTo(2);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void malformedOrInvalidRequestsAre400AndStoreNothing() throws Exception {
        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"correlationId":"corr-no-app","command":"open-case",
                                 "request":{"category":"OTHER","description":"Help","channel":"WEB"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("applicationId")));

        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-1","command":"open-case",
                                 "request":{"category":"OTHER","description":"Help","channel":"WEB"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("correlationId")));

        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"app-1","correlationId":"corr-no-command",
                                 "request":{"category":"OTHER","description":"Help","channel":"WEB"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("command")));

        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid("corr-bad-category", "NOT_A_CATEGORY", "Help")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("taxonomy")));

        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid("corr-blank", "OTHER", " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("description")));

        assertThat(supportCases.count()).isZero();
        assertThat(caseEvents.count()).isZero();
        verifyNoInteractions(orchestrator);
    }

    @Test
    void caseIsImmediatelyVisibleOnTheCappedBoardApi() throws Exception {
        deliver(valid("corr-board", "DATA_CORRECTION", "My address is wrong"));

        mvc.perform(get("/api/v1/support/cases").param("q", "app-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NEW"))
                .andExpect(jsonPath("$[0].category").value("DATA_CORRECTION"))
                .andExpect(jsonPath("$[0].priority").value("P2"));
    }

    @Test
    void searchIsEmptyByDefaultAndFindsApplicantNamesWithoutPersistingThem() throws Exception {
        deliver(valid("corr-search", "CARD_NOT_ARRIVED", "Card not here"));

        mvc.perform(get("/api/v1/support/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        verifyNoInteractions(orchestrator);

        when(orchestrator.applicationsByName("Maria"))
                .thenReturn(List.of(applicantApplication()));
        mvc.perform(get("/api/v1/support/cases")
                        .param("q", "Maria")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationId").value("app-1001"))
                .andExpect(jsonPath("$[0].category").value("CARD_NOT_ARRIVED"));
        verify(orchestrator).applicationsByName("Maria");

        assertThat(jdbc.queryForObject(
                "select customer_id from support_case where correlation_id = ?",
                String.class,
                "corr-search")).isNull();
    }

    @Test
    void searchRequiresTheCompleteQueryAndStatusWorksWithoutAQuery() throws Exception {
        deliver(valid("corr-strict-search", "CARD_NOT_ARRIVED", "Card not here"));

        when(orchestrator.applicationsByName("24"))
                .thenReturn(List.of(applicantApplication()));
        mvc.perform(get("/api/v1/support/cases")
                        .param("q", "24")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mvc.perform(get("/api/v1/support/cases")
                        .param("status", "NEW")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationId").value("app-1001"))
                .andExpect(jsonPath("$[0].status").value("NEW"));
        verify(orchestrator).applicationsByName("24");
    }

    @Test
    void searchIsCappedAtTenAndRejectsAnOversizedLimit() throws Exception {
        for (int index = 0; index < 11; index++) {
            deliver(valid("corr-cap-" + index, "OTHER", "Case " + index));
        }

        mvc.perform(get("/api/v1/support/cases")
                        .param("q", "app-1001")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));

        mvc.perform(get("/api/v1/support/cases")
                        .param("q", "app-1001")
                        .param("limit", "11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 10"));
    }

    @Test
    void queueIsWorstFirstAndPersistsBreachChanges() throws Exception {
        deliver(valid("corr-most-overdue", "COMPLAINT", "Old breach"));
        deliver(valid("corr-less-overdue", "COMPLAINT", "Recent breach"));
        deliver(valid("corr-healthy", "COMPLAINT", "Healthy"));

        Instant now = Instant.now();
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.minus(Duration.ofHours(5)), "corr-most-overdue");
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.minus(Duration.ofHours(1)), "corr-less-overdue");
        jdbc.update("update support_case set sla_deadline = ? where correlation_id = ?",
                now.plus(Duration.ofHours(1)), "corr-healthy");

        String mostOverdueCaseId = supportCases
                .findByCorrelationId("corr-most-overdue").orElseThrow().getCaseId();
        mvc.perform(get("/api/v1/support/cases/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOpen").value(3))
                .andExpect(jsonPath("$.breached").value(2))
                .andExpect(jsonPath("$.cases[0].caseId").value(mostOverdueCaseId))
                .andExpect(jsonPath("$.cases[0].breached").value(true));

        assertThat(jdbc.queryForObject(
                "select breached from support_case where correlation_id = ?",
                Boolean.class,
                "corr-most-overdue")).isTrue();
    }

    @Test
    void queueAndCaseDetailAndApplicantEndpointsArePublished() throws Exception {
        deliver(valid("corr-detail", "CARD_NOT_ARRIVED", "Card not here"));
        String caseId = supportCases.findByCorrelationId("corr-detail").orElseThrow().getCaseId();
                when(orchestrator.application("app-1001")).thenReturn(applicantApplication());

        mvc.perform(get("/api/v1/support/cases/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases").isArray())
                .andExpect(jsonPath("$.totalOpen").value(1));

        mvc.perform(get("/api/v1/support/cases/" + caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId))
                .andExpect(jsonPath("$.description").value("Card not here"))
                .andExpect(jsonPath("$.channel").value("MOBILE_APP"))
                .andExpect(jsonPath("$.pausedMinutes").value(0))
                .andExpect(jsonPath("$.events[0].type").value("CASE_OPENED"));

        mvc.perform(get("/api/v1/support/cases/" + caseId + "/applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicant.fullName").value("Maria Nowak"))
                .andExpect(jsonPath("$.product.productCode").value("CREDIT_CARD_REWARDS"));

        mvc.perform(get("/api/v1/support/cases/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("case not found: does-not-exist"));
    }

    /**
     * The three ways an applicant lookup fails, told apart. This is the whole point of the wording:
     * a 404 is a <em>data</em> fact about this case, everything else is about the orchestrator, and
     * an operator who cannot tell them apart goes looking for the bug in the wrong repository.
     */
    @Test
    void applicantLookupSaysWhichKindOfFailureItWas() throws Exception {
        deliver(valid("corr-orphan", "OTHER", "Old application link"));
        String caseId = supportCases.findByCorrelationId("corr-orphan").orElseThrow().getCaseId();

        // 1 — no such application. The common case: a case opened by hand or seeded for a demo.
        when(orchestrator.application("app-1001")).thenThrow(
                HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "not found", null, null, null));
        mvc.perform(get("/api/v1/support/cases/" + caseId + "/applicant"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("the orchestrator has no application "
                        + "app-1001 — this case was not created from a dispatch, so its applicant "
                        + "cannot be fetched"));

        // 2 — it answered, but not with an application. A 405 is a sidecar built before the
        // lookup endpoint existed, which is a stale image, not a stale link.
        doThrow(HttpClientErrorException.create(
                HttpStatus.METHOD_NOT_ALLOWED, "method not allowed", null, null, null))
                .when(orchestrator).application("app-1001");
        mvc.perform(get("/api/v1/support/cases/" + caseId + "/applicant"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("the orchestrator answered "
                        + "405 METHOD_NOT_ALLOWED when asked for application app-1001"));

        // 3 — never reached it. The cause is named as well as quoted: an UnknownHostException's
        // message is the bare hostname and explains nothing on its own.
        doThrow(new ResourceAccessException("I/O error", new java.net.UnknownHostException("sidecar")))
                .when(orchestrator).application("app-1001");
        mvc.perform(get("/api/v1/support/cases/" + caseId + "/applicant"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("could not reach the orchestrator to fetch "
                        + "application app-1001 (UnknownHostException: sidecar)"));
    }

    // No test for a case with a null application_id: the column is NOT NULL (changelog 002) and
    // @NotBlank rejects a blank one at the door, so it is unreachable from here. The guard in
    // SupportCaseService.applicant() stays anyway — two lines to never issue
    // GET …/applications/null if a future path builds a case some other way.

    @Test
    void supportEndpointsArePublishedInOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(containsString("3.0")))
                .andExpect(jsonPath("$.paths['/api/v1/support/execute'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/support/cases'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/support/cases/queue'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/support/cases/{caseId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/support/cases/{caseId}/applicant'].get").exists());
    }

    private void deliver(String body) throws Exception {
        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    private static String valid(
            String correlationId, String category, String description) {
        return VALID.formatted(correlationId, category, description);
    }

    private static Application applicantApplication() {
        return new Application(
                "app-1001",
                "MOBILE_APP",
                "2026-07-25T09:14:00Z",
                new Application.Applicant(
                        "Maria Nowak",
                        "1996-04-11",
                        "maria@example.test",
                        "+48123456789",
                        "PL",
                        "PL",
                        java.util.List.of("PL"),
                        "OWNER",
                        new Application.Address(
                                "1 Main St",
                                null,
                                "Warsaw",
                                "00-001",
                                "PL"),
                        24,
                        0),
                new Application.IdentityDocument(
                        "PASSPORT",
                        "P1234567",
                        "PL",
                        "2030-01-01"),
                new Application.Employment(
                        "PERMANENT",
                        "NeoBank",
                        18),
                new Application.Finances(
                        900000,
                        1200,
                        300),
                new Application.Product(
                        "CREDIT_CARD_REWARDS",
                        2800),
                new Application.Delivery(
                        true,
                        null),
                new Application.Consents(
                        true,
                        true,
                        false));
    }
}
