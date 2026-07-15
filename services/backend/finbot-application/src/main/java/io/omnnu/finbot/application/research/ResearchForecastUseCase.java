package io.omnnu.finbot.application.research;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;
import java.util.Optional;

public interface ResearchForecastUseCase {
    Optional<ResearchForecastView> findByRun(WorkflowRunId workflowRunId);

    List<ResearchForecastView> recent(int limit);
}
