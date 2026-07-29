package com.neobank.module.support.api;

import jakarta.validation.constraints.NotBlank;

public record CaseSupervisorRequest(
        @NotBlank String action,
        @NotBlank String reason,
        @NotBlank String supervisor,
        String assignee) {
}
