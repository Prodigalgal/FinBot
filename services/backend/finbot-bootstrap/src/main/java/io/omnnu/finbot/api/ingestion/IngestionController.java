package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.api.operations.TaskResponse;
import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.ingestion.CreateSourceCommand;
import io.omnnu.finbot.application.ingestion.DeleteSourceCommand;
import io.omnnu.finbot.application.ingestion.UpdateSourceCommand;
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.EnqueueTaskCommand;
import io.omnnu.finbot.application.operations.IngestionTaskPayload;
import io.omnnu.finbot.application.shared.IdempotencyKeys;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/v2")
public final class IngestionController {
    private final IngestionUseCase ingestionUseCase;
    private final BackgroundTaskCoordinator taskCoordinator;

    public IngestionController(
            IngestionUseCase ingestionUseCase,
            BackgroundTaskCoordinator taskCoordinator) {
        this.ingestionUseCase = Objects.requireNonNull(ingestionUseCase, "ingestionUseCase");
        this.taskCoordinator = Objects.requireNonNull(taskCoordinator, "taskCoordinator");
    }

    @GetMapping("/sources")
    public List<SourceResponse> sources(
            @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return ingestionUseCase.listSources(enabledOnly).stream().map(SourceResponse::from).toList();
    }

    @PostMapping("/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceResponse createSource(@Valid @RequestBody SourceMutationRequest request) {
        return SourceResponse.from(ingestionUseCase.createSource(
                new CreateSourceCommand(request.toDefinition())));
    }

    @PutMapping("/sources/{sourceId}")
    public SourceResponse updateSource(
            @PathVariable String sourceId,
            @RequestParam long expectedVersion,
            @Valid @RequestBody SourceMutationRequest request) {
        return SourceResponse.from(ingestionUseCase.updateSource(new UpdateSourceCommand(
                new SourceId(sourceId),
                request.toDefinition(),
                expectedVersion)));
    }

    @DeleteMapping("/sources/{sourceId}")
    public ResponseEntity<Void> deleteSource(
            @PathVariable String sourceId,
            @RequestParam long expectedVersion) {
        ingestionUseCase.deleteSource(new DeleteSourceCommand(
                new SourceId(sourceId), expectedVersion));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/sources/{sourceId}/status")
    public SourceResponse setSourceStatus(
            @PathVariable String sourceId,
            @Valid @RequestBody UpdateSourceStatusRequest request) {
        return SourceResponse.from(ingestionUseCase.setSourceEnabled(
                new SourceId(sourceId),
                request.enabled(),
                request.expectedVersion()));
    }

    @GetMapping("/evidence/documents")
    public List<DocumentResponse> documents(
            @RequestParam(required = false) String sourceId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        var typedSourceId = sourceId == null ? null : new SourceId(sourceId);
        return ingestionUseCase.listRecentDocuments(typedSourceId, limit).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @GetMapping("/sources/{sourceId}/health")
    public SourceHealthResponse sourceHealth(@PathVariable String sourceId) {
        return SourceHealthResponse.from(ingestionUseCase.sourceHealth(new SourceId(sourceId)));
    }

    @PostMapping("/sources/{sourceId}/collect")
    public ResponseEntity<TaskResponse> collect(
            @PathVariable String sourceId,
            @RequestHeader("Idempotency-Key") String clientIdempotencyKey,
            @Valid @RequestBody CollectSourceRequest request) {
        var typedSourceId = new SourceId(sourceId);
        var workflowRunId = request.workflowRunId() == null || request.workflowRunId().isBlank()
                ? null
                : new WorkflowRunId(request.workflowRunId());
        var normalizedQuery = request.query().strip();
        var task = taskCoordinator.enqueue(new EnqueueTaskCommand(
                BackgroundTaskType.INGESTION,
                IdempotencyKeys.scoped(
                        "manual-ingestion:" + typedSourceId.value(),
                        clientIdempotencyKey),
                new IngestionTaskPayload(workflowRunId, typedSourceId, normalizedQuery),
                70,
                3,
                null));
        return ResponseEntity.accepted().body(TaskResponse.from(task));
    }

    @PostMapping("/sources/{sourceId}/test")
    public CompletionStage<SourceTestResponse> testSource(
            @PathVariable String sourceId,
            @Valid @RequestBody TestSourceRequest request) {
        return ingestionUseCase.testSource(
                        new SourceId(sourceId),
                        request.query().strip())
                .thenApply(SourceTestResponse::from);
    }
}
