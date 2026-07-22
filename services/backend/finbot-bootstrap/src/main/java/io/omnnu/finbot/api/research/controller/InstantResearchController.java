package io.omnnu.finbot.api.research.controller;

import io.omnnu.finbot.api.research.dto.InstantResearchRequest;
import io.omnnu.finbot.api.research.dto.InstantResearchResponse;

import io.omnnu.finbot.application.research.dto.ResearchLaunchResult;
import io.omnnu.finbot.application.research.port.in.ResearchLaunchUseCase;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.port.out.WorkflowRunQuery;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/research/instant")
public final class InstantResearchController {
    private final ResearchLaunchUseCase researchLaunch;
    private final WorkflowRunQuery workflowRuns;

    public InstantResearchController(
            ResearchLaunchUseCase researchLaunch,
            WorkflowRunQuery workflowRuns) {
        this.researchLaunch = Objects.requireNonNull(researchLaunch, "researchLaunch");
        this.workflowRuns = Objects.requireNonNull(workflowRuns, "workflowRuns");
    }

    @PostMapping
    public CompletionStage<ResponseEntity<InstantResearchResponse>> start(
            @RequestHeader("Idempotency-Key") String clientIdempotencyKey,
            @Valid @RequestBody InstantResearchRequest request) {
        var operationKey = IdempotencyKeys.scoped("instant-research", clientIdempotencyKey);
        var versionId = request.workflowVersionId() == null || request.workflowVersionId().isBlank()
                ? null
                : new WorkflowVersionId(request.workflowVersionId());
        var demoVersionId = request.demoWorkflowVersionId() == null
                        || request.demoWorkflowVersionId().isBlank()
                ? null
                : new WorkflowVersionId(request.demoWorkflowVersionId());
        var command = new StartWorkflowCommand(
                        WorkflowType.INSTANT_RESEARCH,
                        WorkflowTrigger.API,
                        versionId,
                        request.question(),
                        operationKey);
        return researchLaunch.launch(
                        command,
                        operationKey,
                        ResearchTaskMode.STANDARD,
                        null,
                        demoVersionId)
                .thenApply(this::acceptedResponse);
    }

    private ResponseEntity<InstantResearchResponse> acceptedResponse(ResearchLaunchResult launched) {
        var started = launched.workflow();
        var runId = started.runId().value();
        var statusUrl = "/api/v2/workflows/" + runId;
        var workflowStatus = workflowRuns.find(started.runId())
                .map(snapshot -> snapshot.status())
                .orElse(WorkflowRunStatus.ACCEPTED);
        var body = InstantResearchResponse.from(launched, workflowStatus);
        return ResponseEntity.accepted()
                .location(URI.create(statusUrl))
                .body(body);
    }
}
