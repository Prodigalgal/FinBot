package io.omnnu.finbot.api.workflow.dto;

import io.omnnu.finbot.application.workflow.dto.WorkflowDefinitionSummary;
import io.omnnu.finbot.application.workflow.service.ExecutableWorkflowSchema;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.debate.CritiqueAssignmentPolicy;
import io.omnnu.finbot.domain.debate.DebateProtocol;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplate;
import io.omnnu.finbot.domain.workflow.BooleanConditionOperand;
import io.omnnu.finbot.domain.workflow.DecimalConditionOperand;
import io.omnnu.finbot.domain.workflow.TextConditionOperand;
import io.omnnu.finbot.domain.workflow.TextListConditionOperand;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCondition;
import io.omnnu.finbot.domain.workflow.WorkflowConditionOperator;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class WorkflowResponses {
    private WorkflowResponses() {
    }

    public record DefinitionSummaryResponse(
            String definitionId,
            String name,
            String description,
            boolean builtIn,
            boolean active,
            String publishedVersionId,
            Integer publishedVersionNumber,
            String draftVersionId,
            Integer draftVersionNumber,
            Instant updatedAt) {
        public static DefinitionSummaryResponse from(WorkflowDefinitionSummary definition) {
            return new DefinitionSummaryResponse(
                    definition.definitionId().value(), definition.name(), definition.description(),
                    definition.builtIn(), definition.active(),
                    definition.publishedVersionId() == null ? null : definition.publishedVersionId().value(),
                    definition.publishedVersionNumber(),
                    definition.draftVersionId() == null ? null : definition.draftVersionId().value(),
                    definition.draftVersionNumber(), definition.updatedAt());
        }
    }

    public record VersionResponse(
            String versionId,
            String definitionId,
            int versionNumber,
            WorkflowVersionStatus status,
            int defaultDebateRounds,
            DebateProtocolResponse debateProtocol,
            int maximumSteps,
            long maximumDurationSeconds,
            long maximumTokens,
            BigDecimal maximumCostUsd,
            WorkflowFailurePolicy failurePolicy,
            String checksum,
            Instant publishedAt,
            Instant createdAt,
            String createdBy,
            List<NodeResponse> nodes,
            List<EdgeResponse> edges) {
        public static VersionResponse from(WorkflowDefinitionVersion version) {
            return new VersionResponse(
                    version.versionId().value(), version.definitionId().value(), version.versionNumber(),
                    version.status(), version.defaultDebateRounds(),
                    DebateProtocolResponse.from(version), version.maximumSteps(),
                    version.maximumDuration().toSeconds(), version.maximumTokens(), version.maximumCostUsd(),
                    version.failurePolicy(), version.checksum(), version.publishedAt(), version.createdAt(),
                    version.createdBy(), version.nodes().stream().map(NodeResponse::from).toList(),
                    version.edges().stream().map(EdgeResponse::from).toList());
        }
    }

    public record DebateProtocolResponse(
            DebateProtocol protocol,
            int minimumParticipantSeats,
            int minimumQuorumRoles,
            long stageTimeoutSeconds,
            CritiqueAssignmentPolicy critiqueAssignmentPolicy) {
        static DebateProtocolResponse from(WorkflowDefinitionVersion version) {
            var configuration = version.debateProtocolConfiguration();
            return new DebateProtocolResponse(
                    configuration.protocol(),
                    configuration.minimumParticipantSeats(),
                    configuration.minimumQuorumRoles(),
                    configuration.stageTimeout().toSeconds(),
                    configuration.critiqueAssignmentPolicy());
        }
    }

    public record NodeResponse(
            String nodeId,
            WorkflowNodeType nodeType,
            String displayName,
            String roleName,
            String roleTemplateId,
            String logicalRoleKey,
            AiBindingResponse primaryAiBinding,
            AiBindingResponse fallbackAiBinding,
            String systemPrompt,
            String userPromptTemplate,
            WorkflowOutputContract outputContract,
            WorkflowContextMode contextMode,
            int contextHistoryRounds,
            int contextMaximumMessages,
            int maximumOutputTokens,
            int timeoutSeconds,
            int retryMaximumAttempts,
            long retryBackoffSeconds,
            String operation,
            BigDecimal positionX,
            BigDecimal positionY,
            boolean enabled) {
        static NodeResponse from(WorkflowNodeDefinition node) {
            return new NodeResponse(
                    node.nodeId().value(), node.nodeType(), node.displayName(), node.roleName(),
                    node.roleTemplateId() == null ? null : node.roleTemplateId().value(),
                    node.logicalRoleKey() == null ? null : node.logicalRoleKey().value(),
                    AiBindingResponse.from(node.primaryAiBinding()),
                    AiBindingResponse.from(node.fallbackAiBinding()),
                    node.systemPrompt(), node.userPromptTemplate(),
                    node.outputContract(), node.contextMode(), node.contextHistoryRounds(),
                    node.contextMaximumMessages(), node.maximumOutputTokens(), node.timeoutSeconds(),
                    node.retryPolicy().maximumAttempts(), node.retryPolicy().backoff().toSeconds(),
                    node.operation(), node.position().x(), node.position().y(), node.enabled());
        }
    }

    public record AiBindingResponse(
            String providerProfileId,
            String modelName,
            ReasoningEffort reasoningEffort) {
        static AiBindingResponse from(AiModelBinding binding) {
            return binding == null ? null : new AiBindingResponse(
                    binding.providerProfileId().value(),
                    binding.modelName(),
                    binding.reasoningEffort());
        }
    }

    public record EdgeResponse(
            String edgeId,
            String sourceNodeId,
            String targetNodeId,
            WorkflowActivationMode activationMode,
            WorkflowEdgeContextMode contextMode,
            ConditionResponse condition,
            boolean loopEdge,
            Integer maximumTraversals) {
        static EdgeResponse from(WorkflowEdgeDefinition edge) {
            return new EdgeResponse(
                    edge.edgeId().value(), edge.sourceNodeId().value(), edge.targetNodeId().value(),
                    edge.activationMode(), edge.contextMode(),
                    edge.condition() == null ? null : ConditionResponse.from(edge.condition()),
                    edge.loopEdge(), edge.maximumTraversals());
        }
    }

    public record ConditionResponse(
            String field,
            WorkflowConditionOperator operator,
            OperandResponse operand) {
        static ConditionResponse from(WorkflowCondition condition) {
            return new ConditionResponse(
                    condition.field(), condition.operator(),
                    condition.operand() == null ? null : OperandResponse.from(condition.operand()));
        }
    }

    public record OperandResponse(
            WorkflowRequests.OperandType type,
            String textValue,
            BigDecimal decimalValue,
            Boolean booleanValue,
            List<String> textValues) {
        static OperandResponse from(io.omnnu.finbot.domain.workflow.ConditionOperand operand) {
            return switch (operand) {
                case TextConditionOperand value -> new OperandResponse(
                        WorkflowRequests.OperandType.TEXT, value.value(), null, null, null);
                case DecimalConditionOperand value -> new OperandResponse(
                        WorkflowRequests.OperandType.DECIMAL, null, value.value(), null, null);
                case BooleanConditionOperand value -> new OperandResponse(
                        WorkflowRequests.OperandType.BOOLEAN, null, null, value.value(), null);
                case TextListConditionOperand value -> new OperandResponse(
                        WorkflowRequests.OperandType.TEXT_LIST, null, null, null, value.values());
            };
        }
    }

    public record RoleResponse(
            String roleTemplateId,
            String displayName,
            String objective,
            String systemPrompt,
            String userPromptTemplate,
            WorkflowOutputContract outputContract,
            String defaultProviderProfileId,
            String defaultModelName,
            ReasoningEffort defaultReasoningEffort,
            boolean builtIn,
            long version,
            Instant createdAt,
            Instant updatedAt) {
        public static RoleResponse from(AgentRoleTemplate role) {
            return new RoleResponse(
                    role.roleTemplateId().value(), role.displayName(), role.objective(), role.systemPrompt(),
                    role.userPromptTemplate(), role.outputContract(), role.defaultProviderProfileId().value(),
                    role.defaultModelName(), role.defaultReasoningEffort(), role.builtIn(), role.version(),
                    role.createdAt(), role.updatedAt());
        }
    }

    public record SchemaResponse(
            List<WorkflowNodeType> nodeTypes,
            List<ReasoningEffort> reasoningEfforts,
            List<WorkflowContextMode> contextModes,
            List<WorkflowEdgeContextMode> edgeContextModes,
            List<WorkflowConditionOperator> conditionOperators,
            List<WorkflowOutputContract> outputContracts,
            List<WorkflowFailurePolicy> failurePolicies) {
        public static SchemaResponse current() {
            return new SchemaResponse(
                    ExecutableWorkflowSchema.nodeTypes(),
                    List.of(ReasoningEffort.values()),
                    List.of(WorkflowContextMode.values()),
                    List.of(WorkflowEdgeContextMode.values()),
                    List.of(WorkflowConditionOperator.values()),
                    List.of(WorkflowOutputContract.values()),
                    List.of(WorkflowFailurePolicy.values()));
        }
    }
}
