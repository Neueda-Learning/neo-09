package com.neobank.module.support.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.support.api.CaseConfigUpsertRequest;
import com.neobank.module.support.api.CaseConfigVersionCreated;
import com.neobank.module.support.api.CaseConfigVersionView;
import com.neobank.module.support.model.CaseConfig;
import com.neobank.module.support.repository.CaseConfigRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupportConfigService {

    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    private static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3");
    private static final TypeReference<List<String>> CATEGORIES = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> PRIORITY_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Integer>> SLA_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, List<String>>> KEYWORD_MAP =
            new TypeReference<>() { };

    private final CaseConfigRepository configs;
    private final ObjectMapper objectMapper;

    public SupportConfigService(CaseConfigRepository configs, ObjectMapper objectMapper) {
        this.configs = configs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CaseConfigVersionCreated createVersion(CaseConfigUpsertRequest request) {
        validate(request);

        List<String> categories = normalizeCategories(request.categories());
        Map<String, String> priorityMap = normalizePriorityMap(categories, request.priorityMap());
        Map<String, Integer> slaHours = normalizeSlaHours(request.slaHours());
        Map<String, List<String>> keywordMap =
                normalizeKeywordMap(categories, request.keywordMap());

        String categoriesJson = writeJson(categories);
        String priorityMapJson = writeJson(priorityMap);
        String slaHoursJson = writeJson(slaHours);
        String keywordMapJson = keywordMap == null ? null : writeJson(keywordMap);

        CaseConfig current = configs.lockCurrent().orElse(null);
        if (current != null
                && readCategories(current.getCategoriesJson()).equals(categories)
                && readPriorityMap(current.getPriorityMapJson()).equals(priorityMap)
                && readSlaHours(current.getSlaHoursJson()).equals(slaHours)
                && Objects.equals(readKeywordMap(current.getKeywordMapJson()), keywordMap)) {
            return new CaseConfigVersionCreated(current.getVersion());
        }

        int nextVersion = current == null ? 1 : current.getVersion() + 1;
        CaseConfig config = CaseConfig.create(
                nextVersion, categoriesJson, priorityMapJson, slaHoursJson, keywordMapJson);
        configs.saveAndFlush(config);
        return new CaseConfigVersionCreated(nextVersion);
    }

    @Transactional(readOnly = true)
    public List<CaseConfigVersionView> listVersions() {
        List<CaseConfig> rows = configs.findAllByOrderByVersionAsc();
        if (rows.isEmpty()) {
            return List.of();
        }

        int currentVersion = rows.get(rows.size() - 1).getVersion();
        List<CaseConfigVersionView> result = new ArrayList<>(rows.size());
        for (CaseConfig row : rows) {
            result.add(new CaseConfigVersionView(
                    row.getVersion(),
                    readCategories(row.getCategoriesJson()),
                    readPriorityMap(row.getPriorityMapJson()),
                    readSlaHours(row.getSlaHoursJson()),
                    readKeywordMap(row.getKeywordMapJson()),
                    row.getEffectiveFrom(),
                    row.getVersion() == currentVersion));
        }
        return result;
    }

    private void validate(CaseConfigUpsertRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        List<String> categories = normalizeCategories(request.categories());

        Set<String> unique = new LinkedHashSet<>(categories);
        if (unique.size() != categories.size()) {
            addError(errors, "categories", "must be unique");
        }

        for (String category : categories) {
            if (!UPPER_SNAKE.matcher(category).matches()) {
                addError(errors, "categories", "must use UPPER_SNAKE");
                break;
            }
        }

        Set<String> categorySet = new LinkedHashSet<>(categories);
        Map<String, String> priorityMap = request.priorityMap();
        for (String category : categorySet) {
            if (!priorityMap.containsKey(category)) {
                addError(errors, "priorityMap", "is missing category " + category);
            }
        }
        for (String mapped : priorityMap.keySet()) {
            if (!categorySet.contains(mapped)) {
                addError(errors, "priorityMap", "has unknown category " + mapped);
            }
        }
        for (Map.Entry<String, String> entry : priorityMap.entrySet()) {
            if (!PRIORITIES.contains(entry.getValue())) {
                addError(errors, "priorityMap", "values must be one of P1, P2 or P3");
                break;
            }
        }

        Map<String, Integer> sla = request.slaHours();
        if (!sla.keySet().equals(PRIORITIES)) {
            addError(errors, "slaHours", "must contain exactly P1, P2 and P3");
        } else {
            Integer p1 = sla.get("P1");
            Integer p2 = sla.get("P2");
            Integer p3 = sla.get("P3");
            if (p1 == null || p2 == null || p3 == null) {
                addError(errors, "slaHours", "must contain exactly P1, P2 and P3");
            } else {
                if (p1 <= 0 || p2 <= 0 || p3 <= 0) {
                    addError(errors, "slaHours", "values must be positive");
                }
                if (!(p1 < p2 && p2 < p3)) {
                    addError(errors, "slaHours", "must satisfy P1 < P2 < P3");
                }
            }
        }

        Map<String, List<String>> keywordMap = request.keywordMap();
        if (keywordMap != null) {
            for (String mapped : keywordMap.keySet()) {
                if (!categorySet.contains(mapped)) {
                    addError(errors, "keywordMap", "has unknown category " + mapped);
                }
            }
            for (Map.Entry<String, List<String>> entry : keywordMap.entrySet()) {
                List<String> keywords = entry.getValue();
                if (keywords == null) {
                    addError(errors, "keywordMap", "keyword lists must not be null");
                    continue;
                }
                Set<String> normalized = new LinkedHashSet<>();
                for (String keyword : keywords) {
                    String value = keyword == null
                            ? ""
                            : keyword.trim().toLowerCase(Locale.ROOT);
                    if (value.isBlank()) {
                        addError(errors, "keywordMap", "keywords must not be blank");
                        break;
                    }
                    if (!normalized.add(value)) {
                        addError(errors, "keywordMap", "keywords must be unique per category");
                        break;
                    }
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidCaseConfigException(errors);
        }
    }

    private void addError(
            Map<String, List<String>> errors,
            String field,
            String message) {
        errors.computeIfAbsent(field, ignored -> new ArrayList<>()).add(message);
    }

    private List<String> normalizeCategories(List<String> raw) {
        List<String> categories = new ArrayList<>(raw.size());
        for (String item : raw) {
            categories.add(item == null ? "" : item.trim());
        }
        return categories;
    }

    private Map<String, String> normalizePriorityMap(
            List<String> categories,
            Map<String, String> raw) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String category : categories) {
            normalized.put(category, raw.get(category));
        }
        return normalized;
    }

    private Map<String, Integer> normalizeSlaHours(Map<String, Integer> raw) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        normalized.put("P1", raw.get("P1"));
        normalized.put("P2", raw.get("P2"));
        normalized.put("P3", raw.get("P3"));
        return normalized;
    }

    private Map<String, List<String>> normalizeKeywordMap(
            List<String> categories,
            Map<String, List<String>> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        for (String category : categories) {
            if (!raw.containsKey(category)) {
                continue;
            }
            List<String> keywords = new ArrayList<>();
            for (String keyword : raw.get(category)) {
                keywords.add(keyword.trim().toLowerCase(Locale.ROOT));
            }
            normalized.put(category, keywords);
        }
        return normalized;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("could not serialize config", ex);
        }
    }

    private List<String> readCategories(String value) {
        try {
            return objectMapper.readValue(value, CATEGORIES);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored categories_json is invalid", ex);
        }
    }

    private Map<String, String> readPriorityMap(String value) {
        try {
            return objectMapper.readValue(value, PRIORITY_MAP);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored priority_map_json is invalid", ex);
        }
    }

    private Map<String, Integer> readSlaHours(String value) {
        try {
            return objectMapper.readValue(value, SLA_MAP);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored sla_hours_json is invalid", ex);
        }
    }

    private Map<String, List<String>> readKeywordMap(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, KEYWORD_MAP);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored keyword_map_json is invalid", ex);
        }
    }
}
