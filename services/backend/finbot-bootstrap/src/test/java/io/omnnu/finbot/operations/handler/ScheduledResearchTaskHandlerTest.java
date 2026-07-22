package io.omnnu.finbot.operations.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.dto.ScheduledResearchTaskPayload;
import io.omnnu.finbot.application.research.dto.ResearchPipelineRequest;
import io.omnnu.finbot.application.research.port.in.ResearchPipelineUseCase;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ScheduledResearchTaskHandlerTest {
    @Test
    void startsOneIndependentRunForEachActivePublishedWorkflow() {
        var requests = new ArrayList<ResearchPipelineRequest>();
        ResearchPipelineUseCase pipeline = request -> {
            requests.add(request);
            var suffix = requests.size();
            return CompletableFuture.completedFuture(new StartWorkflowResult(
                    new WorkflowRunId("run_01j000000000" + suffix),
                    new WorkflowEventId("event_01j000000000" + suffix),
                    Instant.parse("2026-07-14T08:00:00Z")));
        };
        var handler = new ScheduledResearchTaskHandler(
                pipeline,
                () -> List.of(
                        new WorkflowVersionId("workflowversion_active_01"),
                        new WorkflowVersionId("workflowversion_active_02")));

        handler.handle(task()).toCompletableFuture().join();

        assertEquals(2, requests.size());
        assertEquals("workflowversion_active_01",
                requests.get(0).workflowCommand().workflowVersionId().value());
        assertEquals("workflowversion_active_02",
                requests.get(1).workflowCommand().workflowVersionId().value());
        assertNotEquals(
                requests.get(0).workflowCommand().idempotencyKey(),
                requests.get(1).workflowCommand().idempotencyKey());
    }

    @Test
    void completesWithoutResearchWhenNoWorkflowIsActive() {
        var invocationCount = new int[] {0};
        ResearchPipelineUseCase pipeline = request -> {
            invocationCount[0]++;
            return CompletableFuture.failedFuture(new AssertionError("pipeline must not run"));
        };
        var handler = new ScheduledResearchTaskHandler(pipeline, List::of);

        handler.handle(task()).toCompletableFuture().join();

        assertEquals(0, invocationCount[0]);
    }

    private static BackgroundTask task() {
        var now = Instant.parse("2026-07-14T08:00:00Z");
        return new BackgroundTask(
                new BackgroundTaskId("task_01j0000000001"),
                BackgroundTaskType.SCHEDULED_RESEARCH,
                BackgroundTaskStatus.CLAIMED,
                50,
                "scheduled-research:2026-07-14T08:00:00Z",
                new ScheduledResearchTaskPayload("Run scheduled product research"),
                1,
                3,
                now,
                now,
                now.plusSeconds(300),
                null,
                now,
                null,
                null,
                null,
                now,
                now);
    }
}
