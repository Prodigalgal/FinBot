--liquibase formatted sql

--changeset codex:066-any2api-reasoning-capability splitStatements:true endDelimiter:;
-- Any2API's model catalog declares HIGH as the maximum supported reasoning effort
-- for the research models. MAX is not silently downgraded by the gateway and returns HTTP 400.
UPDATE ai_model_profile
SET default_reasoning_effort = 'HIGH',
    maximum_reasoning_effort = 'HIGH',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE provider_profile_id = 'provider_any2api_research'
  AND model_name IN (
    'deepseek/expert',
    'glm/glm-5.2',
    'longcat/longcat-pro',
    'mimo/mimo-v2.5-pro',
    'minmax/MiniMax-M3'
  )
  AND default_reasoning_effort = 'MAX'
  AND maximum_reasoning_effort = 'MAX';

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
    'workflowversion_standard_v11', definition_id, 11, 'PUBLISHED', default_debate_rounds,
    debate_protocol, debate_minimum_participant_seats, debate_minimum_quorum_roles,
    debate_stage_timeout_seconds, debate_critique_assignment,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy,
    '6729ffa0000fab14a8d6d5ee6b5bfaaaa311fffd40980c0ceee46cfafec8f482',
    CURRENT_TIMESTAMP, 'system-migration'
FROM workflow_definition_version
WHERE version_id = 'workflowversion_standard_v10';

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
    'workflowversion_standard_v11', node_id, node_type, display_name, role_name, role_template_id,
    logical_role_key, provider_profile_id, model_name,
    CASE WHEN provider_profile_id = 'provider_any2api_research' THEN 'HIGH' ELSE reasoning_effort END,
    fallback_provider_profile_id, fallback_model_name,
    CASE WHEN fallback_provider_profile_id = 'provider_any2api_research' THEN 'HIGH' ELSE fallback_reasoning_effort END,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds, context_max_messages, maximum_output_tokens,
    timeout_seconds, retry_max_attempts, retry_backoff_seconds, operation,
    position_x, position_y, enabled
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v10';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode,
    context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v11', edge_id, source_node_id, target_node_id,
    activation_mode, context_mode, condition_field, condition_operator,
    condition_value, loop_edge, maximum_traversals
FROM workflow_edge_definition
WHERE version_id = 'workflowversion_standard_v10';

--rollback UPDATE workflow_definition_version SET status = 'ARCHIVED', published_at = NULL WHERE version_id = 'workflowversion_standard_v11';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = COALESCE(published_at, CURRENT_TIMESTAMP) WHERE version_id = 'workflowversion_standard_v10';
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v11';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v11';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v11';
--rollback UPDATE ai_model_profile SET default_reasoning_effort = 'MAX', maximum_reasoning_effort = 'MAX', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE provider_profile_id = 'provider_any2api_research' AND model_name IN ('deepseek/expert', 'glm/glm-5.2', 'longcat/longcat-pro', 'mimo/mimo-v2.5-pro', 'minmax/MiniMax-M3') AND default_reasoning_effort = 'HIGH' AND maximum_reasoning_effort = 'HIGH';
