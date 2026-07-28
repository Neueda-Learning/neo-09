package com.neobank.module.support.api;

public record OpenCaseAcknowledgement(
        String status,
        String applicationId,
        String command) {

    public static OpenCaseAcknowledgement accepted(OpenCaseRequest request) {
        return new OpenCaseAcknowledgement(
                "in-progress", request.applicationId(), request.command());
    }
}
