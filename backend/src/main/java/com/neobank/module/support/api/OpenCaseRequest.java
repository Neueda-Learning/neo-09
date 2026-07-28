package com.neobank.module.support.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.neobank.module.integrations.orchestrator.Application;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The v5 support envelope. The application and outputs arrive for wire compatibility but are
 * deliberately never passed into the domain service or persistence layer.
 */
public record OpenCaseRequest(
        @NotBlank String applicationId,
        @NotBlank String correlationId,
        @NotBlank @Pattern(regexp = "open-case", message = "must be open-case") String command,
        Application application,
        JsonNode outputs,
        @NotNull @Valid CaseDetails request) {

    public record CaseDetails(
            @NotBlank String category,
            @NotBlank String description,
            @NotBlank String channel) {
    }
}
