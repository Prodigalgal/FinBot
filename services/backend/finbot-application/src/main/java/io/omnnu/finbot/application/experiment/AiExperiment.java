package io.omnnu.finbot.application.experiment;

import java.time.Instant;
import java.util.Objects;

public record AiExperiment(
        String experimentId,
        String displayName,
        AiExperimentStatus status,
        String controlWorkflowVersionId,
        String candidateWorkflowVersionId,
        int candidateAllocationBasisPoints,
        String evaluationMetric,
        int minimumSampleSize,
        long controlSampleCount,
        long candidateSampleCount,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public AiExperiment {
        experimentId = Objects.requireNonNull(experimentId, "experimentId");
        displayName = required(displayName, "displayName", 160);
        Objects.requireNonNull(status, "status");
        controlWorkflowVersionId = required(
                controlWorkflowVersionId, "controlWorkflowVersionId", 80);
        candidateWorkflowVersionId = required(
                candidateWorkflowVersionId, "candidateWorkflowVersionId", 80);
        evaluationMetric = required(evaluationMetric, "evaluationMetric", 80);
        if (controlWorkflowVersionId.equals(candidateWorkflowVersionId)) {
            throw new IllegalArgumentException("Experiment variants must use different workflow versions");
        }
        if (candidateAllocationBasisPoints < 1 || candidateAllocationBasisPoints > 9999) {
            throw new IllegalArgumentException("candidateAllocationBasisPoints must be between 1 and 9999");
        }
        if (minimumSampleSize < 2 || minimumSampleSize > 100_000) {
            throw new IllegalArgumentException("minimumSampleSize must be between 2 and 100000");
        }
        if (controlSampleCount < 0 || candidateSampleCount < 0 || version < 0) {
            throw new IllegalArgumentException("Experiment counters must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String required(String value, String name, int maximumLength) {
        var normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
