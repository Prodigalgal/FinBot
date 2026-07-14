package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.AgentMessageContent;

public interface StructuredAiOutputParser {
    AgentMessageContent parseAgent(String output);

    AgentMessageContent parseChair(String output);
}
