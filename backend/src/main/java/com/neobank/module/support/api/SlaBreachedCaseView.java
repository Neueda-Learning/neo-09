package com.neobank.module.support.api;

/** One row of the worst-first breach list on the SLA & Breach view (UC 04), capped at 10. */
public record SlaBreachedCaseView(
        String caseId,
        String priority,
        double overdueHours) {
}
