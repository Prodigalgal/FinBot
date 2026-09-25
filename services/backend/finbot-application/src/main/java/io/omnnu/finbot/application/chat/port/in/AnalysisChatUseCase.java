package io.omnnu.finbot.application.chat.port.in;

import io.omnnu.finbot.application.chat.dto.AnalysisChatSession;
import io.omnnu.finbot.application.chat.dto.AnalysisChatTurn;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface AnalysisChatUseCase {
    AnalysisChatSession create(String workflowVersionId);

    List<AnalysisChatSession> list(String beforeChatId, String search, int limit);

    AnalysisChatSession find(String chatId);

    List<AnalysisChatTurn> turns(String chatId, Integer beforeTurnNumber, int limit);

    CompletionStage<AnalysisChatTurn> send(String chatId, String message, String clientRequestKey);
}
