package com.neobank.module.support.service;

/**
 * The intake service receives only fields UC00 is allowed to persist. In particular, the
 * application and outputs payloads stop at the HTTP boundary.
 */
public record OpenCaseCommand(
        String applicationId,
        String correlationId,
        String category,
        String description,
        String channel) {
}
