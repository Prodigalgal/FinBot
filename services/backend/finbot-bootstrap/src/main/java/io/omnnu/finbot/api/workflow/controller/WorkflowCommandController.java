package io.omnnu.finbot.api.workflow.controller;

import io.omnnu.finbot.api.workflow.dto.StartWorkflowRequest;
import io.omnnu.finbot.api.workflow.dto.StartWorkflowResponse;
import io.omnnu.finbot.api.workflow.dto.WorkflowRunResponse;

import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.port.in.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.port.out.WorkflowRunQuery;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workflows")
public final class WorkflowCommandController {
    private final StartWorkflowUseCase startWorkflowUseCase;
    private final WorkflowRunQuery workflowRunQuery;

    public WorkflowCommandController(
            StartWorkflowUseCase startWorkflowUseCase,
            WorkflowRunQuery workflowRunQuery) {
        this.startWorkflowUseCase = Objects.requireNonNull(startWorkflowUseCase, "startWorkflowUseCase");
        this.workflowRunQuery = Objects.requireNonNull(workflowRunQuery, "workflowRunQuery");
    }

    @PostMapping
    public CompletionStage<ResponseEntity<StartWorkflowResponse>> start(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StartWorkflowRequest request) {
        var command = new StartWorkflowCommand(
                request.workflowType(),
                request.trigger(),
                request.workflowVersionId() == null || request.workflowVersionId().isBlank()
                        ? null
                        : new WorkflowVersionId(request.workflowVersionId()),
                request.requestSummary(),
                IdempotencyKeys.scoped("workflow-start", idempotencyKey));
        return startWorkflowUseCase.start(command).thenApply(result -> {
            var runId = result.runId().value();
            var statusUrl = "/api/v2/workflows/" + runId;
            return ResponseEntity.accepted()
                    .location(URI.create(statusUrl))
                    .body(new StartWorkflowResponse(
                            runId,
                            result.acceptedEventId().value(),
                            WorkflowRunStatus.ACCEPTED,
                            result.acceptedAt(),
                            statusUrl,
                            statusUrl + "/events"));
        });
    }

    @GetMapping("/{runId}")
    public ResponseEntity<WorkflowRunResponse> find(@PathVariable String runId) {
        return workflowRunQuery.find(new WorkflowRunId(runId))
                .map(WorkflowRunResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
