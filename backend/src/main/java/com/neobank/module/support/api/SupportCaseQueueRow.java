package com.neobank.module.support.api;

import java.time.Instant;

public record SupportCaseQueueRow(
        String caseId,
        String status,
        String category,
        String priority,
        boolean breached,
        double overdueHours,
        String applicationId,
        Instant slaDeadline,
        Instant openedAt) {
}
