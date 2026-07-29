package com.neobank.module.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.support.repository.CaseConfigRepository;
import com.neobank.module.support.repository.SupportCaseRepository;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:supportconfigtest;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupportConfigFlowTest {

    @TestConfiguration
    static class SameThreadExecutor {
        @Bean(name = "applicationTaskExecutor")
        Executor applicationTaskExecutor() {
            return Runnable::run;
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SupportCaseRepository supportCases;

        @Autowired
        CaseConfigRepository caseConfigs;

        @BeforeEach
        void clean() {
                jdbc.update("delete from case_event");
                jdbc.update("delete from support_case");
                caseConfigs.deleteAllById(
                                caseConfigs.findAllByOrderByVersionAsc().stream()
                                                .filter(config -> config.getVersion() > 1)
                                                .map(config -> config.getVersion())
                                                .toList());
        }

    @Test
    void historyStartsFromSeedVersionAndFlagsCurrent() throws Exception {
        mvc.perform(get("/config/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].categories[0]").value("APPLICATION_STATUS"))
                .andExpect(jsonPath("$[0].priorityMap.COMPLAINT").value("P1"))
                .andExpect(jsonPath("$[0].slaHours.P1").value(4))
                .andExpect(jsonPath("$[0].keywordMap.CARD_NOT_ARRIVED[0]").value("card"))
                .andExpect(jsonPath("$[0].keywordMap.CARD_NOT_ARRIVED[2]").value("post"));
    }

    @Test
    void postConfigCreatesANewVersionAndHistoryIsOldestFirst() throws Exception {
        mvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["APPLICATION_STATUS", "CARD_NOT_ARRIVED", "AGREEMENT_QUESTION", "DATA_CORRECTION", "COMPLAINT", "OTHER", "FEE_DISPUTE"],
                                  "priorityMap": {
                                    "APPLICATION_STATUS": "P3",
                                    "CARD_NOT_ARRIVED": "P2",
                                    "AGREEMENT_QUESTION": "P3",
                                    "DATA_CORRECTION": "P2",
                                    "COMPLAINT": "P1",
                                    "OTHER": "P3",
                                    "FEE_DISPUTE": "P2"
                                  },
                                  "slaHours": {"P1":4,"P2":24,"P3":72}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/config/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].current").value(true))
                .andExpect(jsonPath("$[1].priorityMap.FEE_DISPUTE").value("P2"));
    }

    @Test
    void invalidConfigReturnsFieldLevelMessages() throws Exception {
        mvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["complaint", "OTHER"],
                                  "priorityMap": {"OTHER": "P3"},
                                  "slaHours": {"P1":24,"P2":4,"P3":72}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("categories")))
                .andExpect(jsonPath("$.message").value(containsString("priorityMap")))
                .andExpect(jsonPath("$.message").value(containsString("slaHours")))
                .andExpect(jsonPath("$.fieldErrors.categories[0]").value("must use UPPER_SNAKE"))
                .andExpect(jsonPath("$.fieldErrors.priorityMap[0]").value(
                        "is missing category complaint"))
                .andExpect(jsonPath("$.fieldErrors.slaHours[0]").value(
                        "must satisfy P1 < P2 < P3"));
    }

    @Test
    void repeatedIdenticalConfigReturnsTheSameVersionWithoutAnotherInsert() throws Exception {
        String request = """
                {
                  "categories": ["OTHER"],
                  "priorityMap": {"OTHER": "P3"},
                  "slaHours": {"P1":4,"P2":24,"P3":72}
                }
                """;

        mvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        assertThat(caseConfigs.findAllByOrderByVersionAsc())
                .extracting(config -> config.getVersion())
                .containsExactly(1, 2);
    }

    @Test
    void nextCaseUsesNewCurrentVersionForPricing() throws Exception {
        mvc.perform(post("/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categories": ["APPLICATION_STATUS", "CARD_NOT_ARRIVED", "AGREEMENT_QUESTION", "DATA_CORRECTION", "COMPLAINT", "OTHER", "FEE_DISPUTE"],
                                  "priorityMap": {
                                    "APPLICATION_STATUS": "P3",
                                    "CARD_NOT_ARRIVED": "P2",
                                    "AGREEMENT_QUESTION": "P3",
                                    "DATA_CORRECTION": "P2",
                                    "COMPLAINT": "P1",
                                    "OTHER": "P3",
                                    "FEE_DISPUTE": "P2"
                                  },
                                  "slaHours": {"P1":4,"P2":24,"P3":72}
                                }
                                """))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/support/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "app-fee-1",
                                  "correlationId": "corr-fee-1",
                                  "command": "open-case",
                                  "application": {"applicant": {"fullName": "N", "email": "n@test"}, "finances": {"annualIncome": 500000}},
                                  "outputs": {"approvedLimit": 1000, "apr": 19.9},
                                  "request": {
                                    "category": "FEE_DISPUTE",
                                    "description": "Card fee dispute",
                                    "channel": "WEB"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted());

        Integer version = supportCases.findByCorrelationId("corr-fee-1")
                .orElseThrow()
                .getConfigVersion();
        String priority = supportCases.findByCorrelationId("corr-fee-1")
                .orElseThrow()
                .getPriority();
        assertThat(version).isEqualTo(2);
        assertThat(priority).isEqualTo("P2");

        Duration deadlineWindow = Duration.between(
                supportCases.findByCorrelationId("corr-fee-1").orElseThrow().getOpenedAt(),
                supportCases.findByCorrelationId("corr-fee-1").orElseThrow().getSlaDeadline());
        assertThat(deadlineWindow).isEqualTo(Duration.ofHours(24));

        String category = jdbc.queryForObject(
                "select category from support_case where correlation_id = ?",
                String.class,
                "corr-fee-1");
        assertThat(category).isEqualTo("FEE_DISPUTE");
    }
}
