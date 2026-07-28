package com.neobank.module.support.api;

import com.neobank.module.support.model.SupportCase;
import java.time.Instant;

public record SupportCaseView(
        String caseId,
        String applicationId,
        String status,
        String category,
        String channel,
        String priority,
        Instant slaDeadline,
        String description,
        Instant openedAt,
        Integer configVersion) {

    public static SupportCaseView of(SupportCase supportCase) {
        return new SupportCaseView(
                supportCase.getCaseId(),
                supportCase.getApplicationId(),
                supportCase.getStatus(),
                supportCase.getCategory(),
                supportCase.getChannel(),
                supportCase.getPriority(),
                supportCase.getSlaDeadline(),
                supportCase.getDescription(),
                supportCase.getOpenedAt(),
                supportCase.getConfigVersion());
    }
}
