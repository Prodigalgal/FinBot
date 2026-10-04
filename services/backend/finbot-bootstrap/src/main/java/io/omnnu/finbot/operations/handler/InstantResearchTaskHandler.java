package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.operations.runtime.UserSelectedResearchRunner;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.dto.InstantResearchTaskPayload;
import io.omnnu.finbot.application.research.dto.ResearchPipelineRequest;
import io.omnnu.finbot.application.market.port.out.UserSelectedResearchScopeQuery;
import io.omnnu.finbot.application.research.dto.ResearchExecutionScope;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class InstantResearchTaskHandler implements BackgroundTaskHandler {
    private final UserSelectedResearchRunner research;
    private final UserSelectedResearchScopeQuery userScopes;

    public InstantResearchTaskHandler(UserSelectedResearchRunner research, UserSelectedResearchScopeQuery userScopes) {
        this.research = Objects.requireNonNull(research, "research");
        this.userScopes = Objects.requireNonNull(userScopes, "userScopes");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.INSTANT_RESEARCH;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof InstantResearchTaskPayload payload)) {
            throw new IllegalArgumentException("Instant research task has an invalid payload");
        }
        if (payload.marketAnalysisScope() == null && payload.executionScope() == ResearchExecutionScope.FULL) {
            var scopes = userScopes.scopes();
            if (scopes.isEmpty()) return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("请先在默认自选列表中指定研究商品"));
            return research.execute(java.util.List.of(new ResearchPipelineRequest(
                    new StartWorkflowCommand(payload.workflowType(), payload.trigger(), payload.workflowVersionId(),
                            payload.question(), payload.workflowIdempotencyKey()),
                    payload.taskMode().forAttempt(task.attemptCount()), task.attemptCount(), task.maximumAttempts(),
                    scopes.getFirst(), payload.demoWorkflowVersionId(), payload.executionScope())));
        }
        var workflowCommand = new StartWorkflowCommand(
                payload.workflowType(),
                payload.trigger(),
                payload.workflowVersionId(),
                payload.question(),
                payload.workflowIdempotencyKey());
        return research.execute(java.util.List.of(new ResearchPipelineRequest(
                        workflowCommand,
                        payload.taskMode().forAttempt(task.attemptCount()),
                        task.attemptCount(),
                        task.maximumAttempts(),
                        payload.marketAnalysisScope(),
                        payload.demoWorkflowVersionId(),
                        payload.executionScope())));
    }
}
