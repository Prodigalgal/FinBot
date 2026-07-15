package io.omnnu.finbot.application.autonomous;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.EnqueueTaskCommand;
import io.omnnu.finbot.application.operations.OperationsRepository;
import io.omnnu.finbot.application.operations.ScheduledResearchTaskPayload;
import io.omnnu.finbot.application.research.ResearchHistoryRepository;
import io.omnnu.finbot.application.shared.IdempotencyKeys;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public final class AutonomousResearchService implements AutonomousResearchUseCase {
    private static final String SCHEDULE_ID = "schedule_autonomous_research";

    private final OperationsRepository operations;
    private final BackgroundTaskCoordinator tasks;
    private final ResearchHistoryRepository history;
    private final Clock clock;

    public AutonomousResearchService(
            OperationsRepository operations,
            BackgroundTaskCoordinator tasks,
            ResearchHistoryRepository history,
            Clock clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AutonomousResearchStatus status() {
        var now = clock.instant();
        var overview = operations.overview(now);
        var schedule = overview.schedules().stream()
                .filter(value -> SCHEDULE_ID.equals(value.scheduleId()))
                .findFirst()
                .orElse(null);
        var activeTask = tasks.list(null, 200).stream()
                .filter(task -> task.taskType() == BackgroundTaskType.SCHEDULED_RESEARCH)
                .filter(task -> task.status() == BackgroundTaskStatus.PENDING
                        || task.status() == BackgroundTaskStatus.CLAIMED)
                .findFirst()
                .orElse(null);
        var latestRun = history.list(null, 200).stream()
                .filter(run -> run.workflowType() == WorkflowType.SCHEDULED_RESEARCH)
                .findFirst()
                .orElse(null);
        var latestConclusion = latestRun == null ? null : history.find(new WorkflowRunId(latestRun.runId()))
                .flatMap(detail -> detail.agentTurns().reversed().stream()
                        .filter(turn -> "CHAIR_VERDICT".equals(turn.messageType()))
                        .findFirst()
                        .map(turn -> turn.summary() == null || turn.summary().isBlank()
                                ? turn.argument() : turn.summary()))
                .orElse(null);
        var workerOnline = overview.workers().stream()
                .anyMatch(worker -> "RUNNING".equals(worker.status())
                        && worker.heartbeatAt() != null
                        && worker.heartbeatAt().isAfter(now.minus(Duration.ofMinutes(1))));
        return new AutonomousResearchStatus(
                schedule != null && schedule.enabled(),
                workerOnline,
                schedule,
                activeTask,
                latestRun,
                latestConclusion,
                now);
    }

    @Override
    public BackgroundTask trigger(String idempotencyKey, String requestSummary) {
        var normalizedSummary = requestSummary == null || requestSummary.isBlank()
                ? "执行手动触发的自动产品研究闭环"
                : requestSummary.strip();
        var operationKey = IdempotencyKeys.scoped("autonomous-research", idempotencyKey);
        return tasks.enqueue(new EnqueueTaskCommand(
                BackgroundTaskType.SCHEDULED_RESEARCH,
                operationKey,
                new ScheduledResearchTaskPayload(normalizedSummary),
                70,
                5,
                clock.instant()));
    }
}
