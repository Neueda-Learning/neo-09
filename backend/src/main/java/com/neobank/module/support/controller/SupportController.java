package com.neobank.module.support.controller;

import com.neobank.module.support.api.OpenCaseAcknowledgement;
import com.neobank.module.support.api.OpenCaseRequest;
import com.neobank.module.support.api.SupportCaseView;
import com.neobank.module.support.service.OpenCaseCommand;
import com.neobank.module.support.service.SupportCaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    private final SupportCaseService supportCases;

    public SupportController(SupportCaseService supportCases) {
        this.supportCases = supportCases;
    }

    @PostMapping("/execute")
    public ResponseEntity<OpenCaseAcknowledgement> execute(
            @Valid @RequestBody OpenCaseRequest request) {
        supportCases.accept(new OpenCaseCommand(
                request.applicationId(),
                request.correlationId(),
                request.request().category(),
                request.request().description(),
                request.request().channel()));
        return ResponseEntity.accepted().body(OpenCaseAcknowledgement.accepted(request));
    }

    @GetMapping("/cases")
    public List<SupportCaseView> listCases() {
        return supportCases.listCases();
    }
}
