package com.neobank.module.support.api;

/** One tile on the SLA & Breach view (UC 04) — open and breached totals for a single priority. */
public record SlaPriorityCount(
        String priority,
        int open,
        int breached) {
}
