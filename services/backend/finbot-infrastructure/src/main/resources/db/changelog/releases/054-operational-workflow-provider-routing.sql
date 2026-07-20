--liquibase formatted sql

--changeset codex:054-operational-workflow-provider-routing splitStatements:true endDelimiter:;
-- The default workflow must remain multi-seat and heterogeneous, but its built-in
-- route cannot depend on providers that are currently returning upstream errors.
-- Keep v6 immutable for historical runs and publish an operationally routed v7.
UPDATE workflow_definition_version
SET status = 'ARCHIVED'
WHERE definition_id = 'workflow_standard_product_research'
  AND status = 'PUBLISHED';

INSERT INTO workflow_definition_version (
    version_id, definition_id, version_number, status, default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, checksum, published_at, created_by
)
SELECT
    'workflowversion_standard_v7', definition_id, 7, 'PUBLISHED', default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy,
    'd315bbb09aaaae999eed831a2ca780165b6ee3924582cf9c29fe809f27004244',
    CURRENT_TIMESTAMP, 'system-migration'
FROM workflow_definition_version
WHERE version_id = 'workflowversion_standard_v6';

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
    'workflowversion_standard_v7',
    node_id,
    node_type,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'Grok 证据清洗审查员'
        WHEN 'node_ai_cleaner_mimo' THEN 'Terra 证据清洗审查员'
        WHEN 'node_information_compressor' THEN 'Terra 信息压缩员'
        WHEN 'node_information_compressor_gemini' THEN 'Grok 信息压缩员'
        WHEN 'node_evidence_analyst' THEN '证据分析员 / Terra 席位'
        WHEN 'node_bull_analyst_gemini' THEN '看多分析员 / Terra 席位'
        WHEN 'node_bear_analyst' THEN '看空分析员 / Grok 席位'
        WHEN 'node_market_structure_gemini' THEN '市场结构分析员 / Grok 席位'
        WHEN 'node_risk_controller' THEN '风险控制员 / Terra 席位'
        ELSE display_name
    END,
    role_name,
    role_template_id,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'provider_grok_sub2api'
        WHEN 'node_ai_cleaner_mimo' THEN 'provider_sub2api_default'
        WHEN 'node_information_compressor' THEN 'provider_sub2api_default'
        WHEN 'node_information_compressor_gemini' THEN 'provider_grok_sub2api'
        WHEN 'node_evidence_analyst' THEN 'provider_sub2api_default'
        WHEN 'node_bull_analyst_gemini' THEN 'provider_sub2api_default'
        WHEN 'node_bear_analyst' THEN 'provider_grok_sub2api'
        WHEN 'node_market_structure_gemini' THEN 'provider_grok_sub2api'
        WHEN 'node_risk_controller' THEN 'provider_sub2api_default'
        ELSE provider_profile_id
    END,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'grok-4.5'
        WHEN 'node_ai_cleaner_mimo' THEN 'gpt-5.6-terra'
        WHEN 'node_information_compressor' THEN 'gpt-5.6-terra'
        WHEN 'node_information_compressor_gemini' THEN 'grok-4.5'
        WHEN 'node_evidence_analyst' THEN 'gpt-5.6-terra'
        WHEN 'node_bull_analyst_gemini' THEN 'gpt-5.6-terra'
        WHEN 'node_bear_analyst' THEN 'grok-4.5'
        WHEN 'node_market_structure_gemini' THEN 'grok-4.5'
        WHEN 'node_risk_controller' THEN 'gpt-5.6-terra'
        ELSE model_name
    END,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'XHIGH'
        WHEN 'node_ai_cleaner_mimo' THEN 'XHIGH'
        WHEN 'node_information_compressor' THEN 'XHIGH'
        WHEN 'node_information_compressor_gemini' THEN 'XHIGH'
        WHEN 'node_evidence_analyst' THEN 'XHIGH'
        WHEN 'node_bull_analyst_gemini' THEN 'XHIGH'
        WHEN 'node_bear_analyst' THEN 'XHIGH'
        WHEN 'node_market_structure_gemini' THEN 'XHIGH'
        WHEN 'node_risk_controller' THEN 'XHIGH'
        ELSE reasoning_effort
    END,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'provider_sub2api_default'
        WHEN 'node_ai_cleaner_mimo' THEN 'provider_grok_sub2api'
        WHEN 'node_information_compressor' THEN 'provider_grok_sub2api'
        WHEN 'node_information_compressor_gemini' THEN 'provider_sub2api_default'
        WHEN 'node_evidence_analyst' THEN 'provider_grok_sub2api'
        WHEN 'node_bull_analyst_gemini' THEN 'provider_grok_sub2api'
        WHEN 'node_bear_analyst' THEN 'provider_sub2api_default'
        WHEN 'node_market_structure_gemini' THEN 'provider_sub2api_default'
        WHEN 'node_risk_controller' THEN 'provider_grok_sub2api'
        WHEN 'node_evidence_analyst_grok' THEN 'provider_sub2api_default'
        WHEN 'node_risk_controller_grok' THEN 'provider_sub2api_default'
        ELSE fallback_provider_profile_id
    END,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'gpt-5.6-terra'
        WHEN 'node_ai_cleaner_mimo' THEN 'grok-4.5'
        WHEN 'node_information_compressor' THEN 'grok-4.5'
        WHEN 'node_information_compressor_gemini' THEN 'gpt-5.6-terra'
        WHEN 'node_evidence_analyst' THEN 'grok-4.5'
        WHEN 'node_bull_analyst_gemini' THEN 'grok-4.5'
        WHEN 'node_bear_analyst' THEN 'gpt-5.6-terra'
        WHEN 'node_market_structure_gemini' THEN 'gpt-5.6-terra'
        WHEN 'node_risk_controller' THEN 'grok-4.5'
        WHEN 'node_evidence_analyst_grok' THEN 'gpt-5.6-terra'
        WHEN 'node_risk_controller_grok' THEN 'gpt-5.6-terra'
        ELSE fallback_model_name
    END,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'XHIGH'
        WHEN 'node_ai_cleaner_mimo' THEN 'XHIGH'
        WHEN 'node_information_compressor' THEN 'XHIGH'
        WHEN 'node_information_compressor_gemini' THEN 'XHIGH'
        WHEN 'node_evidence_analyst' THEN 'XHIGH'
        WHEN 'node_bull_analyst_gemini' THEN 'XHIGH'
        WHEN 'node_bear_analyst' THEN 'XHIGH'
        WHEN 'node_market_structure_gemini' THEN 'XHIGH'
        WHEN 'node_risk_controller' THEN 'XHIGH'
        WHEN 'node_evidence_analyst_grok' THEN 'XHIGH'
        WHEN 'node_risk_controller_grok' THEN 'XHIGH'
        ELSE fallback_reasoning_effort
    END,
    system_prompt,
    user_prompt_template,
    output_contract,
    context_mode,
    context_history_rounds,
    context_max_messages,
    maximum_output_tokens,
    timeout_seconds,
    retry_max_attempts,
    retry_backoff_seconds,
    operation,
    position_x,
    position_y,
    enabled
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v6';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode,
    context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v7', edge_id, source_node_id, target_node_id,
    activation_mode, context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
FROM workflow_edge_definition
WHERE version_id = 'workflowversion_standard_v6';

--rollback UPDATE workflow_definition_version SET status = 'ARCHIVED', published_at = NULL WHERE version_id = 'workflowversion_standard_v7';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = COALESCE(published_at, CURRENT_TIMESTAMP) WHERE version_id = 'workflowversion_standard_v6';
