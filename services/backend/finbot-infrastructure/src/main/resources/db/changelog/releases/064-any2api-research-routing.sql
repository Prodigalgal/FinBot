--liquibase formatted sql

--changeset codex:064-any2api-research-routing splitStatements:true endDelimiter:;
-- Any2API is one public gateway credential with namespaced upstream models.
-- Keep the deterministic social-choice node and execution-review nodes byte-for-byte
-- equivalent to v9 while moving only research AI nodes to the new gateway.
INSERT INTO ai_provider_profile (
    profile_id, display_name, protocol, reasoning_parameter_style,
    base_url, base_url_env, api_key_env, enabled,
    connect_timeout_seconds, request_timeout_seconds,
    maximum_concurrent_requests, acquire_timeout_seconds
) VALUES (
    'provider_any2api_research', 'Any2API 研究网关', 'CHAT', 'FLAT',
    'https://any2api.mnnu.eu.org/v1', NULL, 'FINBOT_AI_PROVIDER_KEYS_JSON', TRUE,
    15, 3600, 5, 1800
);

INSERT INTO ai_model_profile (
    model_profile_id, provider_profile_id, model_name,
    default_reasoning_effort, maximum_reasoning_effort,
    input_usd_per_million, output_usd_per_million, enabled
) VALUES
    ('model_any2api_deepseek_expert', 'provider_any2api_research',
     'deepseek/expert', 'MAX', 'MAX', 0.00000000, 0.00000000, TRUE),
    ('model_any2api_glm_52', 'provider_any2api_research',
     'glm/glm-5.2', 'MAX', 'MAX', 0.00000000, 0.00000000, TRUE),
    ('model_any2api_longcat_pro', 'provider_any2api_research',
     'longcat/longcat-pro', 'MAX', 'MAX', 0.00000000, 0.00000000, TRUE),
    ('model_any2api_mimo_25_pro', 'provider_any2api_research',
     'mimo/mimo-v2.5-pro', 'MAX', 'MAX', 0.00000000, 0.00000000, TRUE),
    ('model_any2api_minmax_m3', 'provider_any2api_research',
     'minmax/MiniMax-M3', 'MAX', 'MAX', 0.00000000, 0.00000000, TRUE);

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
    'workflowversion_standard_v10', definition_id, 10, 'PUBLISHED', default_debate_rounds,
    debate_protocol, debate_minimum_participant_seats, debate_minimum_quorum_roles,
    debate_stage_timeout_seconds, debate_critique_assignment,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy,
    '92851b0b9bd40d758799248299fa4959e140a130f4b2522faa9011eb2a66d08a',
    CURRENT_TIMESTAMP, 'system-migration'
FROM workflow_definition_version
WHERE version_id = 'workflowversion_standard_v9';

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
    'workflowversion_standard_v10',
    node_id,
    node_type,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'Any2API / LongCat 事实抽取清洗员'
        WHEN 'node_ai_cleaner_mimo' THEN 'Any2API / MiniMax 事实抽取清洗员'
        WHEN 'node_information_compressor' THEN 'Any2API / MiMo 事实去重压缩员'
        WHEN 'node_information_compressor_gemini' THEN 'Any2API / GLM 事实去重压缩员'
        WHEN 'node_compression_validator' THEN 'Any2API / DeepSeek 事实验证员'
        WHEN 'node_evidence_analyst' THEN '证据分析员 / MiMo 席位'
        WHEN 'node_evidence_analyst_grok' THEN '证据分析员 / GLM 席位'
        WHEN 'node_bull_analyst' THEN '看多分析员 / LongCat 席位'
        WHEN 'node_bull_analyst_gemini' THEN '看多分析员 / DeepSeek 席位'
        WHEN 'node_bear_analyst' THEN '看空分析员 / GLM 席位'
        WHEN 'node_bear_analyst_terra' THEN '看空分析员 / MiniMax 席位'
        WHEN 'node_market_structure' THEN '市场结构分析员 / DeepSeek 席位'
        WHEN 'node_market_structure_gemini' THEN '市场结构分析员 / MiMo 席位'
        WHEN 'node_risk_controller' THEN '风险控制员 / MiniMax 席位'
        WHEN 'node_risk_controller_grok' THEN '风险控制员 / LongCat 席位'
        ELSE display_name
    END,
    role_name,
    role_template_id,
    logical_role_key,
    CASE WHEN node_id IN (
        'node_ai_cleaner_gemini', 'node_ai_cleaner_mimo',
        'node_information_compressor', 'node_information_compressor_gemini',
        'node_compression_validator', 'node_evidence_analyst',
        'node_evidence_analyst_grok', 'node_bull_analyst',
        'node_bull_analyst_gemini', 'node_bear_analyst',
        'node_bear_analyst_terra', 'node_market_structure',
        'node_market_structure_gemini', 'node_risk_controller',
        'node_risk_controller_grok'
    ) THEN 'provider_any2api_research' ELSE provider_profile_id END,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'longcat/longcat-pro'
        WHEN 'node_ai_cleaner_mimo' THEN 'minmax/MiniMax-M3'
        WHEN 'node_information_compressor' THEN 'mimo/mimo-v2.5-pro'
        WHEN 'node_information_compressor_gemini' THEN 'glm/glm-5.2'
        WHEN 'node_compression_validator' THEN 'deepseek/expert'
        WHEN 'node_evidence_analyst' THEN 'mimo/mimo-v2.5-pro'
        WHEN 'node_evidence_analyst_grok' THEN 'glm/glm-5.2'
        WHEN 'node_bull_analyst' THEN 'longcat/longcat-pro'
        WHEN 'node_bull_analyst_gemini' THEN 'deepseek/expert'
        WHEN 'node_bear_analyst' THEN 'glm/glm-5.2'
        WHEN 'node_bear_analyst_terra' THEN 'minmax/MiniMax-M3'
        WHEN 'node_market_structure' THEN 'deepseek/expert'
        WHEN 'node_market_structure_gemini' THEN 'mimo/mimo-v2.5-pro'
        WHEN 'node_risk_controller' THEN 'minmax/MiniMax-M3'
        WHEN 'node_risk_controller_grok' THEN 'longcat/longcat-pro'
        ELSE model_name
    END,
    CASE WHEN node_id IN (
        'node_ai_cleaner_gemini', 'node_ai_cleaner_mimo',
        'node_information_compressor', 'node_information_compressor_gemini',
        'node_compression_validator', 'node_evidence_analyst',
        'node_evidence_analyst_grok', 'node_bull_analyst',
        'node_bull_analyst_gemini', 'node_bear_analyst',
        'node_bear_analyst_terra', 'node_market_structure',
        'node_market_structure_gemini', 'node_risk_controller',
        'node_risk_controller_grok'
    ) THEN 'MAX' ELSE reasoning_effort END,
    CASE WHEN node_id IN (
        'node_ai_cleaner_gemini', 'node_ai_cleaner_mimo',
        'node_information_compressor', 'node_information_compressor_gemini',
        'node_compression_validator', 'node_evidence_analyst',
        'node_evidence_analyst_grok', 'node_bull_analyst',
        'node_bull_analyst_gemini', 'node_bear_analyst',
        'node_bear_analyst_terra', 'node_market_structure',
        'node_market_structure_gemini', 'node_risk_controller',
        'node_risk_controller_grok'
    ) THEN 'provider_any2api_research' ELSE fallback_provider_profile_id END,
    CASE node_id
        WHEN 'node_ai_cleaner_gemini' THEN 'deepseek/expert'
        WHEN 'node_ai_cleaner_mimo' THEN 'mimo/mimo-v2.5-pro'
        WHEN 'node_information_compressor' THEN 'glm/glm-5.2'
        WHEN 'node_information_compressor_gemini' THEN 'longcat/longcat-pro'
        WHEN 'node_compression_validator' THEN 'minmax/MiniMax-M3'
        WHEN 'node_evidence_analyst' THEN 'glm/glm-5.2'
        WHEN 'node_evidence_analyst_grok' THEN 'mimo/mimo-v2.5-pro'
        WHEN 'node_bull_analyst' THEN 'deepseek/expert'
        WHEN 'node_bull_analyst_gemini' THEN 'longcat/longcat-pro'
        WHEN 'node_bear_analyst' THEN 'minmax/MiniMax-M3'
        WHEN 'node_bear_analyst_terra' THEN 'glm/glm-5.2'
        WHEN 'node_market_structure' THEN 'mimo/mimo-v2.5-pro'
        WHEN 'node_market_structure_gemini' THEN 'deepseek/expert'
        WHEN 'node_risk_controller' THEN 'longcat/longcat-pro'
        WHEN 'node_risk_controller_grok' THEN 'minmax/MiniMax-M3'
        ELSE fallback_model_name
    END,
    CASE WHEN node_id IN (
        'node_ai_cleaner_gemini', 'node_ai_cleaner_mimo',
        'node_information_compressor', 'node_information_compressor_gemini',
        'node_compression_validator', 'node_evidence_analyst',
        'node_evidence_analyst_grok', 'node_bull_analyst',
        'node_bull_analyst_gemini', 'node_bear_analyst',
        'node_bear_analyst_terra', 'node_market_structure',
        'node_market_structure_gemini', 'node_risk_controller',
        'node_risk_controller_grok'
    ) THEN 'MAX' ELSE fallback_reasoning_effort END,
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
WHERE version_id = 'workflowversion_standard_v9';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode,
    context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v10', edge_id, source_node_id, target_node_id,
    activation_mode, context_mode, condition_field, condition_operator,
    condition_value, loop_edge, maximum_traversals
FROM workflow_edge_definition
WHERE version_id = 'workflowversion_standard_v9';

UPDATE workflow_definition
SET description = '确定性采集与事实抽取、Any2API 异构模型独立研究、SDB-SCA 双盲交叉评审、对称修正、角色归一 Schulze 社会选择和模拟执行验证的全量主工作流',
    updated_at = CURRENT_TIMESTAMP
WHERE definition_id = 'workflow_standard_product_research';

--rollback UPDATE workflow_definition_version SET status = 'ARCHIVED', published_at = NULL WHERE version_id = 'workflowversion_standard_v10';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = COALESCE(published_at, CURRENT_TIMESTAMP) WHERE version_id = 'workflowversion_standard_v9';
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v10';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v10';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v10';
--rollback DELETE FROM ai_model_profile WHERE provider_profile_id = 'provider_any2api_research';
--rollback DELETE FROM ai_provider_profile WHERE profile_id = 'provider_any2api_research';
