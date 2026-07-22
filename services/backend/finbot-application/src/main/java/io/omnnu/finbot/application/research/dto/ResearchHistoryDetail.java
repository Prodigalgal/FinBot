package io.omnnu.finbot.application.research.dto;

import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ResearchHistoryDetail(
        Summary summary,
        List<Event> events,
        List<Checkpoint> checkpoints,
        List<AgentTurn> agentTurns,
        List<AiInvocation> aiInvocations,
        List<Artifact> artifacts,
        List<QuantRun> quantRuns,
        DebateProtocolTrace debateProtocol) {
    public ResearchHistoryDetail {
        events = List.copyOf(events);
        checkpoints = List.copyOf(checkpoints);
        agentTurns = List.copyOf(agentTurns);
        aiInvocations = List.copyOf(aiInvocations);
        artifacts = List.copyOf(artifacts);
        quantRuns = List.copyOf(quantRuns);
    }

    public record Summary(
            String runId,
            WorkflowType workflowType,
            WorkflowRunStatus status,
            WorkflowTrigger trigger,
            String requestSummary,
            String workflowVersionId,
            long inputTokens,
            long outputTokens,
            BigDecimal costUsd,
            Instant acceptedAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
    }

    public record Event(
            long sequence,
            String eventType,
            String payloadJson,
            Instant occurredAt) {
    }

    public record Checkpoint(
            String nodeId,
            String displayName,
            int round,
            int attempt,
            String status,
            String resultSummary,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt) {
    }

    public record AgentTurn(
            String messageId,
            String nodeId,
            String roleName,
            int round,
            int turnIndex,
            String messageType,
            String status,
            String summary,
            String argument,
            BigDecimal confidence,
            String claimsJson,
            String evidenceReferencesJson,
            String challengesJson,
            String revisionNotesJson,
            Instant createdAt) {
    }

    public record AiInvocation(
            String invocationId,
            String nodeId,
            String providerProfileId,
            String modelName,
            ReasoningEffort reasoningEffort,
            String status,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCostUsd,
            Long latencyMilliseconds,
            String finishReason,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {
    }

    public record Artifact(
            String artifactId,
            String artifactType,
            int schemaVersion,
            String contentHash,
            Instant createdAt) {
    }

    public record QuantRun(
            String researchRunId,
            String researchKind,
            String strategyId,
            String strategyVersion,
            String status,
            long observationCount,
            String resultFingerprint,
            String metricsJson,
            String errorCode,
            String errorMessage,
            Instant requestedAt,
            Instant completedAt) {
    }
}
