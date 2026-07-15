--liquibase formatted sql

--changeset codex:025-workflow-owned-execution-review splitStatements:true endDelimiter:;
UPDATE workflow_definition
SET description = '采集、清洗、AI 压缩、量化研究、混合模型多轮辩论、主席仲裁、执行初审与独立反思的全量主工作流',
    updated_at = CURRENT_TIMESTAMP
WHERE definition_id = 'workflow_standard_product_research';

INSERT INTO workflow_definition_version (
    version_id, definition_id, version_number, status, default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, checksum, published_at, created_by
)
SELECT
    'workflowversion_standard_v4', definition_id, 4, 'DRAFT', default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, 'f3db6cf42a547c52fb64cebb21ea9191f7b20632c118173ff4dc37bfa7544101',
    NULL, 'system-migration'
FROM workflow_definition_version
WHERE version_id = 'workflowversion_standard_v3';

INSERT INTO workflow_node_definition (
    version_id, node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort,
    fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens,
    timeout_seconds, retry_max_attempts, retry_backoff_seconds, operation,
    position_x, position_y, enabled
)
SELECT
    'workflowversion_standard_v4', node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort,
    fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens,
    timeout_seconds, retry_max_attempts, retry_backoff_seconds, operation,
    CASE WHEN node_id = 'node_research_output' THEN 1840 ELSE position_x END,
    position_y, enabled
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v3';

INSERT INTO workflow_node_definition (
    version_id, node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort,
    fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens,
    timeout_seconds, retry_max_attempts, retry_backoff_seconds, operation,
    position_x, position_y, enabled
)
SELECT
    'workflowversion_standard_v4',
    CASE stage WHEN 'DRAFT' THEN 'node_execution_draft' ELSE 'node_execution_reflection' END,
    'EXECUTION_REVIEW',
    CASE stage WHEN 'DRAFT' THEN '执行机器人初审' ELSE '执行机器人独立反思' END,
    CASE stage WHEN 'DRAFT' THEN 'Execution Draft' ELSE 'Execution Reflection' END,
    NULL,
    provider_profile_id, model_name, reasoning_effort,
    fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
    system_prompt, user_prompt_template,
    CASE stage WHEN 'DRAFT' THEN 'TRADE_DECISIONS' ELSE 'EXECUTION_VERDICT' END,
    'UPSTREAM', 3, 48, maximum_output_tokens, timeout_seconds,
    retry_max_attempts, retry_backoff_seconds, lower(stage),
    CASE stage WHEN 'DRAFT' THEN 1440 ELSE 1640 END,
    240, enabled
FROM trade_execution_ai_stage
WHERE stage IN ('DRAFT', 'REFLECTION');

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode, context_mode,
    condition_field, condition_operator, condition_value, loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v4', edge_id, source_node_id, target_node_id,
    activation_mode, context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
FROM workflow_edge_definition
WHERE version_id = 'workflowversion_standard_v3'
  AND edge_id <> 'edge_chair_output';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode, context_mode
) VALUES
    ('workflowversion_standard_v4', 'edge_chair_execution_draft', 'node_chair_arbiter', 'node_execution_draft', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v4', 'edge_execution_draft_reflection', 'node_execution_draft', 'node_execution_reflection', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v4', 'edge_execution_reflection_output', 'node_execution_reflection', 'node_research_output', 'ALL', 'SUMMARY');

UPDATE workflow_definition_version
SET status = 'ARCHIVED'
WHERE version_id = 'workflowversion_standard_v3';

UPDATE workflow_definition_version
SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
WHERE version_id = 'workflowversion_standard_v4';

--rollback UPDATE workflow_definition_version SET status = 'DRAFT', published_at = NULL WHERE version_id = 'workflowversion_standard_v4';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE version_id = 'workflowversion_standard_v3';
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v4';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v4';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v4';
--rollback UPDATE workflow_definition SET description = '采集、清洗、AI 压缩、量化研究、三轮混合模型辩论、主席仲裁和模拟交易执行的内置主工作流', updated_at = CURRENT_TIMESTAMP WHERE definition_id = 'workflow_standard_product_research';
