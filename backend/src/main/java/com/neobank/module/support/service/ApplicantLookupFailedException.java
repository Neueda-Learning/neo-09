package com.neobank.module.support.service;

public class ApplicantLookupFailedException extends RuntimeException {

    public ApplicantLookupFailedException(String message) {
        super(message);
    }
}