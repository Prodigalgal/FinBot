package io.omnnu.finbot.application.review;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;

public interface ResearchReviewUseCase {
    ResearchComparison compare(WorkflowRunId leftRunId, WorkflowRunId rightRunId);

    List<ResearchFeedback> feedback(int limit);

    ResearchFeedback saveFeedback(
            WorkflowRunId workflowRunId,
            ResearchFeedbackRating rating,
            ResearchEffectiveness effectiveness,
            String note,
            Long expectedVersion);
}
