package com.neobank.module.support.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CaseConfigVersionView(
        int version,
        List<String> categories,
        Map<String, String> priorityMap,
        Map<String, Integer> slaHours,
        Map<String, List<String>> keywordMap,
        Instant effectiveFrom,
        boolean current) {
}
