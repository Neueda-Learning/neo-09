package com.neobank.module.support.api;

import java.time.Instant;

import com.neobank.module.support.model.CaseEvent;

public record SupportCaseEventView(
        String type,
        String actor,
        String note,
        Instant at) {

    public static SupportCaseEventView of(CaseEvent event) {
        return new SupportCaseEventView(
                event.getEventType(),
                event.getActor(),
                event.getNote(),
                event.getCreatedAt());
    }
}