package com.neobank.module.support.api;

import java.util.List;

public record CategorySuggestion(
        String category,
        int score,
        List<String> matchedKeywords) {
}
