--liquibase formatted sql

--changeset codex:012-operational-ai-routing splitStatements:true endDelimiter:;
UPDATE ai_provider_profile
SET base_url = 'http://mimo2api.mimo2api.svc.cluster.local:8080/v1',
    base_url_env = NULL,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_id = 'provider_mimo_default';

UPDATE ai_provider_profile
SET enabled = FALSE,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE profile_id = 'provider_deepseek_default';

UPDATE ai_model_profile
SET enabled = FALSE,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE provider_profile_id = 'provider_deepseek_default';

UPDATE agent_role_template
SET default_provider_profile_id = 'provider_sub2api_default',
    default_model_name = 'gpt-5.6-luna',
    default_reasoning_effort = 'HIGH',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE role_template_id = 'role_bull_analyst';

UPDATE agent_role_template
SET default_provider_profile_id = 'provider_mimo_default',
    default_model_name = 'mimo-v2.5-pro',
    default_reasoning_effort = 'HIGH',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE role_template_id = 'role_risk_controller';

UPDATE workflow_definition
SET description = '收集与证据审查、MiMo 和 Sub2API 混合多轮辩论、风险复核和主席仲裁的默认工作流',
    updated_at = CURRENT_TIMESTAMP
WHERE definition_id = 'workflow_standard_product_research';

INSERT INTO workflow_definition_version (
    version_id, definition_id, version_number, status, default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, checksum, published_at, created_by
)
SELECT
    'workflowversion_standard_v2', definition_id, 2, 'DRAFT', default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, '1fdea69ba5bba4b4e334530e599225d98c5a102e24b0b277b762557c39c1248a',
    NULL, 'system-migration'
FROM workflow_definition_version
WHERE version_id = 'workflowversion_standard_v1';

INSERT INTO workflow_node_definition (
    version_id, node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort, system_prompt,
    user_prompt_template, output_contract, context_mode, context_history_rounds,
    context_max_messages, maximum_output_tokens, timeout_seconds,
    retry_max_attempts, retry_backoff_seconds, operation, position_x, position_y, enabled
)
SELECT
    'workflowversion_standard_v2', node_id, node_type, display_name, role_name, role_template_id,
    CASE node_id
        WHEN 'node_bull_analyst' THEN 'provider_sub2api_default'
        WHEN 'node_risk_controller' THEN 'provider_mimo_default'
        ELSE provider_profile_id
    END,
    CASE node_id
        WHEN 'node_bull_analyst' THEN 'gpt-5.6-luna'
        WHEN 'node_risk_controller' THEN 'mimo-v2.5-pro'
        ELSE model_name
    END,
    reasoning_effort, system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens, timeout_seconds,
    retry_max_attempts, retry_backoff_seconds, operation, position_x, position_y, enabled
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v1';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode, context_mode,
    condition_field, condition_operator, condition_value, loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v2', edge_id, source_node_id, target_node_id,
    activation_mode, context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
FROM workflow_edge_definition
WHERE version_id = 'workflowversion_standard_v1';

UPDATE workflow_definition_version
SET status = 'ARCHIVED'
WHERE version_id = 'workflowversion_standard_v1';

UPDATE workflow_definition_version
SET status = 'PUBLISHED',
    published_at = CURRENT_TIMESTAMP
WHERE version_id = 'workflowversion_standard_v2';

--rollback UPDATE workflow_definition_version SET status = 'DRAFT', published_at = NULL WHERE version_id = 'workflowversion_standard_v2';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE version_id = 'workflowversion_standard_v1';
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v2';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v2';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v2';
--rollback UPDATE workflow_definition SET description = '收集与证据审查、三家混合多轮辩论、风险复核和主席仲裁的默认工作流', updated_at = CURRENT_TIMESTAMP WHERE definition_id = 'workflow_standard_product_research';
--rollback UPDATE agent_role_template SET default_provider_profile_id = 'provider_deepseek_default', default_model_name = 'deepseek-chat', default_reasoning_effort = 'HIGH', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE role_template_id IN ('role_bull_analyst', 'role_risk_controller');
--rollback UPDATE ai_model_profile SET enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE provider_profile_id = 'provider_deepseek_default';
--rollback UPDATE ai_provider_profile SET enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE profile_id = 'provider_deepseek_default';
--rollback UPDATE ai_provider_profile SET base_url = 'https://mimo2api.mnnu.eu.org/v1', base_url_env = NULL, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE profile_id = 'provider_mimo_default';
