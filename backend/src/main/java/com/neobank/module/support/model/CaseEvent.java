package com.neobank.module.support.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "case_event")
public class CaseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false, length = 64)
    private String caseId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 128)
    private String actor;

    @Lob
    @Column(length = 65_535)
    private String note;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", length = 32)
    private String toStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CaseEvent() {
        // JPA
    }

    public static CaseEvent opened(String caseId, String description, Instant at) {
        CaseEvent event = new CaseEvent();
        event.caseId = caseId;
        event.eventType = "CASE_OPENED";
        event.actor = "customer via orchestrator";
        event.note = description;
        event.fromStatus = null;
        event.toStatus = "NEW";
        event.createdAt = at;
        return event;
    }

    public static CaseEvent transition(
            String caseId,
            String type,
            String fromStatus,
            String toStatus,
            String actor,
            String note,
            Instant at) {
        CaseEvent event = new CaseEvent();
        event.caseId = caseId;
        event.eventType = type;
        event.actor = actor;
        event.note = note;
        event.fromStatus = fromStatus;
        event.toStatus = toStatus;
        event.createdAt = at;
        return event;
    }

    public static CaseEvent callbackSent(String caseId, String note, Instant at) {
        return transition(caseId, "CALLBACK_SENT", null, null, "SYSTEM", note, at);
    }

    public static CaseEvent priorityEscalated(
            String caseId, String fromPriority, String toPriority, Instant at) {
        return transition(
                caseId,
                "PRIORITY_ESCALATED",
                null,
                null,
                "SYSTEM",
                fromPriority + " -> " + toPriority,
                at);
    }

    public String getCaseId() {
        return caseId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActor() {
        return actor;
    }

    public String getNote() {
        return note;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
