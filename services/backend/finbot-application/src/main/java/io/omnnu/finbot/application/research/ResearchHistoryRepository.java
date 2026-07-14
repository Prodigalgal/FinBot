package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.util.List;
import java.util.Optional;

public interface ResearchHistoryRepository {
    List<ResearchHistoryDetail.Summary> list(WorkflowRunStatus status, int limit);

    Optional<ResearchHistoryDetail> find(WorkflowRunId runId);

    Optional<ResearchReplaySource> replaySource(WorkflowRunId runId);
}
