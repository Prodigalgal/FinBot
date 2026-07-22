package io.omnnu.finbot.application.experiment.port.out;

import io.omnnu.finbot.application.experiment.dto.AiExperiment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiExperimentRepository {
    List<AiExperiment> list();

    Optional<AiExperiment> find(String experimentId);

    AiExperiment create(AiExperiment experiment);

    Optional<AiExperiment> update(AiExperiment experiment, long expectedVersion, Instant updatedAt);

    boolean hasRunningOverlap(
            String excludedExperimentId,
            String controlWorkflowVersionId,
            String candidateWorkflowVersionId);
}
