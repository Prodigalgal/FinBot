package io.omnnu.finbot.infrastructure.workflow;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.workflow.WorkflowDefinitionSummary;
import io.omnnu.finbot.application.workflow.ActiveWorkflowQuery;
import io.omnnu.finbot.application.workflow.WorkflowManagementConflictException;
import io.omnnu.finbot.application.workflow.WorkflowManagementRepository;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplate;
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
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeId;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWorkflowManagementRepository implements WorkflowManagementRepository, ActiveWorkflowQuery {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowManagementRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowDefinitionSummary> listDefinitions() {
        return jdbcClient.sql("""
                select d.definition_id, d.name, d.description, d.built_in, d.active, d.updated_at,
                       published.version_id as published_version_id,
                       published.version_number as published_version_number,
                       draft.version_id as draft_version_id,
                       draft.version_number as draft_version_number
                from workflow_definition d
                left join lateral (
                  select version_id, version_number
                  from workflow_definition_version v
                  where v.definition_id = d.definition_id and v.status = 'PUBLISHED'
                  limit 1
                ) published on true
                left join lateral (
                  select version_id, version_number
                  from workflow_definition_version v
                  where v.definition_id = d.definition_id and v.status = 'DRAFT'
                  order by version_number desc
                  limit 1
                ) draft on true
                order by d.built_in desc, d.name, d.definition_id
                """)
                .query((resultSet, rowNumber) -> new WorkflowDefinitionSummary(
                        new WorkflowDefinitionId(resultSet.getString("definition_id")),
                        resultSet.getString("name"),
                        resultSet.getString("description"),
                        resultSet.getBoolean("built_in"),
                        resultSet.getBoolean("active"),
                        nullableVersionId(resultSet.getString("published_version_id")),
                        nullableInteger(resultSet, "published_version_number"),
                        nullableVersionId(resultSet.getString("draft_version_id")),
                        nullableInteger(resultSet, "draft_version_number"),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowVersionId> activePublishedVersionIds() {
        return jdbcClient.sql("""
                select v.version_id
                from workflow_definition d
                join workflow_definition_version v
                  on v.definition_id = d.definition_id and v.status = 'PUBLISHED'
                where d.active = true
                order by d.built_in desc, d.name, d.definition_id
                """)
                .query(String.class)
                .list()
                .stream()
                .map(WorkflowVersionId::new)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowDefinitionVersion> findVersion(WorkflowVersionId versionId) {
        var root = jdbcClient.sql("""
                select definition_id, version_number, status, default_debate_rounds,
                       maximum_steps, maximum_duration_seconds, maximum_tokens,
                       maximum_cost_usd, failure_policy, checksum, published_at,
                       created_at, created_by
                from workflow_definition_version where version_id = :versionId
                """)
                .param("versionId", versionId.value())
                .query((resultSet, rowNumber) -> versionRoot(resultSet))
                .optional();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        var nodes = jdbcClient.sql("""
                select node_id, node_type, display_name, role_name, role_template_id,
                       provider_profile_id, model_name, reasoning_effort,
                       fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
                       system_prompt,
                       user_prompt_template, output_contract, context_mode,
                       context_history_rounds, context_max_messages, maximum_output_tokens,
                       timeout_seconds, retry_max_attempts, retry_backoff_seconds,
                       operation, position_x, position_y, enabled
                from workflow_node_definition
                where version_id = :versionId
                order by id
                """)
                .param("versionId", versionId.value())
                .query((resultSet, rowNumber) -> node(resultSet))
                .list();
        var edges = jdbcClient.sql("""
                select edge_id, source_node_id, target_node_id, activation_mode,
                       context_mode, condition_field, condition_operator,
                       condition_value::text as condition_value, loop_edge, maximum_traversals
                from workflow_edge_definition
                where version_id = :versionId
                order by id
                """)
                .param("versionId", versionId.value())
                .query((resultSet, rowNumber) -> edge(resultSet))
                .list();
        var value = root.orElseThrow();
        return Optional.of(new WorkflowDefinitionVersion(
                versionId,
                value.definitionId(),
                value.versionNumber(),
                value.status(),
                value.defaultDebateRounds(),
                value.maximumSteps(),
                Duration.ofSeconds(value.maximumDurationSeconds()),
                value.maximumTokens(),
                value.maximumCostUsd(),
                value.failurePolicy(),
                value.checksum(),
                value.publishedAt(),
                value.createdAt(),
                value.createdBy(),
                nodes,
                edges));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowDefinitionVersion> listVersions(WorkflowDefinitionId definitionId) {
        return jdbcClient.sql("""
                select version_id from workflow_definition_version
                where definition_id = :definitionId
                order by version_number desc, id desc
                """)
                .param("definitionId", definitionId.value())
                .query(String.class)
                .list()
                .stream()
                .map(WorkflowVersionId::new)
                .map(this::findVersion)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowDefinitionVersion> findPublished(WorkflowDefinitionId definitionId) {
        return jdbcClient.sql("""
                select version_id from workflow_definition_version
                where definition_id = :definitionId and status = 'PUBLISHED'
                """)
                .param("definitionId", definitionId.value())
                .query(String.class)
                .optional()
                .flatMap(value -> findVersion(new WorkflowVersionId(value)));
    }

    @Override
    @Transactional
    public boolean setActive(WorkflowDefinitionId definitionId, boolean active, Instant updatedAt) {
        return jdbcClient.sql("""
                update workflow_definition
                set active = :active, updated_at = :updatedAt
                where definition_id = :definitionId and active <> :active
                """)
                .param("definitionId", definitionId.value())
                .param("active", active)
                .param("updatedAt", timestamp(updatedAt))
                .update() == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public int nextVersionNumber(WorkflowDefinitionId definitionId) {
        return jdbcClient.sql("""
                select coalesce(max(version_number), 0) + 1
                from workflow_definition_version where definition_id = :definitionId
                """)
                .param("definitionId", definitionId.value())
                .query(Integer.class)
                .single();
    }

    @Override
    @Transactional
    public WorkflowDefinitionVersion saveDraft(
            String name,
            String description,
            boolean builtIn,
            WorkflowDefinitionVersion version,
            String expectedChecksum,
            Instant updatedAt) {
        jdbcClient.sql("""
                insert into workflow_definition (
                  definition_id, name, description, built_in, created_at, updated_at
                ) values (
                  :definitionId, :name, :description, :builtIn, :updatedAt, :updatedAt
                ) on conflict (definition_id) do update
                set name = excluded.name,
                    description = excluded.description,
                    updated_at = excluded.updated_at
                """)
                .param("definitionId", version.definitionId().value())
                .param("name", name)
                .param("description", description)
                .param("builtIn", builtIn)
                .param("updatedAt", timestamp(updatedAt))
                .update();

        var exists = jdbcClient.sql("""
                select count(*) from workflow_definition_version where version_id = :versionId
                """)
                .param("versionId", version.versionId().value())
                .query(Integer.class)
                .single() == 1;
        if (exists) {
            if (expectedChecksum == null || expectedChecksum.isBlank()) {
                throw new WorkflowManagementConflictException("更新草稿需要 expectedChecksum");
            }
            var updated = jdbcClient.sql("""
                    update workflow_definition_version
                    set default_debate_rounds = :rounds,
                        maximum_steps = :maximumSteps,
                        maximum_duration_seconds = :maximumDuration,
                        maximum_tokens = :maximumTokens,
                        maximum_cost_usd = :maximumCost,
                        failure_policy = :failurePolicy,
                        checksum = :checksum
                    where version_id = :versionId
                      and status = 'DRAFT'
                      and checksum = :expectedChecksum
                    """)
                    .param("versionId", version.versionId().value())
                    .param("rounds", version.defaultDebateRounds())
                    .param("maximumSteps", version.maximumSteps())
                    .param("maximumDuration", version.maximumDuration().toSeconds())
                    .param("maximumTokens", version.maximumTokens())
                    .param("maximumCost", version.maximumCostUsd())
                    .param("failurePolicy", version.failurePolicy().name())
                    .param("checksum", version.checksum())
                    .param("expectedChecksum", expectedChecksum)
                    .update();
            if (updated != 1) {
                throw new WorkflowManagementConflictException("草稿已被修改，请刷新后重试");
            }
            jdbcClient.sql("delete from workflow_edge_definition where version_id = :versionId")
                    .param("versionId", version.versionId().value())
                    .update();
            jdbcClient.sql("delete from workflow_node_definition where version_id = :versionId")
                    .param("versionId", version.versionId().value())
                    .update();
        } else {
            jdbcClient.sql("""
                    insert into workflow_definition_version (
                      version_id, definition_id, version_number, status,
                      default_debate_rounds, maximum_steps, maximum_duration_seconds,
                      maximum_tokens, maximum_cost_usd, failure_policy, checksum,
                      published_at, created_at, created_by
                    ) values (
                      :versionId, :definitionId, :versionNumber, 'DRAFT',
                      :rounds, :maximumSteps, :maximumDuration,
                      :maximumTokens, :maximumCost, :failurePolicy, :checksum,
                      null, :createdAt, :createdBy
                    )
                    """)
                    .param("versionId", version.versionId().value())
                    .param("definitionId", version.definitionId().value())
                    .param("versionNumber", version.versionNumber())
                    .param("rounds", version.defaultDebateRounds())
                    .param("maximumSteps", version.maximumSteps())
                    .param("maximumDuration", version.maximumDuration().toSeconds())
                    .param("maximumTokens", version.maximumTokens())
                    .param("maximumCost", version.maximumCostUsd())
                    .param("failurePolicy", version.failurePolicy().name())
                    .param("checksum", version.checksum())
                    .param("createdAt", timestamp(version.createdAt()))
                    .param("createdBy", version.createdBy())
                    .update();
        }
        version.nodes().forEach(node -> insertNode(version.versionId(), node));
        version.edges().forEach(edge -> insertEdge(version.versionId(), edge));
        return findVersion(version.versionId()).orElseThrow();
    }

    @Override
    @Transactional
    public WorkflowDefinitionVersion publish(WorkflowVersionId versionId, Instant publishedAt) {
        var definitionId = jdbcClient.sql("""
                select definition_id from workflow_definition_version
                where version_id = :versionId and status = 'DRAFT'
                for update
                """)
                .param("versionId", versionId.value())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new WorkflowManagementConflictException("草稿不存在或已发布"));
        jdbcClient.sql("""
                update workflow_definition_version
                set status = 'ARCHIVED'
                where definition_id = :definitionId and status = 'PUBLISHED'
                """)
                .param("definitionId", definitionId)
                .update();
        var updated = jdbcClient.sql("""
                update workflow_definition_version
                set status = 'PUBLISHED', published_at = :publishedAt
                where version_id = :versionId and status = 'DRAFT'
                """)
                .param("versionId", versionId.value())
                .param("publishedAt", timestamp(publishedAt))
                .update();
        if (updated != 1) {
            throw new WorkflowManagementConflictException("工作流发布冲突");
        }
        return findVersion(versionId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRoleTemplate> listRoles() {
        return jdbcClient.sql("""
                select role_template_id, display_name, objective, system_prompt,
                       user_prompt_template, output_contract, default_provider_profile_id,
                       default_model_name, default_reasoning_effort, built_in, version,
                       created_at, updated_at
                from agent_role_template
                order by built_in desc, display_name, role_template_id
                """)
                .query((resultSet, rowNumber) -> role(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRoleTemplate> findRole(AgentRoleTemplateId roleTemplateId) {
        return jdbcClient.sql("""
                select role_template_id, display_name, objective, system_prompt,
                       user_prompt_template, output_contract, default_provider_profile_id,
                       default_model_name, default_reasoning_effort, built_in, version,
                       created_at, updated_at
                from agent_role_template where role_template_id = :roleTemplateId
                """)
                .param("roleTemplateId", roleTemplateId.value())
                .query((resultSet, rowNumber) -> role(resultSet))
                .optional();
    }

    @Override
    public AgentRoleTemplate createRole(AgentRoleTemplate role) {
        jdbcClient.sql("""
                insert into agent_role_template (
                  role_template_id, display_name, objective, system_prompt,
                  user_prompt_template, output_contract, default_provider_profile_id,
                  default_model_name, default_reasoning_effort, built_in, version,
                  created_at, updated_at
                ) values (
                  :roleTemplateId, :displayName, :objective, :systemPrompt,
                  :userPromptTemplate, :outputContract, :providerProfileId,
                  :modelName, :reasoningEffort, false, 0, :createdAt, :updatedAt
                )
                """)
                .param("roleTemplateId", role.roleTemplateId().value())
                .param("displayName", role.displayName())
                .param("objective", role.objective())
                .param("systemPrompt", role.systemPrompt())
                .param("userPromptTemplate", role.userPromptTemplate())
                .param("outputContract", role.outputContract().name())
                .param("providerProfileId", role.defaultProviderProfileId().value())
                .param("modelName", role.defaultModelName())
                .param("reasoningEffort", role.defaultReasoningEffort().name())
                .param("createdAt", timestamp(role.createdAt()))
                .param("updatedAt", timestamp(role.updatedAt()))
                .update();
        return findRole(role.roleTemplateId()).orElseThrow();
    }

    @Override
    public Optional<AgentRoleTemplate> updateRole(AgentRoleTemplate role, long expectedVersion) {
        var updated = jdbcClient.sql("""
                update agent_role_template
                set display_name = :displayName,
                    objective = :objective,
                    system_prompt = :systemPrompt,
                    user_prompt_template = :userPromptTemplate,
                    output_contract = :outputContract,
                    default_provider_profile_id = :providerProfileId,
                    default_model_name = :modelName,
                    default_reasoning_effort = :reasoningEffort,
                    version = version + 1,
                    updated_at = :updatedAt
                where role_template_id = :roleTemplateId
                  and built_in = false
                  and version = :expectedVersion
                """)
                .param("roleTemplateId", role.roleTemplateId().value())
                .param("displayName", role.displayName())
                .param("objective", role.objective())
                .param("systemPrompt", role.systemPrompt())
                .param("userPromptTemplate", role.userPromptTemplate())
                .param("outputContract", role.outputContract().name())
                .param("providerProfileId", role.defaultProviderProfileId().value())
                .param("modelName", role.defaultModelName())
                .param("reasoningEffort", role.defaultReasoningEffort().name())
                .param("updatedAt", timestamp(role.updatedAt()))
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1 ? findRole(role.roleTemplateId()) : Optional.empty();
    }

    @Override
    public boolean deleteRole(AgentRoleTemplateId roleTemplateId, long expectedVersion) {
        return jdbcClient.sql("""
                delete from agent_role_template
                where role_template_id = :roleTemplateId
                  and built_in = false
                  and version = :expectedVersion
                """)
                .param("roleTemplateId", roleTemplateId.value())
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private void insertNode(WorkflowVersionId versionId, WorkflowNodeDefinition node) {
        jdbcClient.sql("""
                insert into workflow_node_definition (
                  version_id, node_id, node_type, display_name, role_name, role_template_id,
                  provider_profile_id, model_name, reasoning_effort,
                  fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
                  system_prompt,
                  user_prompt_template, output_contract, context_mode, context_history_rounds,
                  context_max_messages, maximum_output_tokens, timeout_seconds,
                  retry_max_attempts, retry_backoff_seconds, operation,
                  position_x, position_y, enabled
                ) values (
                  :versionId, :nodeId, :nodeType, :displayName, :roleName, :roleTemplateId,
                  :providerProfileId, :modelName, :reasoningEffort,
                  :fallbackProviderProfileId, :fallbackModelName, :fallbackReasoningEffort,
                  :systemPrompt,
                  :userPromptTemplate, :outputContract, :contextMode, :historyRounds,
                  :maximumMessages, :maximumOutputTokens, :timeoutSeconds,
                  :retryAttempts, :retryBackoffSeconds, :operation,
                  :positionX, :positionY, :enabled
                )
                """)
                .param("versionId", versionId.value())
                .param("nodeId", node.nodeId().value())
                .param("nodeType", node.nodeType().name())
                .param("displayName", node.displayName())
                .param("roleName", node.roleName())
                .param("roleTemplateId", node.roleTemplateId() == null ? null : node.roleTemplateId().value())
                .param("providerProfileId", providerProfileId(node.primaryAiBinding()))
                .param("modelName", modelName(node.primaryAiBinding()))
                .param("reasoningEffort", reasoningEffort(node.primaryAiBinding()))
                .param("fallbackProviderProfileId", providerProfileId(node.fallbackAiBinding()))
                .param("fallbackModelName", modelName(node.fallbackAiBinding()))
                .param("fallbackReasoningEffort", reasoningEffort(node.fallbackAiBinding()))
                .param("systemPrompt", node.systemPrompt())
                .param("userPromptTemplate", node.userPromptTemplate())
                .param("outputContract", node.outputContract() == null ? null : node.outputContract().name())
                .param("contextMode", node.contextMode().name())
                .param("historyRounds", node.contextHistoryRounds())
                .param("maximumMessages", node.contextMaximumMessages())
                .param("maximumOutputTokens", node.maximumOutputTokens())
                .param("timeoutSeconds", node.timeoutSeconds())
                .param("retryAttempts", node.retryPolicy().maximumAttempts())
                .param("retryBackoffSeconds", node.retryPolicy().backoff().toSeconds())
                .param("operation", node.operation())
                .param("positionX", node.position().x())
                .param("positionY", node.position().y())
                .param("enabled", node.enabled())
                .update();
    }

    private void insertEdge(WorkflowVersionId versionId, WorkflowEdgeDefinition edge) {
        jdbcClient.sql("""
                insert into workflow_edge_definition (
                  version_id, edge_id, source_node_id, target_node_id, activation_mode,
                  context_mode, condition_field, condition_operator, condition_value,
                  loop_edge, maximum_traversals
                ) values (
                  :versionId, :edgeId, :sourceNodeId, :targetNodeId, :activationMode,
                  :contextMode, :conditionField, :conditionOperator, cast(:conditionValue as jsonb),
                  :loopEdge, :maximumTraversals
                )
                """)
                .param("versionId", versionId.value())
                .param("edgeId", edge.edgeId().value())
                .param("sourceNodeId", edge.sourceNodeId().value())
                .param("targetNodeId", edge.targetNodeId().value())
                .param("activationMode", edge.activationMode().name())
                .param("contextMode", edge.contextMode().name())
                .param("conditionField", edge.condition() == null ? null : edge.condition().field())
                .param("conditionOperator", edge.condition() == null ? null : edge.condition().operator().name())
                .param("conditionValue", conditionValue(edge.condition()))
                .param("loopEdge", edge.loopEdge())
                .param("maximumTraversals", edge.maximumTraversals())
                .update();
    }

    private WorkflowNodeDefinition node(ResultSet resultSet) throws SQLException {
        var providerId = resultSet.getString("provider_profile_id");
        var reasoning = resultSet.getString("reasoning_effort");
        var fallbackProviderId = resultSet.getString("fallback_provider_profile_id");
        var fallbackReasoning = resultSet.getString("fallback_reasoning_effort");
        var outputContract = resultSet.getString("output_contract");
        var roleTemplateId = resultSet.getString("role_template_id");
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(resultSet.getString("node_id")),
                WorkflowNodeType.valueOf(resultSet.getString("node_type")),
                resultSet.getString("display_name"),
                resultSet.getString("role_name"),
                roleTemplateId == null ? null : new AgentRoleTemplateId(roleTemplateId),
                binding(providerId, resultSet.getString("model_name"), reasoning),
                binding(
                        fallbackProviderId,
                        resultSet.getString("fallback_model_name"),
                        fallbackReasoning),
                resultSet.getString("system_prompt"),
                resultSet.getString("user_prompt_template"),
                outputContract == null ? null : WorkflowOutputContract.valueOf(outputContract),
                WorkflowContextMode.valueOf(resultSet.getString("context_mode")),
                resultSet.getInt("context_history_rounds"),
                resultSet.getInt("context_max_messages"),
                resultSet.getInt("maximum_output_tokens"),
                resultSet.getInt("timeout_seconds"),
                new WorkflowRetryPolicy(
                        resultSet.getInt("retry_max_attempts"),
                        Duration.ofSeconds(resultSet.getInt("retry_backoff_seconds"))),
                resultSet.getString("operation"),
                new WorkflowCanvasPosition(
                        resultSet.getBigDecimal("position_x"),
                        resultSet.getBigDecimal("position_y")),
                resultSet.getBoolean("enabled"));
    }

    private static AiModelBinding binding(String providerId, String modelName, String reasoning) {
        return providerId == null
                ? null
                : new AiModelBinding(
                        new AiProviderProfileId(providerId),
                        modelName,
                        ReasoningEffort.valueOf(reasoning));
    }

    private static String providerProfileId(AiModelBinding binding) {
        return binding == null ? null : binding.providerProfileId().value();
    }

    private static String modelName(AiModelBinding binding) {
        return binding == null ? null : binding.modelName();
    }

    private static String reasoningEffort(AiModelBinding binding) {
        return binding == null ? null : binding.reasoningEffort().name();
    }

    private WorkflowEdgeDefinition edge(ResultSet resultSet) throws SQLException {
        var operator = resultSet.getString("condition_operator");
        WorkflowCondition condition = null;
        if (operator != null) {
            var enumOperator = WorkflowConditionOperator.valueOf(operator);
            condition = new WorkflowCondition(
                    resultSet.getString("condition_field"),
                    enumOperator,
                    enumOperator.requiresOperand()
                            ? decodeConditionOperand(resultSet.getString("condition_value"))
                            : null);
        }
        return new WorkflowEdgeDefinition(
                new WorkflowEdgeId(resultSet.getString("edge_id")),
                new WorkflowNodeId(resultSet.getString("source_node_id")),
                new WorkflowNodeId(resultSet.getString("target_node_id")),
                WorkflowActivationMode.valueOf(resultSet.getString("activation_mode")),
                WorkflowEdgeContextMode.valueOf(resultSet.getString("context_mode")),
                condition,
                resultSet.getBoolean("loop_edge"),
                nullableInteger(resultSet, "maximum_traversals"));
    }

    private AgentRoleTemplate role(ResultSet resultSet) throws SQLException {
        return new AgentRoleTemplate(
                new AgentRoleTemplateId(resultSet.getString("role_template_id")),
                resultSet.getString("display_name"),
                resultSet.getString("objective"),
                resultSet.getString("system_prompt"),
                resultSet.getString("user_prompt_template"),
                WorkflowOutputContract.valueOf(resultSet.getString("output_contract")),
                new AiProviderProfileId(resultSet.getString("default_provider_profile_id")),
                resultSet.getString("default_model_name"),
                ReasoningEffort.valueOf(resultSet.getString("default_reasoning_effort")),
                resultSet.getBoolean("built_in"),
                resultSet.getLong("version"),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)));
    }

    private VersionRoot versionRoot(ResultSet resultSet) throws SQLException {
        return new VersionRoot(
                new WorkflowDefinitionId(resultSet.getString("definition_id")),
                resultSet.getInt("version_number"),
                WorkflowVersionStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("default_debate_rounds"),
                resultSet.getInt("maximum_steps"),
                resultSet.getInt("maximum_duration_seconds"),
                resultSet.getLong("maximum_tokens"),
                resultSet.getBigDecimal("maximum_cost_usd"),
                WorkflowFailurePolicy.valueOf(resultSet.getString("failure_policy")),
                resultSet.getString("checksum"),
                nullableInstant(resultSet.getObject("published_at", OffsetDateTime.class)),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                resultSet.getString("created_by"));
    }

    private String conditionValue(WorkflowCondition condition) {
        if (condition == null || condition.operand() == null) {
            return null;
        }
        try {
            return switch (condition.operand()) {
                case TextConditionOperand value -> objectMapper.writeValueAsString(value.value());
                case DecimalConditionOperand value -> objectMapper.writeValueAsString(value.value());
                case BooleanConditionOperand value -> objectMapper.writeValueAsString(value.value());
                case TextListConditionOperand value -> objectMapper.writeValueAsString(value.values());
            };
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode workflow condition operand", exception);
        }
    }

    private ConditionOperand decodeConditionOperand(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isTextual()) {
                return new TextConditionOperand(node.textValue());
            }
            if (node.isNumber()) {
                return new DecimalConditionOperand(node.decimalValue());
            }
            if (node.isBoolean()) {
                return new BooleanConditionOperand(node.booleanValue());
            }
            if (node.isArray()) {
                var values = new ArrayList<String>();
                node.forEach(value -> {
                    if (!value.isTextual()) {
                        throw new IllegalArgumentException("Workflow condition list must contain strings");
                    }
                    values.add(value.textValue());
                });
                return new TextListConditionOperand(values);
            }
            throw new IllegalArgumentException("Unsupported workflow condition operand");
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid workflow condition JSON", exception);
        }
    }

    private static WorkflowVersionId nullableVersionId(String value) {
        return value == null ? null : new WorkflowVersionId(value);
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record VersionRoot(
            WorkflowDefinitionId definitionId,
            int versionNumber,
            WorkflowVersionStatus status,
            int defaultDebateRounds,
            int maximumSteps,
            int maximumDurationSeconds,
            long maximumTokens,
            java.math.BigDecimal maximumCostUsd,
            WorkflowFailurePolicy failurePolicy,
            String checksum,
            Instant publishedAt,
            Instant createdAt,
            String createdBy) {
    }
}
