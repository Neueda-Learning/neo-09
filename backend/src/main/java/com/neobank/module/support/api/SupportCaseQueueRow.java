package com.neobank.module.support.api;

public record SupportCaseQueueRow(
        String caseId,
        String status,
        String category,
        String priority,
        boolean breached,
        double overdueHours,
        String applicationId) {
}