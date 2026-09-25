package io.omnnu.finbot.application.chat.dto;

import java.time.Instant;

public record AnalysisChatSession(
        String chatId,
        String title,
        String workflowVersionId,
        Instant createdAt,
        Instant updatedAt) {
}
