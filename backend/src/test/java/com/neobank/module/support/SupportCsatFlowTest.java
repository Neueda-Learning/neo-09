package com.neobank.module.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.support.model.SupportCase;
import com.neobank.module.support.repository.CaseEventRepository;
import com.neobank.module.support.repository.SupportCaseRepository;
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
        "spring.datasource.url=jdbc:h2:mem:supportcsattest;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportCsatFlowTest {

    @TestConfiguration
    static class SameThreadExecutor {
        @Bean(name = "applicationTaskExecutor")
        Executor applicationTaskExecutor() {
            return Runnable::run;
        }
    }

    private static final String OPEN_CASE = """
            {
              "applicationId": "app-csat",
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
                "description": "CSAT test case",
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
    void recordsCsatOnlyForAClosedCaseAndAddsTheTimelineEvent() throws Exception {
        String caseId = open("corr-csat", "COMPLAINT");

        submit(caseId, """
                {"score":5,"comment":"Prompt and clear"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("CSAT can only be recorded for a CLOSED case"));
        assertThat(caseEvents.countByCaseIdAndEventType(caseId, "CSAT_RECORDED")).isZero();
        assertThat(supportCases.findByCaseId(caseId).orElseThrow().getCsatScore()).isNull();

        close(caseId);
        submit(caseId, """
                {"score":5,"comment":"  Prompt and clear  "}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csatScore").value(5))
                .andExpect(jsonPath("$.csatComment").value("Prompt and clear"))
                .andExpect(jsonPath("$.events[-1].type").value("CSAT_RECORDED"))
                .andExpect(jsonPath("$.events[-1].actor")
                        .value("customer via orchestrator"))
                .andExpect(jsonPath("$.events[-1].note").value("Prompt and clear"));

        SupportCase recorded = supportCases.findByCaseId(caseId).orElseThrow();
        assertThat(recorded.getCsatScore()).isEqualTo(5);
        assertThat(recorded.getCsatComment()).isEqualTo("Prompt and clear");
        assertThat(caseEvents.countByCaseIdAndEventType(caseId, "CSAT_RECORDED")).isOne();
    }

    @Test
    void scoreIsWriteOnceAndARejectedSecondSubmissionHasNoSideEffects() throws Exception {
        String caseId = open("corr-write-once", "OTHER");
        close(caseId);

        submit(caseId, """
                {"score":2,"comment":"Long wait"}
                """).andExpect(status().isOk());

        submit(caseId, """
                {"score":5,"comment":"Changed my mind"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("CSAT has already been recorded"));

        SupportCase unchanged = supportCases.findByCaseId(caseId).orElseThrow();
        assertThat(unchanged.getCsatScore()).isEqualTo(2);
        assertThat(unchanged.getCsatComment()).isEqualTo("Long wait");
        assertThat(caseEvents.countByCaseIdAndEventType(caseId, "CSAT_RECORDED")).isOne();
    }

    @Test
    void validatesScoreAndSupportsANullComment() throws Exception {
        String caseId = open("corr-validation", "DATA_CORRECTION");
        close(caseId);

        submit(caseId, """
                {"score":0}
                """).andExpect(status().isBadRequest());
        submit(caseId, """
                {"score":6}
                """).andExpect(status().isBadRequest());
        submit(caseId, """
                {"comment":"missing score"}
                """).andExpect(status().isBadRequest());

        assertThat(supportCases.findByCaseId(caseId).orElseThrow().getCsatScore()).isNull();
        assertThat(caseEvents.countByCaseIdAndEventType(caseId, "CSAT_RECORDED")).isZero();

        submit(caseId, """
                {"score":4}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csatScore").value(4))
                .andExpect(jsonPath("$.csatComment").doesNotExist())
                .andExpect(jsonPath("$.events[-1].note").doesNotExist());
    }

    @Test
    void slaBoardReportsOneDecimalAveragesAndNullForCategoriesWithoutScores()
            throws Exception {
        String complaintOne = open("corr-average-1", "COMPLAINT");
        String complaintTwo = open("corr-average-2", "COMPLAINT");
        String cardWithoutScore = open("corr-average-empty", "CARD_NOT_ARRIVED");
        close(complaintOne);
        close(complaintTwo);
        close(cardWithoutScore);

        submit(complaintOne, """
                {"score":4}
                """).andExpect(status().isOk());
        submit(complaintTwo, """
                {"score":5}
                """).andExpect(status().isOk());

        mvc.perform(get("/api/v1/support/sla"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csatByCategory.length()").value(6))
                .andExpect(jsonPath("$.csatByCategory[2].category")
                        .value("CARD_NOT_ARRIVED"))
                .andExpect(jsonPath("$.csatByCategory[2].averageScore").doesNotExist())
                .andExpect(jsonPath("$.csatByCategory[3].category").value("COMPLAINT"))
                .andExpect(jsonPath("$.csatByCategory[3].averageScore").value(4.5));
    }

    @Test
    void csatEndpointIsPublishedInOpenApiAndMissingCasesAre404() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/support/cases/{caseId}/csat'].post").exists());

        submit("case-missing", """
                {"score":5}
                """).andExpect(status().isNotFound());
    }

    private String open(String correlationId, String category) throws Exception {
        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_CASE.formatted(correlationId, category)))
                .andExpect(status().isAccepted());
        return supportCases.findByCorrelationId(correlationId).orElseThrow().getCaseId();
    }

    private void close(String caseId) {
        jdbc.update(
                "update support_case set status = 'CLOSED', closed_at = CURRENT_TIMESTAMP "
                        + "where case_id = ?",
                caseId);
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            String caseId,
            String body) throws Exception {
        return mvc.perform(post("/api/v1/support/cases/{caseId}/csat", caseId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
