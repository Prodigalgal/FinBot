package io.omnnu.finbot.domain.workflow;

import java.time.Instant;

public sealed interface WorkflowEvent permits
        WorkflowAccepted,
        WorkflowStageStarted,
        WorkflowProgressed,
        AiTextChunkPublished,
        AgentMessagePublished,
        WorkflowCompleted,
        WorkflowFailed {

    WorkflowEventId eventId();

    WorkflowRunId runId();

    long sequence();

    Instant occurredAt();

    default String eventType() {
        return switch (this) {
            case WorkflowAccepted ignored -> "workflow.accepted";
            case WorkflowStageStarted ignored -> "workflow.stage.started";
            case WorkflowProgressed ignored -> "workflow.progressed";
            case AiTextChunkPublished ignored -> "workflow.ai.text.delta";
            case AgentMessagePublished ignored -> "workflow.agent.message";
            case WorkflowCompleted ignored -> "workflow.completed";
            case WorkflowFailed ignored -> "workflow.failed";
        };
    }
}
