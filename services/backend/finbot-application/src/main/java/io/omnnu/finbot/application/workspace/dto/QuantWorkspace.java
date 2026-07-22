package io.omnnu.finbot.application.workspace.dto;

import java.time.Instant;
import java.util.List;

public record QuantWorkspace(
        List<Run> runs,
        Instant generatedAt) {
    public QuantWorkspace {
        runs = List.copyOf(runs);
    }

    public record Run(
            String researchRunId,
            String workflowRunId,
            String requestSummary,
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
            Instant startedAt,
            Instant completedAt) {
    }
}
