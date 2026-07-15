package io.omnnu.finbot.application.experiment;

import java.util.List;

public interface AiExperimentUseCase {
    List<AiExperiment> list();

    AiExperiment save(SaveAiExperimentCommand command);
}
