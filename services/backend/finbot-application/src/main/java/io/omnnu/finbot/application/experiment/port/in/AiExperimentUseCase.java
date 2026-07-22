package io.omnnu.finbot.application.experiment.port.in;

import io.omnnu.finbot.application.experiment.dto.AiExperiment;
import io.omnnu.finbot.application.experiment.dto.SaveAiExperimentCommand;

import java.util.List;

public interface AiExperimentUseCase {
    List<AiExperiment> list();

    AiExperiment save(SaveAiExperimentCommand command);
}
