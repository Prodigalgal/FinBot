package io.omnnu.finbot.api.workflow;

import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
import io.omnnu.finbot.domain.workflow.BooleanConditionOperand;
import io.omnnu.finbot.domain.workflow.ConditionOperand;
import io.omnnu.finbot.domain.workflow.DecimalConditionOperand;
import io.omnnu.finbot.domain.workflow.TextConditionOperand;
import io.omnnu.finbot.domain.workflow.TextListConditionOperand;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowCondition;
import io.omnnu.finbot.domain.workflow.WorkflowConditionOperator;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeId;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

public final class WorkflowRequests {
    private WorkflowRequests() {
    }

    public record ActivationRequest(boolean active) {
    }

    public record SaveDraftRequest(
            @Size(max = 80) String definitionId,
            @Size(max = 80) String versionId,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @Min(1) @Max(8) int defaultDebateRounds,
            @Min(1) @Max(1000) int maximumSteps,
            @Min(10) @Max(86400) int maximumDurationSeconds,
            @Min(1000) @Max(10000000) long maximumTokens,
            @NotNull @DecimalMin("0") BigDecimal maximumCostUsd,
            @NotNull WorkflowFailurePolicy failurePolicy,
            @Size(min = 64, max = 64) String expectedChecksum,
            @NotEmpty List<@Valid NodeRequest> nodes,
            @NotNull List<@Valid EdgeRequest> edges) {
    }

    public record NodeRequest(
            @NotBlank @Size(max = 80) String nodeId,
            @NotNull WorkflowNodeType nodeType,
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 120) String roleName,
            @Size(max = 80) String roleTemplateId,
            @Valid AiBindingRequest primaryAiBinding,
            @Valid AiBindingRequest fallbackAiBinding,
            @Size(max = 32000) String systemPrompt,
            @Size(max = 32000) String userPromptTemplate,
            WorkflowOutputContract outputContract,
            @NotNull WorkflowContextMode contextMode,
            @Min(0) @Max(8) int contextHistoryRounds,
            @Min(0) @Max(64) int contextMaximumMessages,
            @Min(64) @Max(65536) int maximumOutputTokens,
            @Min(5) @Max(3600) int timeoutSeconds,
            @Min(1) @Max(5) int retryMaximumAttempts,
            @Min(0) @Max(300) int retryBackoffSeconds,
            @Size(max = 80) String operation,
            @NotNull BigDecimal positionX,
            @NotNull BigDecimal positionY,
            boolean enabled) {
        WorkflowNodeDefinition toDomain() {
            return new WorkflowNodeDefinition(
                    new WorkflowNodeId(nodeId),
                    nodeType,
                    displayName,
                    roleName,
                    roleTemplateId == null || roleTemplateId.isBlank()
                            ? null
                            : new AgentRoleTemplateId(roleTemplateId),
                    primaryAiBinding == null ? null : primaryAiBinding.toDomain(),
                    fallbackAiBinding == null ? null : fallbackAiBinding.toDomain(),
                    systemPrompt,
                    userPromptTemplate,
                    outputContract,
                    contextMode,
                    contextHistoryRounds,
                    contextMaximumMessages,
                    maximumOutputTokens,
                    timeoutSeconds,
                    new WorkflowRetryPolicy(retryMaximumAttempts, Duration.ofSeconds(retryBackoffSeconds)),
                    operation,
                    new WorkflowCanvasPosition(positionX, positionY),
                    enabled);
        }
    }

    public record AiBindingRequest(
            @NotBlank @Size(max = 80) String providerProfileId,
            @NotBlank @Size(max = 160) String modelName,
            @NotNull ReasoningEffort reasoningEffort) {
        AiModelBinding toDomain() {
            return new AiModelBinding(
                    new AiProviderProfileId(providerProfileId),
                    modelName,
                    reasoningEffort);
        }
    }

    public record EdgeRequest(
            @NotBlank @Size(max = 80) String edgeId,
            @NotBlank @Size(max = 80) String sourceNodeId,
            @NotBlank @Size(max = 80) String targetNodeId,
            @NotNull WorkflowActivationMode activationMode,
            @NotNull WorkflowEdgeContextMode contextMode,
            @Valid ConditionRequest condition,
            boolean loopEdge,
            @Min(1) @Max(8) Integer maximumTraversals) {
        WorkflowEdgeDefinition toDomain() {
            return new WorkflowEdgeDefinition(
                    new WorkflowEdgeId(edgeId),
                    new WorkflowNodeId(sourceNodeId),
                    new WorkflowNodeId(targetNodeId),
                    activationMode,
                    contextMode,
                    condition == null ? null : condition.toDomain(),
                    loopEdge,
                    maximumTraversals);
        }
    }

    public record ConditionRequest(
            @NotBlank @Size(max = 256) String field,
            @NotNull WorkflowConditionOperator operator,
            @Valid ConditionOperandRequest operand) {
        WorkflowCondition toDomain() {
            return new WorkflowCondition(field, operator, operand == null ? null : operand.toDomain());
        }
    }

    public enum OperandType {
        TEXT,
        DECIMAL,
        BOOLEAN,
        TEXT_LIST
    }

    public record ConditionOperandRequest(
            @NotNull OperandType type,
            String textValue,
            BigDecimal decimalValue,
            Boolean booleanValue,
            List<String> textValues) {
        ConditionOperand toDomain() {
            return switch (type) {
                case TEXT -> new TextConditionOperand(require(textValue, "textValue"));
                case DECIMAL -> new DecimalConditionOperand(require(decimalValue, "decimalValue"));
                case BOOLEAN -> new BooleanConditionOperand(require(booleanValue, "booleanValue"));
                case TEXT_LIST -> new TextListConditionOperand(require(textValues, "textValues"));
            };
        }

        private static <T> T require(T value, String fieldName) {
            if (value == null) {
                throw new IllegalArgumentException(fieldName + " is required for operand type");
            }
            return value;
        }
    }

    public record SaveRoleRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(max = 1000) String objective,
            @NotBlank @Size(max = 32000) String systemPrompt,
            @NotBlank @Size(max = 32000) String userPromptTemplate,
            @NotNull WorkflowOutputContract outputContract,
            @NotBlank @Size(max = 80) String defaultProviderProfileId,
            @NotBlank @Size(max = 160) String defaultModelName,
            @NotNull ReasoningEffort defaultReasoningEffort,
            @PositiveOrZero Long expectedVersion) {
    }
}
