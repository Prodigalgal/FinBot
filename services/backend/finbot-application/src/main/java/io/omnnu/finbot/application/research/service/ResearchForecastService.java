package io.omnnu.finbot.application.research.service;

import io.omnnu.finbot.application.research.dto.ResearchForecastView;
import io.omnnu.finbot.application.research.port.in.ResearchForecastUseCase;
import io.omnnu.finbot.application.research.port.out.ResearchForecastRepository;

import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ResearchForecastService implements ResearchForecastUseCase {
    private final ResearchForecastRepository repository;

    public ResearchForecastService(ResearchForecastRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public Optional<ResearchForecastView> findByRun(WorkflowRunId workflowRunId) {
        return repository.findByRun(workflowRunId);
    }

    @Override
    public List<ResearchForecastView> recent(int limit) {
        return repository.recent(Math.max(1, Math.min(limit, 200)));
    }
}
