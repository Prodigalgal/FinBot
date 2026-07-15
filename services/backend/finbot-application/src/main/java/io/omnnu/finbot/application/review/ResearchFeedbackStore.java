package io.omnnu.finbot.application.review;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ResearchFeedbackStore {
    List<ResearchFeedback> list(int limit);

    Optional<ResearchFeedback> find(WorkflowRunId workflowRunId);

    ResearchFeedback save(
            String feedbackId,
            WorkflowRunId workflowRunId,
            ResearchFeedbackRating rating,
            ResearchEffectiveness effectiveness,
            String note,
            Long expectedVersion,
            Instant savedAt);
}
