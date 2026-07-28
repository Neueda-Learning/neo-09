package com.neobank.module.support.api;

import java.time.Instant;
import java.util.List;

public record SupportCaseDetailView(
        String caseId,
        String status,
        String category,
        String priority,
        Instant slaDeadline,
        boolean breached,
        String applicationId,
        Integer configVersion,
        List<SupportCaseEventView> events) {
}