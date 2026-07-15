package io.omnnu.finbot.api.research;

import io.omnnu.finbot.application.research.ResearchHistoryDetail;
import io.omnnu.finbot.application.research.ResearchHistoryRepository;
import io.omnnu.finbot.application.research.ResearchLaunchResult;
import io.omnnu.finbot.application.research.ResearchLaunchUseCase;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.shared.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.WorkflowNotFoundException;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/research/history")
public final class ResearchHistoryController {
    private final ResearchHistoryRepository history;
    private final ResearchLaunchUseCase researchLaunch;

    public ResearchHistoryController(
            ResearchHistoryRepository history,
            ResearchLaunchUseCase researchLaunch) {
        this.history = Objects.requireNonNull(history, "history");
        this.researchLaunch = Objects.requireNonNull(researchLaunch, "researchLaunch");
    }

    @GetMapping
    public List<ResearchHistoryDetail.Summary> list(
            @RequestParam(required = false) WorkflowRunStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return history.list(status, limit);
    }

    @GetMapping("/{runId}")
    public ResearchHistoryDetail detail(@PathVariable String runId) {
        return history.find(new WorkflowRunId(runId))
                .orElseThrow(() -> new WorkflowNotFoundException(runId));
    }

    @PostMapping("/{runId}/replay")
    public CompletionStage<ResponseEntity<InstantResearchResponse>> replay(
            @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String clientIdempotencyKey) {
        var source = source(runId);
        var operationKey = IdempotencyKeys.scoped(
                "research-replay",
                runId + ':' + clientIdempotencyKey);
        var command = new StartWorkflowCommand(
                source.workflowType(),
                WorkflowTrigger.RECOVERY,
                source.workflowVersionId(),
                source.requestSummary(),
                operationKey);
        return researchLaunch.launch(command, operationKey, ResearchTaskMode.STANDARD)
                .thenApply(launched -> response(launched, WorkflowRunStatus.ACCEPTED));
    }

    @PostMapping("/{runId}/resume")
    public CompletionStage<ResponseEntity<InstantResearchResponse>> resume(
            @PathVariable String runId,
            @RequestParam(required = false) String checkpointNodeId,
            @RequestHeader("Idempotency-Key") String clientIdempotencyKey) {
        var source = source(runId);
        if (source.status() != WorkflowRunStatus.FAILED) {
            throw new IllegalArgumentException("Only a FAILED research run can be resumed");
        }
        validateCheckpoint(runId, checkpointNodeId);
        var taskKey = IdempotencyKeys.scoped(
                "research-resume",
                runId + ':' + clientIdempotencyKey);
        var command = new StartWorkflowCommand(
                source.workflowType(),
                source.trigger(),
                source.workflowVersionId(),
                source.requestSummary(),
                source.workflowIdempotencyKey());
        return researchLaunch.launch(command, taskKey, ResearchTaskMode.RESUME_FAILED)
                .thenApply(launched -> response(launched, WorkflowRunStatus.ACCEPTED));
    }

    private void validateCheckpoint(String runId, String checkpointNodeId) {
        if (checkpointNodeId == null || checkpointNodeId.isBlank()) {
            return;
        }
        var detail = history.find(new WorkflowRunId(runId))
                .orElseThrow(() -> new WorkflowNotFoundException(runId));
        var resumable = detail.checkpoints().stream()
                .anyMatch(checkpoint -> checkpoint.nodeId().equals(checkpointNodeId.strip())
                        && "FAILED".equals(checkpoint.status()));
        if (!resumable) {
            throw new IllegalArgumentException(
                    "checkpointNodeId must identify a failed checkpoint in this run");
        }
    }

    private io.omnnu.finbot.application.research.ResearchReplaySource source(String runId) {
        return history.replaySource(new WorkflowRunId(runId))
                .orElseThrow(() -> new WorkflowNotFoundException(runId));
    }

    private static ResponseEntity<InstantResearchResponse> response(
            ResearchLaunchResult launched,
            WorkflowRunStatus status) {
        var body = InstantResearchResponse.from(launched, status);
        return ResponseEntity.accepted()
                .location(URI.create(body.statusUrl()))
                .body(body);
    }
}
