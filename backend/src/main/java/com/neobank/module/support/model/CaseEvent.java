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

    @Column
    @Lob
    private String note;

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
        event.createdAt = at;
        return event;
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
}
