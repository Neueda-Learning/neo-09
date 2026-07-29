package com.neobank.module.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.support.repository.CaseConfigRepository;
import com.neobank.module.support.repository.CaseEventRepository;
import com.neobank.module.support.repository.SupportCaseRepository;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:supportsuggestiontest;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportCategorySuggestionFlowTest {

    @TestConfiguration
    static class SameThreadExecutor {
        @Bean(name = "applicationTaskExecutor")
        Executor applicationTaskExecutor() {
            return Runnable::run;
        }
    }

    private static final String OPEN_CASE = """
            {
              "applicationId": "app-suggestion",
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
                "category": "OTHER",
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
    CaseConfigRepository configs;

    @BeforeEach
    void clean() {
        caseEvents.deleteAll();
        supportCases.deleteAll();
        configs.deleteAllById(
                configs.findAllByOrderByVersionAsc().stream()
                        .filter(config -> config.getVersion() > 1)
                        .map(config -> config.getVersion())
                        .toList());
    }

    @Test
    void seedRanksCardNotArrivedFirstWithoutChangingTheCaseOrTimeline() throws Exception {
        deliver("corr-card-phrase", "My card never arrived in the post");
        var supportCase = supportCases.findByCorrelationId("corr-card-phrase").orElseThrow();

        String first = mvc.perform(post(suggestionPath(supportCase.getCaseId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("CARD_NOT_ARRIVED"))
                .andExpect(jsonPath("$[0].score").value(3))
                .andExpect(jsonPath("$[0].matchedKeywords[0]").value("card"))
                .andExpect(jsonPath("$[0].matchedKeywords[1]").value("arrived"))
                .andExpect(jsonPath("$[0].matchedKeywords[2]").value("post"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        mvc.perform(post(suggestionPath(supportCase.getCaseId())))
                .andExpect(status().isOk())
                .andExpect(content().json(first));

        var unchanged = supportCases.findByCaseId(supportCase.getCaseId()).orElseThrow();
        assertThat(unchanged.getCategory()).isEqualTo("OTHER");
        assertThat(unchanged.getPriority()).isEqualTo("P3");
        assertThat(caseEvents.findAllByCaseIdOrderByCreatedAtAscIdAsc(supportCase.getCaseId()))
                .hasSize(1);
    }

    @Test
    void noHitsAndNullCurrentKeywordMapReturnAnEmptyList() throws Exception {
        deliver("corr-no-hits", "The zephyr is mauve");
        String caseId = supportCases.findByCorrelationId("corr-no-hits")
                .orElseThrow()
                .getCaseId();

        mvc.perform(post(suggestionPath(caseId)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mvc.perform(post("/api/v1/support/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["APPLICATION_STATUS", "CARD_NOT_ARRIVED", "AGREEMENT_QUESTION", "DATA_CORRECTION", "COMPLAINT", "OTHER"],
                                  "priorityMap": {
                                    "APPLICATION_STATUS": "P3",
                                    "CARD_NOT_ARRIVED": "P2",
                                    "AGREEMENT_QUESTION": "P3",
                                    "DATA_CORRECTION": "P2",
                                    "COMPLAINT": "P1",
                                    "OTHER": "P3"
                                  },
                                  "slaHours": {"P1":4,"P2":24,"P3":72},
                                  "keywordMap": null
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post(suggestionPath(caseId)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/support/config/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].keywordMap").doesNotExist());
    }

    @Test
    void currentConfigControlsRankingAndAlphabeticallyBreaksTies() throws Exception {
        deliver("corr-current-config", "Please help with this");
        String caseId = supportCases.findByCorrelationId("corr-current-config")
                .orElseThrow()
                .getCaseId();

        mvc.perform(post("/api/v1/support/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["APPLICATION_STATUS", "CARD_NOT_ARRIVED", "AGREEMENT_QUESTION", "DATA_CORRECTION", "COMPLAINT", "OTHER"],
                                  "priorityMap": {
                                    "APPLICATION_STATUS": "P3",
                                    "CARD_NOT_ARRIVED": "P2",
                                    "AGREEMENT_QUESTION": "P3",
                                    "DATA_CORRECTION": "P2",
                                    "COMPLAINT": "P1",
                                    "OTHER": "P3"
                                  },
                                  "slaHours": {"P1":4,"P2":24,"P3":72},
                                  "keywordMap": {
                                    "COMPLAINT": ["help"],
                                    "APPLICATION_STATUS": ["help"]
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post(suggestionPath(caseId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("APPLICATION_STATUS"))
                .andExpect(jsonPath("$[0].score").value(1))
                .andExpect(jsonPath("$[1].category").value("COMPLAINT"))
                .andExpect(jsonPath("$[1].score").value(1));
    }

    @Test
    void validationRejectsUnknownCategoriesAndDuplicateKeywords() throws Exception {
        mvc.perform(post("/api/v1/support/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["OTHER"],
                                  "priorityMap": {"OTHER": "P3"},
                                  "slaHours": {"P1":4,"P2":24,"P3":72},
                                  "keywordMap": {
                                    "NOT_A_CATEGORY": ["word"],
                                    "OTHER": ["Help", " help "]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.keywordMap[0]")
                        .value("has unknown category NOT_A_CATEGORY"))
                .andExpect(jsonPath("$.fieldErrors.keywordMap[1]")
                        .value("keywords must be unique per category"));
    }

    @Test
    void suggestionEndpointIsPublishedInOpenApiAndMissingCaseIs404() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/support/cases/{caseId}/suggest-category'].post")
                        .exists());

        mvc.perform(post(suggestionPath("case-missing")))
                .andExpect(status().isNotFound());
    }

    private void deliver(String correlationId, String description) throws Exception {
        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_CASE.formatted(correlationId, description)))
                .andExpect(status().isAccepted());
    }

    private String suggestionPath(String caseId) {
        return "/api/v1/support/cases/" + caseId + "/suggest-category";
    }
}
