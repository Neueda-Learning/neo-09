package com.neobank.module.support.api;

import jakarta.validation.constraints.NotBlank;

public record CaseTransitionRequest(
        @NotBlank String action,
        @NotBlank String actor,
        String note) {
}
