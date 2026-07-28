package com.neobank.module.support.api;

import java.time.Instant;

import com.neobank.module.support.model.SupportCase;

public record SupportCaseView(
        String caseId,
        String applicationId,
        String status,
        String category,
        String channel,
        String priority,
        boolean breached,
        Instant slaDeadline,
        String description,
        Instant openedAt,
        Integer configVersion) {

    public static SupportCaseView of(SupportCase supportCase) {
        return of(supportCase, supportCase.isBreached());
    }

    public static SupportCaseView of(SupportCase supportCase, boolean breached) {
        return new SupportCaseView(
                supportCase.getCaseId(),
                supportCase.getApplicationId(),
                supportCase.getStatus(),
                supportCase.getCategory(),
                supportCase.getChannel(),
                supportCase.getPriority(),
                breached,
                supportCase.getSlaDeadline(),
                supportCase.getDescription(),
                supportCase.getOpenedAt(),
                supportCase.getConfigVersion());
    }
}
