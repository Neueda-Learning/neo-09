package com.neobank.module.support.controller;

import com.neobank.module.support.api.CaseConfigUpsertRequest;
import com.neobank.module.support.api.CaseConfigVersionCreated;
import com.neobank.module.support.api.CaseConfigVersionView;
import com.neobank.module.support.service.SupportConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupportConfigController {

    private final SupportConfigService configs;

    public SupportConfigController(SupportConfigService configs) {
        this.configs = configs;
    }

    @PostMapping({"/config", "/api/v1/support/config"})
    public ResponseEntity<CaseConfigVersionCreated> create(@Valid @RequestBody CaseConfigUpsertRequest request) {
        return ResponseEntity.status(201).body(configs.createVersion(request));
    }

    @GetMapping({"/config/versions", "/api/v1/support/config/versions"})
    public List<CaseConfigVersionView> versions() {
        return configs.listVersions();
    }
}
