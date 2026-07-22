package io.omnnu.finbot.application.workflow.port.out;

import io.omnnu.finbot.domain.workflow.AgentMessageContent;

public interface StructuredAiOutputParser {
    AgentMessageContent parseAgent(String output);

    AgentMessageContent parseChair(String output);
}
