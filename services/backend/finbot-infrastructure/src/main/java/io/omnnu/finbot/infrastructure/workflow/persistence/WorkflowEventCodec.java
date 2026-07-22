package io.omnnu.finbot.infrastructure.workflow.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.domain.workflow.AgentMessagePublished;
import io.omnnu.finbot.domain.workflow.AiTextChunkPublished;
import io.omnnu.finbot.domain.workflow.WorkflowAccepted;
import io.omnnu.finbot.domain.workflow.WorkflowCompleted;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowFailed;
import io.omnnu.finbot.domain.workflow.WorkflowProgressed;
import io.omnnu.finbot.domain.workflow.WorkflowStageStarted;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowEventCodec {
    private final ObjectMapper objectMapper;

    public WorkflowEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encode(WorkflowEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode workflow event " + event.eventId().value(), exception);
        }
    }

    public WorkflowEvent decode(String eventType, String payload) {
        var targetType = switch (eventType) {
            case "workflow.accepted" -> WorkflowAccepted.class;
            case "workflow.stage.started" -> WorkflowStageStarted.class;
            case "workflow.progressed" -> WorkflowProgressed.class;
            case "workflow.ai.text.delta" -> AiTextChunkPublished.class;
            case "workflow.agent.message" -> AgentMessagePublished.class;
            case "workflow.completed" -> WorkflowCompleted.class;
            case "workflow.failed" -> WorkflowFailed.class;
            default -> throw new IllegalArgumentException("Unknown workflow event type: " + eventType);
        };
        try {
            return objectMapper.readValue(payload, targetType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode workflow event type " + eventType, exception);
        }
    }
}
