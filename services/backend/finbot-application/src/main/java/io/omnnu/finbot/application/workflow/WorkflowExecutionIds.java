package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.AgentMessageId;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class WorkflowExecutionIds {
    private WorkflowExecutionIds() {
    }

    static DebateId debate(WorkflowRunId runId) {
        return new DebateId(identifier("debate_", runId.value()));
    }

    static WorkflowCheckpointId checkpoint(
            WorkflowRunId runId,
            WorkflowNodeId nodeId,
            int roundIndex) {
        return new WorkflowCheckpointId(identifier(
                "checkpoint_",
                runId.value(),
                nodeId.value(),
                Integer.toString(roundIndex)));
    }

    static AgentMessageId message(
            WorkflowRunId runId,
            WorkflowNodeId nodeId,
            int roundIndex) {
        return new AgentMessageId(identifier(
                "message_",
                runId.value(),
                nodeId.value(),
                Integer.toString(roundIndex)));
    }

    private static String identifier(String prefix, String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return prefix + HexFormat.of().formatHex(digest.digest(), 0, 20);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
