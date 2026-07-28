package com.neobank.module.support.service;

import java.util.List;
import java.util.Map;

public class InvalidCaseConfigException extends RuntimeException {

    private final Map<String, List<String>> fieldErrors;

    public InvalidCaseConfigException(Map<String, List<String>> fieldErrors) {
        super(fieldErrors.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(message -> entry.getKey() + " " + message))
                .reduce((left, right) -> left + "; " + right)
                .orElse("configuration validation failed"));
        this.fieldErrors = fieldErrors;
    }

    public Map<String, List<String>> getFieldErrors() {
        return fieldErrors;
    }
}
