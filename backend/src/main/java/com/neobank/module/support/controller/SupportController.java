package com.neobank.module.support.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.support.api.OpenCaseAcknowledgement;
import com.neobank.module.support.api.OpenCaseRequest;
import com.neobank.module.support.api.CaseSupervisorRequest;
import com.neobank.module.support.api.CaseTransitionRequest;
import com.neobank.module.support.api.SlaBoardResponse;
import com.neobank.module.support.api.SupportCaseDetailView;
import com.neobank.module.support.api.SupportCaseQueueResponse;
import com.neobank.module.support.api.SupportCaseView;
import com.neobank.module.support.service.OpenCaseCommand;
import com.neobank.module.support.service.SupportCaseService;

import jakarta.validation.Valid;

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
    public List<SupportCaseView> searchCases(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "10") int limit) {
        return supportCases.searchCases(q, status, limit);
    }

    @GetMapping("/cases/queue")
    public SupportCaseQueueResponse queue() {
        return supportCases.queue();
    }

    @GetMapping("/sla")
    public SlaBoardResponse sla() {
        return supportCases.slaBoard();
    }

    @GetMapping("/cases/{caseId}")
    public SupportCaseDetailView getCase(@PathVariable String caseId) {
        return supportCases.getCase(caseId);
    }

    @PostMapping("/cases/{caseId}/transition")
    public SupportCaseDetailView transition(
            @PathVariable String caseId,
            @Valid @RequestBody CaseTransitionRequest request) {
        return supportCases.transition(caseId, request.action(), request.actor(), request.note());
    }

    @PostMapping("/cases/{caseId}/supervisor")
    public SupportCaseDetailView supervisorAction(
            @PathVariable String caseId,
            @Valid @RequestBody CaseSupervisorRequest request) {
        return supportCases.supervisorAction(
                caseId,
                request.action(),
                request.reason(),
                request.supervisor(),
                request.assignee());
    }

    @GetMapping("/cases/{caseId}/applicant")
    public Application applicant(@PathVariable String caseId) {
        return supportCases.applicant(caseId);
    }
}
