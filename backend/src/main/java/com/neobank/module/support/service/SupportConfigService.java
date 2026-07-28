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
import java.util.Map;
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

    private final CaseConfigRepository configs;
    private final ObjectMapper objectMapper;

    public SupportConfigService(CaseConfigRepository configs, ObjectMapper objectMapper) {
        this.configs = configs;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CaseConfigVersionCreated createVersion(CaseConfigUpsertRequest request) {
        validate(request);

        int nextVersion = configs.findTopByOrderByVersionDesc()
                .map(c -> c.getVersion() + 1)
                .orElse(1);

        CaseConfig config = CaseConfig.create(
                nextVersion,
                writeJson(normalizeCategories(request.categories())),
                writeJson(new LinkedHashMap<>(request.priorityMap())),
            writeJson(new LinkedHashMap<>(request.slaHours())));

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
                    row.getEffectiveFrom(),
                    row.getVersion() == currentVersion));
        }
        return result;
    }

    private void validate(CaseConfigUpsertRequest request) {
        List<String> errors = new ArrayList<>();
        List<String> categories = normalizeCategories(request.categories());

        Set<String> unique = new LinkedHashSet<>(categories);
        if (unique.size() != categories.size()) {
            errors.add("categories must be unique");
        }

        for (String category : categories) {
            if (!UPPER_SNAKE.matcher(category).matches()) {
                errors.add("categories must use UPPER_SNAKE");
                break;
            }
        }

        Set<String> categorySet = new LinkedHashSet<>(categories);
        Map<String, String> priorityMap = request.priorityMap();
        for (String category : categorySet) {
            if (!priorityMap.containsKey(category)) {
                errors.add("priorityMap is missing category " + category);
            }
        }
        for (String mapped : priorityMap.keySet()) {
            if (!categorySet.contains(mapped)) {
                errors.add("priorityMap has unknown category " + mapped);
            }
        }
        for (Map.Entry<String, String> entry : priorityMap.entrySet()) {
            if (!PRIORITIES.contains(entry.getValue())) {
                errors.add("priorityMap value must be one of P1/P2/P3");
                break;
            }
        }

        Map<String, Integer> sla = request.slaHours();
        if (!sla.keySet().equals(PRIORITIES)) {
            errors.add("slaHours must contain exactly P1, P2 and P3");
        } else {
            Integer p1 = sla.get("P1");
            Integer p2 = sla.get("P2");
            Integer p3 = sla.get("P3");
            if (p1 == null || p2 == null || p3 == null) {
                errors.add("slaHours must contain exactly P1, P2 and P3");
            } else {
                if (p1 <= 0 || p2 <= 0 || p3 <= 0) {
                    errors.add("slaHours values must be positive");
                }
                if (!(p1 < p2 && p2 < p3)) {
                    errors.add("slaHours must satisfy P1 < P2 < P3");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new InvalidCaseConfigException(String.join("; ", errors));
        }
    }

    private List<String> normalizeCategories(List<String> raw) {
        List<String> categories = new ArrayList<>(raw.size());
        for (String item : raw) {
            categories.add(item == null ? "" : item.trim());
        }
        return categories;
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
}
