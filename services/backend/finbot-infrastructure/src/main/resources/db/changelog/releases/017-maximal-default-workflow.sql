--liquibase formatted sql

--changeset codex:017-maximal-default-workflow splitStatements:true endDelimiter:;
UPDATE workflow_definition
SET name = '全量自主研究主工作流',
    description = '采集、清洗、AI 压缩、量化研究、三轮混合模型辩论、主席仲裁和模拟交易执行的内置主工作流',
    updated_at = CURRENT_TIMESTAMP
WHERE definition_id = 'workflow_standard_product_research';

INSERT INTO workflow_definition_version (
    version_id, definition_id, version_number, status, default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, checksum, published_at, created_by
) VALUES (
    'workflowversion_standard_v3', 'workflow_standard_product_research', 3, 'DRAFT', 3,
    300, 7200, 5000000, 25.00, 'STOP',
    'ca298eeb921d445518eb1a266e723371e9fa32a320886a1579621b4d711db4dc',
    NULL, 'system-migration'
);

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
    'workflowversion_standard_v3', node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort,
    CASE node_id
        WHEN 'node_information_compressor' THEN 'provider_sub2api_default'
        WHEN 'node_evidence_analyst' THEN 'provider_sub2api_default'
        WHEN 'node_bull_analyst' THEN 'provider_mimo_default'
        WHEN 'node_bear_analyst' THEN 'provider_sub2api_default'
        WHEN 'node_market_structure' THEN 'provider_mimo_default'
        WHEN 'node_risk_controller' THEN 'provider_sub2api_default'
        WHEN 'node_chair_arbiter' THEN 'provider_sub2api_default'
        ELSE NULL
    END,
    CASE node_id
        WHEN 'node_information_compressor' THEN 'gpt-5.6-luna'
        WHEN 'node_evidence_analyst' THEN 'gpt-5.6-luna'
        WHEN 'node_bull_analyst' THEN 'mimo-v2.5-pro'
        WHEN 'node_bear_analyst' THEN 'gpt-5.6-luna'
        WHEN 'node_market_structure' THEN 'mimo-v2.5-pro'
        WHEN 'node_risk_controller' THEN 'gpt-5.6-terra'
        WHEN 'node_chair_arbiter' THEN 'gpt-5.6-terra'
        ELSE NULL
    END,
    CASE node_id
        WHEN 'node_market_structure' THEN 'HIGH'
        WHEN 'node_risk_controller' THEN 'XHIGH'
        WHEN 'node_chair_arbiter' THEN 'XHIGH'
        WHEN 'node_information_compressor' THEN 'HIGH'
        WHEN 'node_evidence_analyst' THEN 'HIGH'
        WHEN 'node_bull_analyst' THEN 'HIGH'
        WHEN 'node_bear_analyst' THEN 'HIGH'
        ELSE NULL
    END,
    system_prompt, user_prompt_template, output_contract, context_mode,
    context_history_rounds,
    CASE WHEN node_type IN ('AGENT', 'CHAIR') THEN 48 ELSE context_max_messages END,
    CASE
        WHEN node_type = 'CHAIR' THEN 16384
        WHEN node_type IN ('COMPRESSOR', 'AGENT') THEN 8192
        ELSE maximum_output_tokens
    END,
    CASE
        WHEN node_type = 'CHAIR' THEN 900
        WHEN node_type IN ('COMPRESSOR', 'AGENT') THEN 600
        ELSE timeout_seconds
    END,
    CASE
        WHEN node_type IN ('COLLECTOR', 'COMPRESSOR', 'QUANT', 'AGENT', 'CHAIR') THEN 3
        ELSE retry_max_attempts
    END,
    CASE
        WHEN node_type IN ('COLLECTOR', 'COMPRESSOR', 'QUANT', 'AGENT', 'CHAIR') THEN 5
        ELSE retry_backoff_seconds
    END,
    operation, position_x, position_y, enabled
FROM workflow_node_definition
WHERE version_id = 'workflowversion_standard_v2';

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode, context_mode,
    condition_field, condition_operator, condition_value, loop_edge, maximum_traversals
)
SELECT
    'workflowversion_standard_v3', edge_id, source_node_id, target_node_id,
    activation_mode, context_mode, condition_field, condition_operator, condition_value,
    loop_edge, maximum_traversals
FROM workflow_edge_definition
WHERE version_id = 'workflowversion_standard_v2';

UPDATE trade_execution_ai_stage
SET fallback_provider_profile_id = 'provider_sub2api_default',
    fallback_model_name = 'gpt-5.6-terra',
    fallback_reasoning_effort = 'XHIGH',
    maximum_output_tokens = 16384,
    timeout_seconds = 900,
    retry_max_attempts = 3,
    retry_backoff_seconds = 5,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE stage IN ('DRAFT', 'REFLECTION');

UPDATE workflow_definition_version
SET status = 'ARCHIVED'
WHERE version_id = 'workflowversion_standard_v2';

UPDATE workflow_definition_version
SET status = 'PUBLISHED',
    published_at = CURRENT_TIMESTAMP
WHERE version_id = 'workflowversion_standard_v3';

--rollback UPDATE workflow_definition_version SET status = 'DRAFT', published_at = NULL WHERE version_id = 'workflowversion_standard_v3';
--rollback UPDATE workflow_definition_version SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP WHERE version_id = 'workflowversion_standard_v2';
--rollback UPDATE trade_execution_ai_stage SET fallback_provider_profile_id = NULL, fallback_model_name = NULL, fallback_reasoning_effort = NULL, maximum_output_tokens = 4096, timeout_seconds = 300, retry_max_attempts = 3, retry_backoff_seconds = 2, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE stage IN ('DRAFT', 'REFLECTION');
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v3';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v3';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v3';
--rollback UPDATE workflow_definition SET name = '标准产品研究', description = '收集与证据审查、MiMo 和 Sub2API 混合多轮辩论、风险复核和主席仲裁的默认工作流', updated_at = CURRENT_TIMESTAMP WHERE definition_id = 'workflow_standard_product_research';
