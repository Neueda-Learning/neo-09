package com.neobank.module.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Infrastructure beans. Just the HTTP client this module calls the orchestrator back with.
 *
 * <p>The thread pool the decision runs on is Spring Boot's own
 * {@code applicationTaskExecutor} — no bean needed here. Size and naming are properties:
 * {@code spring.task.execution.*} in {@code application.yml}.</p>
 */
@Configuration
public class AppConfig {

    /**
     * <p><b>Timeouts are not optional here.</b> A bare {@code RestClient} waits forever. The case
     * board fetches one applicant per visible row — up to ten calls at once — and each one holds a
     * request thread while it waits. Against a DB pool of three, an unresponsive orchestrator turns
     * a slow sidebar into a wedged module. Failing in seconds gives the operator a 503 they can act
     * on; waiting gives them a spinner that never resolves.</p>
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(5));
        return builder
                .requestFactory(ClientHttpRequestFactories.get(timeouts))
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
