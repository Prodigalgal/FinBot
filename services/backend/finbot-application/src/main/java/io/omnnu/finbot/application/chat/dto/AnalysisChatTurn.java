package io.omnnu.finbot.application.chat.dto;

import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.time.Instant;

public record AnalysisChatTurn(
        String turnId,
        String chatId,
        int turnNumber,
        String requestKey,
        String userMessage,
        String workflowPrompt,
        String workflowRunId,
        String taskId,
        WorkflowRunStatus workflowStatus,
        BackgroundTaskStatus taskStatus,
        String answerSummary,
        String answer,
        Instant createdAt) {
    public boolean finished() {
        if (taskStatus == BackgroundTaskStatus.FAILED
                || taskStatus == BackgroundTaskStatus.CANCELLED) {
            return true;
        }
        return taskStatus == BackgroundTaskStatus.COMPLETED
                && workflowStatus != null
                && workflowStatus.terminal();
    }
}
