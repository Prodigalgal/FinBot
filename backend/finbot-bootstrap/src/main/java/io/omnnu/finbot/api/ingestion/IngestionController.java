package io.omnnu.finbot.api.ingestion;

import io.omnnu.finbot.api.operations.TaskResponse;
import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.EnqueueTaskCommand;
import io.omnnu.finbot.application.operations.IngestionTaskPayload;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public final class IngestionController {
    private final IngestionUseCase ingestionUseCase;
    private final BackgroundTaskCoordinator taskCoordinator;
    private final Clock clock;

    public IngestionController(
            IngestionUseCase ingestionUseCase,
            BackgroundTaskCoordinator taskCoordinator,
            Clock clock) {
        this.ingestionUseCase = Objects.requireNonNull(ingestionUseCase, "ingestionUseCase");
        this.taskCoordinator = Objects.requireNonNull(taskCoordinator, "taskCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping("/sources")
    public List<SourceResponse> sources(
            @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return ingestionUseCase.listSources(enabledOnly).stream().map(SourceResponse::from).toList();
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

    @PostMapping("/sources/{sourceId}/collect")
    public ResponseEntity<TaskResponse> collect(
            @PathVariable String sourceId,
            @Valid @RequestBody CollectSourceRequest request) {
        var typedSourceId = new SourceId(sourceId);
        var workflowRunId = request.workflowRunId() == null || request.workflowRunId().isBlank()
                ? null
                : new WorkflowRunId(request.workflowRunId());
        var normalizedQuery = request.query().strip();
        var timeBucket = clock.instant().getEpochSecond();
        var task = taskCoordinator.enqueue(new EnqueueTaskCommand(
                BackgroundTaskType.INGESTION,
                "manual-ingestion:" + typedSourceId.value() + ':'
                        + hash(normalizedQuery) + ':' + timeBucket,
                new IngestionTaskPayload(workflowRunId, typedSourceId, normalizedQuery),
                70,
                3,
                null));
        return ResponseEntity.accepted().body(TaskResponse.from(task));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
