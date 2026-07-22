package io.omnnu.finbot.api.workflow.controller;

import io.omnnu.finbot.application.workflow.port.in.WorkflowDiagnosticsUseCase;
import io.omnnu.finbot.application.workflow.dto.WorkflowEstimate;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionPlan;
import io.omnnu.finbot.application.workflow.dto.WorkflowNodeTestResult;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/workflow-versions")
public final class WorkflowDiagnosticsController {
    private final WorkflowDiagnosticsUseCase useCase;

    public WorkflowDiagnosticsController(WorkflowDiagnosticsUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping("/{versionId}/estimate")
    public WorkflowEstimate estimate(@PathVariable String versionId) {
        return useCase.estimate(new WorkflowVersionId(versionId));
    }

    @GetMapping("/{versionId}/plan")
    public WorkflowExecutionPlan plan(@PathVariable String versionId) {
        return useCase.plan(new WorkflowVersionId(versionId));
    }

    @PostMapping("/{versionId}/nodes/{nodeId}/test")
    public CompletionStage<WorkflowNodeTestResult> testNode(
            @PathVariable String versionId,
            @PathVariable String nodeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody NodeTestRequest request) {
        return useCase.testNode(
                new WorkflowVersionId(versionId),
                new WorkflowNodeId(nodeId),
                request.userPrompt(),
                idempotencyKey);
    }

    public record NodeTestRequest(
            @NotBlank @Size(max = 20000) String userPrompt) {
    }
}
