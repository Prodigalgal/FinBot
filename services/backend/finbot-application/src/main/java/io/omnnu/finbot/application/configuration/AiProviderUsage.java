package io.omnnu.finbot.application.configuration;

public record AiProviderUsage(
        long workflowNodeCount,
        long roleTemplateCount,
        long executionStageCount) {
    public static final AiProviderUsage NONE = new AiProviderUsage(0, 0, 0);

    public AiProviderUsage {
        if (workflowNodeCount < 0 || roleTemplateCount < 0 || executionStageCount < 0) {
            throw new IllegalArgumentException("Provider usage counts must not be negative");
        }
    }

    public long totalCount() {
        return workflowNodeCount + roleTemplateCount + executionStageCount;
    }

    public boolean inUse() {
        return totalCount() > 0;
    }
}
