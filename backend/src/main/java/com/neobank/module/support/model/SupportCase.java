package com.neobank.module.support.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "support_case")
public class SupportCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, unique = true, length = 64)
    private String caseId;

    @Column(name = "application_id", nullable = false, length = 64)
    private String applicationId;

    @Column(name = "correlation_id", nullable = false, unique = true, length = 128)
    private String correlationId;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false)
    @Lob
    private String description;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 2)
    private String priority;

    @Column(name = "config_version")
    private Integer configVersion;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "sla_deadline")
    private Instant slaDeadline;

    protected SupportCase() {
        // JPA
    }

    public SupportCase(
            String caseId,
            String applicationId,
            String correlationId,
            String category,
            String description,
            String channel,
            Instant openedAt) {
        this.caseId = caseId;
        this.applicationId = applicationId;
        this.correlationId = correlationId;
        this.category = category;
        this.description = description;
        this.channel = channel;
        this.status = "NEW";
        this.openedAt = openedAt;
    }

    public void price(String priority, Instant slaDeadline, int configVersion) {
        if (this.priority != null) {
            return;
        }
        this.priority = priority;
        this.slaDeadline = slaDeadline;
        this.configVersion = configVersion;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public String getChannel() {
        return channel;
    }

    public String getStatus() {
        return status;
    }

    public String getPriority() {
        return priority;
    }

    public Integer getConfigVersion() {
        return configVersion;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getSlaDeadline() {
        return slaDeadline;
    }
}
