package io.omnnu.finbot.application.experiment;

public record SaveAiExperimentCommand(
        String experimentId,
        String displayName,
        AiExperimentStatus status,
        String controlWorkflowVersionId,
        String candidateWorkflowVersionId,
        int candidateAllocationBasisPoints,
        String evaluationMetric,
        int minimumSampleSize,
        Long expectedVersion) {
}
