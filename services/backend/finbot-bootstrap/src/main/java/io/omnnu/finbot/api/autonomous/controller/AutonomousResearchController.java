package io.omnnu.finbot.api.autonomous.controller;

import io.omnnu.finbot.api.operations.dto.TaskResponse;
import io.omnnu.finbot.application.autonomous.dto.AutonomousResearchStatus;
import io.omnnu.finbot.application.autonomous.port.in.AutonomousResearchUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/autonomous")
public final class AutonomousResearchController {
    private final AutonomousResearchUseCase useCase;

    public AutonomousResearchController(AutonomousResearchUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping
    public StatusResponse status() {
        return StatusResponse.from(useCase.status());
    }

    @PostMapping("/runs")
    public ResponseEntity<TaskResponse> trigger(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TriggerRequest request) {
        var task = TaskResponse.from(useCase.trigger(idempotencyKey, request.requestSummary()));
        return ResponseEntity.accepted()
                .location(URI.create("/api/v2/operations/tasks/" + task.taskId()))
                .body(task);
    }

    public record TriggerRequest(@Size(max = 1000) String requestSummary) {
    }

    public record StatusResponse(
            boolean enabled,
            boolean workerOnline,
            io.omnnu.finbot.application.operations.dto.OperationsOverview.Schedule schedule,
            TaskResponse activeTask,
            io.omnnu.finbot.application.research.dto.ResearchHistoryDetail.Summary latestRun,
            String latestConclusion,
            java.time.Instant generatedAt) {
        static StatusResponse from(AutonomousResearchStatus value) {
            return new StatusResponse(
                    value.enabled(),
                    value.workerOnline(),
                    value.schedule(),
                    value.activeTask() == null ? null : TaskResponse.from(value.activeTask()),
                    value.latestRun(),
                    value.latestConclusion(),
                    value.generatedAt());
        }
    }
}
