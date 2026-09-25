package io.omnnu.finbot.api.chat.dto;

import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.time.Instant;

public record AnalysisChatTurnResponse(
        String turnId,
        String chatId,
        int turnNumber,
        String userMessage,
        String workflowRunId,
        String taskId,
        WorkflowRunStatus workflowStatus,
        BackgroundTaskStatus taskStatus,
        String answerSummary,
        String answer,
        Instant createdAt) {
    public static AnalysisChatTurnResponse from(AnalysisChatTurn turn) {
        return new AnalysisChatTurnResponse(
                turn.turnId(), turn.chatId(), turn.turnNumber(), turn.userMessage(),
                turn.workflowRunId(), turn.taskId(), turn.workflowStatus(), turn.taskStatus(),
                turn.answerSummary(), turn.answer(), turn.createdAt());
    }
}
