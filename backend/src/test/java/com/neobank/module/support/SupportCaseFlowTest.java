package com.neobank.module.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.support.model.SupportCase;
import com.neobank.module.support.repository.CaseEventRepository;
import com.neobank.module.support.repository.SupportCaseRepository;
import java.time.Duration;
import java.util.concurrent.Executor;
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

        mvc.perform(get("/api/v1/support/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("NEW"))
                .andExpect(jsonPath("$[0].category").value("DATA_CORRECTION"))
                .andExpect(jsonPath("$[0].priority").value("P2"));
    }

    @Test
    void supportEndpointsArePublishedInOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(containsString("3.0")))
                .andExpect(jsonPath("$.paths['/api/v1/support/execute'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/support/cases'].get").exists());
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
}
