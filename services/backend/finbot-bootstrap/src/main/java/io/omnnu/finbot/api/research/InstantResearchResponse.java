package io.omnnu.finbot.api.research;

import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.application.research.ResearchLaunchResult;
import java.time.Instant;

public record InstantResearchResponse(
        String runId,
        WorkflowRunStatus workflowStatus,
        String taskId,
        BackgroundTaskStatus taskStatus,
        Instant acceptedAt,
        String statusUrl,
        String eventsUrl,
        String taskUrl) {
    public static InstantResearchResponse from(
            ResearchLaunchResult launched,
            WorkflowRunStatus workflowStatus) {
        var workflow = launched.workflow();
        var task = launched.task();
        var runId = workflow.runId().value();
        var statusUrl = "/api/v2/workflows/" + runId;
        return new InstantResearchResponse(
                runId,
                workflowStatus,
                task.taskId().value(),
                task.status(),
                workflow.acceptedAt(),
                statusUrl,
                statusUrl + "/events",
                "/api/v2/operations/tasks/" + task.taskId().value());
    }
}
