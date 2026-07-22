package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.domain.workflow.AgentMessageId;
import io.omnnu.finbot.domain.consensus.ConsensusBallotId;
import io.omnnu.finbot.domain.consensus.ConsensusDecisionId;
import io.omnnu.finbot.domain.debate.DebateArtifactId;
import io.omnnu.finbot.domain.debate.DebateCandidateId;
import io.omnnu.finbot.domain.debate.DebatePhaseId;
import io.omnnu.finbot.domain.debate.DebatePhaseType;
import io.omnnu.finbot.domain.debate.DebateTaskId;
import io.omnnu.finbot.domain.debate.DebateTaskVariant;
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

    static DebatePhaseId phase(DebateId debateId, int generation, DebatePhaseType phaseType) {
        return new DebatePhaseId(identifier(
                "phase_", debateId.value(), Integer.toString(generation), phaseType.name()));
    }

    static DebateTaskId debateTask(
            DebatePhaseId phaseId,
            WorkflowNodeId actorNodeId,
            String targetCandidateId,
            DebateTaskVariant variant) {
        return new DebateTaskId(identifier(
                "debate_task_",
                phaseId.value(),
                actorNodeId.value(),
                targetCandidateId == null ? "" : targetCandidateId,
                variant.name()));
    }

    static DebateArtifactId debateArtifact(DebateTaskId taskId) {
        return new DebateArtifactId(identifier("debate_artifact_", taskId.value()));
    }

    static DebateCandidateId candidate(DebateId debateId, WorkflowNodeId nodeId) {
        return new DebateCandidateId(identifier("candidate_", debateId.value(), nodeId.value()));
    }

    static String anonymousCandidateAlias(DebateId debateId, WorkflowNodeId nodeId) {
        return "candidate_" + digest(debateId.value(), nodeId.value()).substring(0, 12);
    }

    static ConsensusBallotId ballot(
            DebateId debateId,
            WorkflowNodeId actorNodeId,
            DebateTaskVariant variant) {
        return new ConsensusBallotId(identifier(
                "ballot_", debateId.value(), actorNodeId.value(), variant.name()));
    }

    static ConsensusDecisionId decision(DebateId debateId) {
        return new ConsensusDecisionId(identifier("decision_", debateId.value()));
    }

    static String sha256(String... parts) {
        return digest(parts);
    }

    private static String identifier(String prefix, String... parts) {
        return prefix + digest(parts).substring(0, 40);
    }

    private static String digest(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
