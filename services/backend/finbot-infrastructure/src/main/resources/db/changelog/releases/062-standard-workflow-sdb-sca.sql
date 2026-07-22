--liquibase formatted sql

--changeset codex:062-standard-workflow-sdb-sca splitStatements:true endDelimiter:;
UPDATE workflow_definition_version
SET status = 'ARCHIVED'
WHERE definition_id = 'workflow_standard_product_research'
  AND status = 'PUBLISHED';

INSERT INTO workflow_definition_version (
    version_id, definition_id, version_number, status, default_debate_rounds,
    debate_protocol, debate_minimum_participant_seats, debate_minimum_quorum_roles,
    debate_stage_timeout_seconds, debate_critique_assignment,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, checksum, published_at, created_by
)
SELECT
    'workflowversion_standard_v9', definition_id, 9, 'PUBLISHED', 1,
    'SDB_SCA_V1', 5, 3, 1200, 'BALANCED_INCOMPLETE',
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy,
    '54ed83e919434a63a2cc7801d99158279d542092b0121f05d78b70e69ac74ebc',
    CURRENT_TIMESTAMP, 'system-migration'
FROM workflow_definition_version
WHERE version_id = 'workflowversion_standard_v8';

INSERT INTO workflow_node_definition (
    version_id, node_id, node_type, display_name, role_name, role_template_id,
    logical_role_key, provider_profile_id, model_name, reasoning_effort,
    fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens,
    timeout_seconds, retry_max_attempts, retry_backoff_seconds, operation,
    position_x, position_y, enabled
)
SELECT
    'workflowversion_standard_v9',
    node_id,
    CASE WHEN node_type = 'CHAIR' THEN 'SOCIAL_CHOICE' ELSE node_type END,
    CASE WHEN node_type = 'CHAIR' THEN 'SDB-SCA 对称社会选择' ELSE display_name END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE role_name END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE role_template_id END,
    CASE
        WHEN node_type IN ('AGENT', 'AGGREGATOR')
            THEN COALESCE(logical_role_key, role_template_id, node_id)
        ELSE NULL
    END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE provider_profile_id END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE model_name END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE reasoning_effort END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE fallback_provider_profile_id END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE fallback_model_name END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE fallback_reasoning_effort END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE system_prompt END,
    CASE WHEN node_type = 'CHAIR' THEN NULL ELSE user_prompt_template END,
    CASE WHEN node_type = 'CHAIR' THEN 'CONSENSUS_RESULT' ELSE output_contract END,
    CASE WHEN node_type = 'CHAIR' THEN 'NONE' ELSE context_mode END,
    CASE WHEN node_type = 'CHAIR' THEN 0 ELSE context_history_rounds END,
    CASE WHEN node_type = 'CHAIR' THEN 0 ELSE context_max_messages END,
    maximum_output_tokens,
    timeout_seconds,
    retry_max_attempts,
    retry_backoff_seconds,
    CASE WHEN node_type = 'CHAIR' THEN 'schulze_social_choice' ELSE operation END,
    position_x,
    position_y,
    enabled
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v8';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode,
    context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v9', edge.edge_id, edge.source_node_id, edge.target_node_id,
    edge.activation_mode,
    CASE
        WHEN source.node_type IN ('AGENT', 'AGGREGATOR')
          AND target.node_type IN ('AGENT', 'AGGREGATOR') THEN 'EXCLUDE'
        ELSE edge.context_mode
    END,
    edge.condition_field, edge.condition_operator,
    edge.condition_value, edge.loop_edge, edge.maximum_traversals
FROM workflow_edge_definition edge
JOIN workflow_node_definition source
  ON source.version_id = edge.version_id AND source.node_id = edge.source_node_id
JOIN workflow_node_definition target
  ON target.version_id = edge.version_id AND target.node_id = edge.target_node_id
WHERE edge.version_id = 'workflowversion_standard_v8'
  AND NOT (
      source.node_type IN ('AGENT', 'AGGREGATOR')
      AND target.node_type = 'CHAIR'
  );

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode,
    context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v9',
    'edge_sdb_choice_' || substring(md5(node_id), 1, 24),
    node_id,
    'node_chair_arbiter',
    'ALL',
    'EXCLUDE',
    NULL,
    NULL,
    NULL,
    FALSE,
    NULL
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v9'
  AND node_type IN ('AGENT', 'AGGREGATOR')
  AND enabled = TRUE;

UPDATE workflow_definition
SET description = '确定性采集与事实抽取、异构 Grok/Terra 独立分析、SDB-SCA 双盲交叉评审、对称修正、角色归一 Schulze 社会选择和模拟执行验证的全量主工作流',
    updated_at = CURRENT_TIMESTAMP
WHERE definition_id = 'workflow_standard_product_research';

--rollback UPDATE workflow_definition_version SET status = 'ARCHIVED', published_at = NULL WHERE version_id = 'workflowversion_standard_v9';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = COALESCE(published_at, CURRENT_TIMESTAMP) WHERE version_id = 'workflowversion_standard_v8';
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v9';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v9';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v9';
