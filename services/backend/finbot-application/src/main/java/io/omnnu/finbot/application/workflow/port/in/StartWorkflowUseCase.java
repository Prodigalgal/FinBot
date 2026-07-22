package io.omnnu.finbot.application.workflow.port.in;

import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface StartWorkflowUseCase {
    CompletionStage<StartWorkflowResult> start(StartWorkflowCommand command);
}
