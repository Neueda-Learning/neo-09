package com.neobank.module.support.api;

import java.util.List;

public record SupportCaseQueueResponse(
        int totalOpen,
        int breached,
        List<SupportCaseQueueRow> cases) {
}