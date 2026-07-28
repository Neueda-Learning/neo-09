package com.neobank.module.support.api;

import java.time.Instant;
import java.util.List;

public record SupportCaseDetailView(
        String caseId,
        String status,
        String category,
        String channel,
        String priority,
        Instant slaDeadline,
        boolean breached,
        String applicationId,
        String correlationId,
        Integer configVersion,
        String description,
        String assignee,
        int pausedMinutes,
        String resolutionNote,
        Instant openedAt,
        Instant resolvedAt,
        Instant closedAt,
        List<SupportCaseEventView> events) {
}
