package com.neobank.module.support.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record CaseConfigUpsertRequest(
        @NotNull @NotEmpty List<String> categories,
        @NotNull @NotEmpty Map<String, String> priorityMap,
        @NotNull @NotEmpty Map<String, Integer> slaHours,
        Map<String, List<String>> keywordMap) {
}
