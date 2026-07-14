package io.omnnu.finbot.application.workflow;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface StartWorkflowUseCase {
    CompletionStage<StartWorkflowResult> start(StartWorkflowCommand command);
}
