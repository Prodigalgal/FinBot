package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ResearchForecastRepository {
    Optional<ResearchForecastView> findByRun(WorkflowRunId workflowRunId);

    List<ResearchForecastView> recent(int limit);

    List<ForecastEvaluationCandidate> due(Instant now, int limit);

    boolean evaluate(String forecastId, Instant evaluatedAt);
}
