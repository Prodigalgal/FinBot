package io.omnnu.finbot.application.review.port.in;

import io.omnnu.finbot.application.review.dto.ResearchComparison;
import io.omnnu.finbot.application.review.dto.ResearchEffectiveness;
import io.omnnu.finbot.application.review.dto.ResearchFeedback;
import io.omnnu.finbot.application.review.dto.ResearchFeedbackRating;

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
