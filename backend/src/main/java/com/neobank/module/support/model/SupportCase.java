package com.neobank.module.support.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

    @Lob
    @Column(nullable = false, length = 65_535)
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

    @Column(length = 128)
    private String assignee;

    @Column(name = "paused_minutes", nullable = false)
    private int pausedMinutes;

    @Column(nullable = false)
    private boolean breached;

    @Lob
    @Column(name = "resolution_note", length = 65_535)
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "paused_since")
    private Instant pausedSince;

    @Column(name = "callback_sent", nullable = false)
    private boolean callbackSent;

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
        this.pausedMinutes = 0;
        this.breached = false;
        this.callbackSent = false;
    }

    public void price(String priority, Instant slaDeadline, int configVersion) {
        if (this.priority != null) {
            return;
        }
        this.priority = priority;
        this.slaDeadline = slaDeadline;
        this.configVersion = configVersion;
    }

    /**
     * Age escalation (UC 04, rule 1+2): raises the priority one level and recomputes the
     * deadline as openedAt + the new level's SLA hours + the total paused time so far.
     */
    public void escalate(String newPriority, Instant newDeadline) {
        this.priority = newPriority;
        this.slaDeadline = newDeadline;
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

    public String getAssignee() {
        return assignee;
    }

    public int getPausedMinutes() {
        return pausedMinutes;
    }

    public boolean isBreached() {
        return breached;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getPausedSince() {
        return pausedSince;
    }

    public boolean isCallbackSent() {
        return callbackSent;
    }

    public void markBreached(boolean breached) {
        this.breached = breached;
    }

    public void pickUp(String actor) {
        this.status = "OPEN";
        this.assignee = actor;
    }

    public void waitCustomer(Instant now) {
        this.status = "PENDING_CUSTOMER";
        this.pausedSince = now;
    }

    public void resume(Instant now) {
        long newlyPaused = pausedSince == null ? 0 : pausedMinutesBetween(pausedSince, now);
        this.pausedMinutes += (int) newlyPaused;
        if (this.slaDeadline != null) {
            this.slaDeadline = this.slaDeadline.plus(newlyPaused, ChronoUnit.MINUTES);
        }
        this.pausedSince = null;
        this.status = "OPEN";
    }

    public void resolve(String note, Instant now) {
        this.status = "RESOLVED";
        this.resolutionNote = note;
        this.resolvedAt = now;
        this.pausedSince = null;
    }

    public void close(Instant now) {
        this.status = "CLOSED";
        this.closedAt = now;
        this.pausedSince = null;
    }

    public void reassign(String newAssignee) {
        this.assignee = newAssignee;
    }

    public void reopen(Instant freshDeadline) {
        this.status = "OPEN";
        this.resolutionNote = null;
        this.resolvedAt = null;
        this.closedAt = null;
        this.pausedSince = null;
        this.slaDeadline = freshDeadline;
    }

    public void markCallbackSent() {
        this.callbackSent = true;
    }

    private static long pausedMinutesBetween(Instant from, Instant to) {
        long minutes = Duration.between(from, to).toMinutes();
        return Math.max(0, minutes);
    }
}
