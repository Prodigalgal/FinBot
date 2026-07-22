package io.omnnu.finbot.application.research.port.out;

import io.omnnu.finbot.application.research.dto.ResearchHistoryDetail;
import io.omnnu.finbot.application.research.dto.ResearchReplaySource;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.util.List;
import java.util.Optional;

public interface ResearchHistoryRepository {
    List<ResearchHistoryDetail.Summary> list(WorkflowRunStatus status, int limit);

    Optional<ResearchHistoryDetail> find(WorkflowRunId runId);

    Optional<ResearchReplaySource> replaySource(WorkflowRunId runId);
}
