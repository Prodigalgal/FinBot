package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.operations.runtime.UserSelectedResearchRunner;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.dto.ResearchTaskMode;
import io.omnnu.finbot.application.operations.dto.ScheduledResearchTaskPayload;
import io.omnnu.finbot.application.research.dto.ResearchPipelineRequest;
import io.omnnu.finbot.application.market.port.out.UserSelectedResearchScopeQuery;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.application.workflow.port.out.ActiveWorkflowQuery;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class ScheduledResearchTaskHandler implements BackgroundTaskHandler {
    private final UserSelectedResearchRunner research;
    private final ActiveWorkflowQuery activeWorkflows;
    private final UserSelectedResearchScopeQuery userScopes;

    public ScheduledResearchTaskHandler(
            UserSelectedResearchRunner research,
            ActiveWorkflowQuery activeWorkflows,
            UserSelectedResearchScopeQuery userScopes) {
        this.research = Objects.requireNonNull(research, "research");
        this.activeWorkflows = Objects.requireNonNull(activeWorkflows, "activeWorkflows");
        this.userScopes = Objects.requireNonNull(userScopes, "userScopes");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.SCHEDULED_RESEARCH;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof ScheduledResearchTaskPayload payload)) {
            throw new IllegalArgumentException("Scheduled research task has an invalid payload");
        }
        var workflows = activeWorkflows.activePublishedVersionIds();
        if (workflows.isEmpty()) return CompletableFuture.completedFuture(null);
        var scopes = userScopes.scopes();
        if (scopes.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("请先在默认自选列表中指定研究商品"));
        var requests = new java.util.ArrayList<ResearchPipelineRequest>();
        for (var scope : scopes) {
            for (var versionId : workflows) {
                    var workflowCommand = new StartWorkflowCommand(
                            WorkflowType.SCHEDULED_RESEARCH,
                            WorkflowTrigger.SCHEDULED,
                            versionId,
                            payload.requestSummary(),
                            IdempotencyKeys.scoped(
                                    "scheduled-workflow",
                                    task.idempotencyKey() + ':' + versionId.value() + ':' + scope.instrumentId().value()));
                    requests.add(new ResearchPipelineRequest(
                                    workflowCommand,
                                    ResearchTaskMode.STANDARD.forAttempt(task.attemptCount()),
                                    task.attemptCount(),
                                    task.maximumAttempts(), scope));
            }
        }
        return research.execute(requests);
    }
}
