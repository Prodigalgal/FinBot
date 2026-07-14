package io.omnnu.finbot.api.operations;

import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.TaskNotFoundException;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/operations/tasks")
public final class TaskController {
    private final BackgroundTaskCoordinator coordinator;

    public TaskController(BackgroundTaskCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @GetMapping
    public List<TaskResponse> list(
            @RequestParam(required = false) BackgroundTaskStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return coordinator.list(status, limit).stream().map(TaskResponse::from).toList();
    }

    @GetMapping("/{taskId}")
    public TaskResponse find(@PathVariable String taskId) {
        return coordinator.find(new BackgroundTaskId(taskId))
                .map(TaskResponse::from)
                .orElseThrow(() -> new TaskNotFoundException("后台任务不存在"));
    }
}
