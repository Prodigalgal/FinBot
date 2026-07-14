package io.omnnu.finbot.application.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkflowRunResumeServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:30:00Z");
    private static final WorkflowRunId RUN_ID = new WorkflowRunId("run_resume_test001");

    @Test
    void explicitlyTransitionsFailedRunToAcceptedBeforeItIsRequeued() {
        var status = new AtomicReference<>(WorkflowRunStatus.FAILED);
        WorkflowRunResumeStore store = (runId, resumedAt) -> {
            assertEquals(RUN_ID, runId);
            assertEquals(NOW, resumedAt);
            return status.compareAndSet(WorkflowRunStatus.FAILED, WorkflowRunStatus.ACCEPTED);
        };
        WorkflowRunQuery query = runId -> Optional.of(snapshot(runId, status.get()));
        var service = new WorkflowRunResumeService(
                store,
                query,
                Clock.fixed(NOW, ZoneOffset.UTC));

        service.resumeFailed(RUN_ID);
        service.resumeFailed(RUN_ID);

        assertEquals(WorkflowRunStatus.ACCEPTED, status.get());
    }

    private static WorkflowRunSnapshot snapshot(WorkflowRunId runId, WorkflowRunStatus status) {
        return new WorkflowRunSnapshot(
                runId,
                WorkflowType.INSTANT_RESEARCH,
                status,
                WorkflowTrigger.API,
                "Resume failed research",
                NOW.minusSeconds(60),
                NOW);
    }
}
