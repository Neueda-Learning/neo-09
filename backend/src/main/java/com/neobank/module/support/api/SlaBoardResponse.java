package com.neobank.module.support.api;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /sla} response (UC 04) — per-priority open/breached tallies plus the worst
 * breaches, ≤10, worst (most overdue) first. An empty case book returns zeros, never a 500.
 */
public record SlaBoardResponse(
        Instant referenceNow,
        List<SlaPriorityCount> byPriority,
        List<SlaBreachedCaseView> breachedCases) {
}
