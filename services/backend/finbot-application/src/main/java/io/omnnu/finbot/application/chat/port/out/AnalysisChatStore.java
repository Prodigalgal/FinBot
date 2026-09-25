package io.omnnu.finbot.application.chat.port.out;

import io.omnnu.finbot.application.chat.dto.AnalysisChatSession;
import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnalysisChatStore {
    AnalysisChatSession create(AnalysisChatSession session);

    List<AnalysisChatSession> list(String beforeChatId, String search, int limit);

    Optional<AnalysisChatSession> find(String chatId);

    List<AnalysisChatTurn> turns(String chatId, Integer beforeTurnNumber, int limit);

    AnalysisChatTurn reserve(
            String chatId,
            String turnId,
            String requestKey,
            String userMessage,
            String workflowPrompt,
            Instant createdAt);

    AnalysisChatTurn attach(String turnId, String workflowRunId, String taskId, Instant acceptedAt);
}
