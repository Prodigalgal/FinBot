--liquibase formatted sql

--changeset codex:004-ai-workflow splitStatements:true endDelimiter:;
CREATE TABLE workflow_definition (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    definition_id VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_workflow_definition_id CHECK (definition_id ~ '^workflow_[a-z0-9_-]{4,67}$')
);

CREATE TABLE workflow_definition_version (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id VARCHAR(80) NOT NULL UNIQUE,
    definition_id VARCHAR(80) NOT NULL REFERENCES workflow_definition (definition_id),
    version_number INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    default_debate_rounds SMALLINT NOT NULL DEFAULT 3,
    maximum_steps INTEGER NOT NULL DEFAULT 100,
    maximum_duration_seconds INTEGER NOT NULL DEFAULT 1800,
    maximum_tokens BIGINT NOT NULL DEFAULT 500000,
    maximum_cost_usd NUMERIC(18, 8) NOT NULL DEFAULT 0.50,
    failure_policy VARCHAR(16) NOT NULL DEFAULT 'STOP',
    checksum CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(80) NOT NULL DEFAULT 'admin',
    CONSTRAINT uq_workflow_definition_version UNIQUE (definition_id, version_number),
    CONSTRAINT ck_workflow_version_id CHECK (version_id ~ '^workflowversion_[a-z0-9_-]{4,60}$'),
    CONSTRAINT ck_workflow_version_number CHECK (version_number > 0),
    CONSTRAINT ck_workflow_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_workflow_version_rounds CHECK (default_debate_rounds BETWEEN 1 AND 8),
    CONSTRAINT ck_workflow_version_limits CHECK (
        maximum_steps BETWEEN 1 AND 1000
        AND maximum_duration_seconds BETWEEN 10 AND 86400
        AND maximum_tokens BETWEEN 1000 AND 10000000
        AND maximum_cost_usd >= 0
    ),
    CONSTRAINT ck_workflow_version_failure_policy CHECK (failure_policy IN ('STOP', 'CONTINUE', 'REPLAN')),
    CONSTRAINT ck_workflow_version_checksum CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workflow_version_publish CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL) OR status <> 'PUBLISHED'
    )
);

CREATE UNIQUE INDEX uq_workflow_published_version
    ON workflow_definition_version (definition_id)
    WHERE status = 'PUBLISHED';

CREATE TABLE agent_role_template (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role_template_id VARCHAR(80) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    objective VARCHAR(1000) NOT NULL,
    system_prompt TEXT NOT NULL,
    user_prompt_template TEXT NOT NULL,
    output_contract VARCHAR(40) NOT NULL,
    default_provider_profile_id VARCHAR(80) NOT NULL REFERENCES ai_provider_profile (profile_id),
    default_model_name VARCHAR(160) NOT NULL,
    default_reasoning_effort VARCHAR(24) NOT NULL,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_role_template_id CHECK (role_template_id ~ '^role_[a-z0-9_-]{4,73}$'),
    CONSTRAINT ck_agent_role_output CHECK (output_contract IN (
        'TEXT', 'RESEARCH_FINDINGS', 'DEBATE_ARGUMENT', 'RISK_ASSESSMENT',
        'CHAIR_VERDICT', 'TRADE_DECISIONS', 'EXECUTION_VERDICT'
    )),
    CONSTRAINT ck_agent_role_reasoning CHECK (default_reasoning_effort IN (
        'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
    )),
    CONSTRAINT ck_agent_role_version CHECK (version >= 0)
);

CREATE TABLE workflow_node_definition (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id VARCHAR(80) NOT NULL REFERENCES workflow_definition_version (version_id) ON DELETE CASCADE,
    node_id VARCHAR(80) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    role_name VARCHAR(120),
    role_template_id VARCHAR(80) REFERENCES agent_role_template (role_template_id),
    provider_profile_id VARCHAR(80) REFERENCES ai_provider_profile (profile_id),
    model_name VARCHAR(160),
    reasoning_effort VARCHAR(24),
    system_prompt TEXT,
    user_prompt_template TEXT,
    output_contract VARCHAR(40),
    context_mode VARCHAR(24) NOT NULL DEFAULT 'UPSTREAM',
    context_history_rounds SMALLINT NOT NULL DEFAULT 3,
    context_max_messages SMALLINT NOT NULL DEFAULT 24,
    maximum_output_tokens INTEGER NOT NULL DEFAULT 4096,
    timeout_seconds INTEGER NOT NULL DEFAULT 120,
    retry_max_attempts SMALLINT NOT NULL DEFAULT 1,
    retry_backoff_seconds INTEGER NOT NULL DEFAULT 0,
    operation VARCHAR(80),
    position_x NUMERIC(12, 4) NOT NULL DEFAULT 0,
    position_y NUMERIC(12, 4) NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_workflow_node UNIQUE (version_id, node_id),
    CONSTRAINT ck_workflow_node_id CHECK (node_id ~ '^[a-z][a-z0-9_-]{1,79}$'),
    CONSTRAINT ck_workflow_node_type CHECK (node_type IN (
        'INPUT', 'ROUTER', 'DETERMINISTIC', 'COLLECTOR', 'CLEANER', 'COMPRESSOR',
        'AGENT', 'GATE', 'QUANT', 'RISK', 'SUBFLOW', 'HUMAN_REVIEW',
        'AGGREGATOR', 'CHAIR', 'EXECUTION_REVIEW', 'OUTPUT'
    )),
    CONSTRAINT ck_workflow_node_reasoning CHECK (
        reasoning_effort IS NULL OR reasoning_effort IN (
            'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
        )
    ),
    CONSTRAINT ck_workflow_node_output CHECK (
        output_contract IS NULL OR output_contract IN (
            'TEXT', 'RESEARCH_FINDINGS', 'DEBATE_ARGUMENT', 'RISK_ASSESSMENT',
            'CHAIR_VERDICT', 'TRADE_DECISIONS', 'EXECUTION_VERDICT'
        )
    ),
    CONSTRAINT ck_workflow_node_context CHECK (context_mode IN (
        'UPSTREAM', 'SELECTED', 'LATEST', 'CLAIMS_ONLY', 'SUMMARY', 'NONE'
    )),
    CONSTRAINT ck_workflow_node_context_limits CHECK (
        context_history_rounds BETWEEN 0 AND 8 AND context_max_messages BETWEEN 0 AND 64
    ),
    CONSTRAINT ck_workflow_node_limits CHECK (
        maximum_output_tokens BETWEEN 64 AND 65536
        AND timeout_seconds BETWEEN 5 AND 1800
        AND retry_max_attempts BETWEEN 1 AND 5
        AND retry_backoff_seconds BETWEEN 0 AND 300
    ),
    CONSTRAINT ck_workflow_node_llm_binding CHECK (
        (node_type IN ('COMPRESSOR', 'AGENT', 'AGGREGATOR', 'CHAIR', 'EXECUTION_REVIEW')
            AND provider_profile_id IS NOT NULL
            AND model_name IS NOT NULL
            AND reasoning_effort IS NOT NULL
            AND system_prompt IS NOT NULL)
        OR node_type NOT IN ('COMPRESSOR', 'AGENT', 'AGGREGATOR', 'CHAIR', 'EXECUTION_REVIEW')
    )
);

CREATE INDEX ix_workflow_node_version_type
    ON workflow_node_definition (version_id, node_type, enabled, id);

CREATE TABLE workflow_edge_definition (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_id VARCHAR(80) NOT NULL,
    edge_id VARCHAR(80) NOT NULL,
    source_node_id VARCHAR(80) NOT NULL,
    target_node_id VARCHAR(80) NOT NULL,
    activation_mode VARCHAR(8) NOT NULL DEFAULT 'ALL',
    context_mode VARCHAR(16) NOT NULL DEFAULT 'INHERIT',
    condition_field VARCHAR(260),
    condition_operator VARCHAR(16),
    condition_value JSONB,
    loop_edge BOOLEAN NOT NULL DEFAULT FALSE,
    maximum_traversals SMALLINT,
    CONSTRAINT uq_workflow_edge UNIQUE (version_id, edge_id),
    CONSTRAINT fk_workflow_edge_version
        FOREIGN KEY (version_id) REFERENCES workflow_definition_version (version_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edge_source
        FOREIGN KEY (version_id, source_node_id)
        REFERENCES workflow_node_definition (version_id, node_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edge_target
        FOREIGN KEY (version_id, target_node_id)
        REFERENCES workflow_node_definition (version_id, node_id) ON DELETE CASCADE,
    CONSTRAINT ck_workflow_edge_id CHECK (edge_id ~ '^[a-z][a-z0-9_-]{1,79}$'),
    CONSTRAINT ck_workflow_edge_self CHECK (source_node_id <> target_node_id),
    CONSTRAINT ck_workflow_edge_activation CHECK (activation_mode IN ('ALL', 'ANY')),
    CONSTRAINT ck_workflow_edge_context CHECK (context_mode IN (
        'INHERIT', 'INCLUDE', 'EXCLUDE', 'LATEST', 'CLAIMS_ONLY', 'SUMMARY'
    )),
    CONSTRAINT ck_workflow_edge_condition CHECK (
        (condition_field IS NULL AND condition_operator IS NULL AND condition_value IS NULL)
        OR (condition_field ~ '^[a-zA-Z][a-zA-Z0-9_.-]{0,255}$'
            AND condition_operator IN (
                'EXISTS', 'EQ', 'NE', 'IN', 'NOT_IN', 'GT', 'GTE', 'LT', 'LTE',
                'CONTAINS', 'TRUTHY', 'FALSY'
            ))
    ),
    CONSTRAINT ck_workflow_edge_loop CHECK (
        (loop_edge = FALSE AND maximum_traversals IS NULL)
        OR (loop_edge = TRUE AND condition_operator IS NOT NULL AND maximum_traversals BETWEEN 1 AND 8)
    )
);

ALTER TABLE workflow_run
    ADD COLUMN workflow_version_id VARCHAR(80) REFERENCES workflow_definition_version (version_id),
    ADD COLUMN current_node_id VARCHAR(80),
    ADD COLUMN next_event_sequence BIGINT NOT NULL DEFAULT 2,
    ADD COLUMN total_input_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN total_output_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN total_cost_usd NUMERIC(18, 8) NOT NULL DEFAULT 0,
    ADD COLUMN reserved_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN reserved_cost_usd NUMERIC(18, 8) NOT NULL DEFAULT 0;

ALTER TABLE workflow_run DROP CONSTRAINT ck_workflow_run_status;
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_status CHECK (status IN (
    'ACCEPTED', 'RUNNING', 'WAITING_HUMAN', 'PARTIAL', 'COMPLETED', 'FAILED', 'CANCELLED'
));
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_ai_usage CHECK (
    total_input_tokens >= 0 AND total_output_tokens >= 0 AND total_cost_usd >= 0
    AND reserved_tokens >= 0 AND reserved_cost_usd >= 0
);
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_next_sequence CHECK (next_event_sequence >= 2);

CREATE TABLE workflow_node_checkpoint (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    checkpoint_id VARCHAR(80) NOT NULL UNIQUE,
    run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    node_id VARCHAR(80) NOT NULL,
    round_index SMALLINT NOT NULL DEFAULT 0,
    iteration INTEGER NOT NULL DEFAULT 0,
    attempt INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL,
    result_summary TEXT,
    artifact_id VARCHAR(80),
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_workflow_node_checkpoint UNIQUE (run_id, node_id, round_index, iteration),
    CONSTRAINT ck_workflow_checkpoint_id CHECK (checkpoint_id ~ '^checkpoint_[a-z0-9_-]{4,63}$'),
    CONSTRAINT ck_workflow_checkpoint_position CHECK (
        round_index BETWEEN 0 AND 8 AND iteration >= 0 AND attempt BETWEEN 1 AND 5
    ),
    CONSTRAINT ck_workflow_checkpoint_status CHECK (status IN (
        'PENDING', 'RUNNING', 'COMPLETED', 'SKIPPED', 'WAITING_HUMAN', 'FAILED'
    )),
    CONSTRAINT ck_workflow_checkpoint_time CHECK (
        completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at
    )
);

CREATE INDEX ix_workflow_checkpoint_run_status
    ON workflow_node_checkpoint (run_id, status, round_index, iteration, id);

CREATE TABLE debate_session (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    debate_id VARCHAR(80) NOT NULL UNIQUE,
    run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL,
    configured_rounds SMALLINT NOT NULL,
    completed_rounds SMALLINT NOT NULL DEFAULT 0,
    chair_node_id VARCHAR(80) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_debate_session_run UNIQUE (run_id),
    CONSTRAINT ck_debate_session_id CHECK (debate_id ~ '^debate_[a-z0-9_-]{4,70}$'),
    CONSTRAINT ck_debate_session_status CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_debate_session_rounds CHECK (
        configured_rounds BETWEEN 1 AND 8
        AND completed_rounds BETWEEN 0 AND configured_rounds
    ),
    CONSTRAINT ck_debate_session_time CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE agent_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id VARCHAR(80) NOT NULL UNIQUE,
    debate_id VARCHAR(80) NOT NULL REFERENCES debate_session (debate_id) ON DELETE CASCADE,
    run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    node_id VARCHAR(80) NOT NULL,
    role_name VARCHAR(120) NOT NULL,
    round_index SMALLINT NOT NULL,
    turn_index INTEGER NOT NULL,
    message_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    summary TEXT NOT NULL,
    argument TEXT NOT NULL,
    confidence NUMERIC(5, 4),
    claims JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    challenges JSONB NOT NULL DEFAULT '[]'::jsonb,
    revision_notes JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_agent_message_turn UNIQUE (debate_id, round_index, turn_index, node_id),
    CONSTRAINT ck_agent_message_id CHECK (message_id ~ '^message_[a-z0-9_-]{4,69}$'),
    CONSTRAINT ck_agent_message_round CHECK (round_index BETWEEN 0 AND 8 AND turn_index >= 0),
    CONSTRAINT ck_agent_message_type CHECK (message_type IN ('ARGUMENT', 'CHALLENGE', 'REVISION', 'CHAIR_VERDICT')),
    CONSTRAINT ck_agent_message_status CHECK (status IN ('COMPLETED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_agent_message_confidence CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT ck_agent_message_arrays CHECK (
        jsonb_typeof(claims) = 'array'
        AND jsonb_typeof(evidence_refs) = 'array'
        AND jsonb_typeof(challenges) = 'array'
        AND jsonb_typeof(revision_notes) = 'array'
    )
);

CREATE INDEX ix_agent_message_debate_round
    ON agent_message (debate_id, round_index, turn_index, id);

CREATE TABLE agent_message_reply (
    message_id VARCHAR(80) NOT NULL REFERENCES agent_message (message_id) ON DELETE CASCADE,
    replied_to_message_id VARCHAR(80) NOT NULL REFERENCES agent_message (message_id) ON DELETE CASCADE,
    reply_order SMALLINT NOT NULL,
    PRIMARY KEY (message_id, replied_to_message_id),
    CONSTRAINT ck_agent_message_reply_self CHECK (message_id <> replied_to_message_id),
    CONSTRAINT ck_agent_message_reply_order CHECK (reply_order >= 0)
);

CREATE TABLE ai_invocation (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invocation_id VARCHAR(80) NOT NULL UNIQUE,
    run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    node_id VARCHAR(80) NOT NULL,
    provider_profile_id VARCHAR(80) NOT NULL REFERENCES ai_provider_profile (profile_id),
    protocol VARCHAR(16) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    reasoning_effort VARCHAR(24) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(18, 8) NOT NULL DEFAULT 0,
    latency_milliseconds BIGINT,
    finish_reason VARCHAR(80),
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_ai_invocation_id CHECK (invocation_id ~ '^invocation_[a-z0-9_-]{4,63}$'),
    CONSTRAINT ck_ai_invocation_protocol CHECK (protocol IN ('CHAT', 'RESPONSES')),
    CONSTRAINT ck_ai_invocation_reasoning CHECK (reasoning_effort IN (
        'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
    )),
    CONSTRAINT ck_ai_invocation_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ai_invocation_status CHECK (status IN ('STARTED', 'STREAMING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_ai_invocation_usage CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND estimated_cost_usd >= 0
        AND (latency_milliseconds IS NULL OR latency_milliseconds >= 0)
    ),
    CONSTRAINT ck_ai_invocation_time CHECK (completed_at IS NULL OR completed_at >= started_at)
);

CREATE INDEX ix_ai_invocation_run_started
    ON ai_invocation (run_id, started_at, id);

CREATE TABLE ai_stream_chunk (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invocation_id VARCHAR(80) NOT NULL REFERENCES ai_invocation (invocation_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    content TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ai_stream_chunk_sequence UNIQUE (invocation_id, sequence),
    CONSTRAINT ck_ai_stream_chunk_sequence CHECK (sequence > 0),
    CONSTRAINT ck_ai_stream_chunk_content CHECK (length(content) BETWEEN 1 AND 8192)
);

CREATE TABLE ai_budget_reservation (
    invocation_id VARCHAR(80) PRIMARY KEY REFERENCES ai_invocation (invocation_id) ON DELETE CASCADE,
    run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    reserved_tokens BIGINT NOT NULL,
    reserved_cost_usd NUMERIC(18, 8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    released_at TIMESTAMPTZ,
    CONSTRAINT ck_ai_budget_reservation_values CHECK (reserved_tokens > 0 AND reserved_cost_usd >= 0),
    CONSTRAINT ck_ai_budget_reservation_status CHECK (status IN ('RESERVED', 'RELEASED')),
    CONSTRAINT ck_ai_budget_reservation_release CHECK (
        (status = 'RELEASED' AND released_at IS NOT NULL) OR status = 'RESERVED'
    )
);

--rollback DROP TABLE IF EXISTS ai_budget_reservation;
--rollback DROP TABLE IF EXISTS ai_stream_chunk;
--rollback DROP TABLE IF EXISTS ai_invocation;
--rollback DROP TABLE IF EXISTS agent_message_reply;
--rollback DROP TABLE IF EXISTS agent_message;
--rollback DROP TABLE IF EXISTS debate_session;
--rollback DROP TABLE IF EXISTS workflow_node_checkpoint;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS total_cost_usd;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS reserved_cost_usd;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS reserved_tokens;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS total_output_tokens;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS total_input_tokens;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS current_node_id;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS next_event_sequence;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS workflow_version_id;
--rollback DROP TABLE IF EXISTS workflow_edge_definition;
--rollback DROP TABLE IF EXISTS workflow_node_definition;
--rollback DROP TABLE IF EXISTS agent_role_template;
--rollback DROP TABLE IF EXISTS workflow_definition_version;
--rollback DROP TABLE IF EXISTS workflow_definition;

--changeset codex:004-workflow-immutability-function splitStatements:false
CREATE FUNCTION reject_published_workflow_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM workflow_definition_version version
        WHERE version.version_id = COALESCE(OLD.version_id, NEW.version_id)
          AND version.status IN ('PUBLISHED', 'ARCHIVED')
    ) THEN
        RAISE EXCEPTION 'Published workflow versions are immutable';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

--rollback DROP FUNCTION IF EXISTS reject_published_workflow_mutation();

--changeset codex:004-workflow-default-seed splitStatements:true endDelimiter:;
CREATE TRIGGER trg_workflow_node_immutable
    BEFORE UPDATE OR DELETE ON workflow_node_definition
    FOR EACH ROW EXECUTE FUNCTION reject_published_workflow_mutation();
CREATE TRIGGER trg_workflow_edge_immutable
    BEFORE UPDATE OR DELETE ON workflow_edge_definition
    FOR EACH ROW EXECUTE FUNCTION reject_published_workflow_mutation();

INSERT INTO workflow_definition (definition_id, name, description, built_in)
VALUES (
    'workflow_standard_product_research',
    '标准产品研究',
    '收集与证据审查、三家混合多轮辩论、风险复核和主席仲裁的默认工作流',
    TRUE
);

INSERT INTO workflow_definition_version (
    version_id, definition_id, version_number, status, default_debate_rounds,
    maximum_steps, maximum_duration_seconds, maximum_tokens, maximum_cost_usd,
    failure_policy, checksum, published_at
) VALUES (
    'workflowversion_standard_v1', 'workflow_standard_product_research', 1, 'PUBLISHED', 3,
    100, 1800, 500000, 2.00, 'STOP',
    'd67fd4f1ff03b657fa34e96aca5a23c85d1d658f01b4426cfc9f504d8ae42470', CURRENT_TIMESTAMP
);

INSERT INTO agent_role_template (
    role_template_id, display_name, objective, system_prompt, user_prompt_template,
    output_contract, default_provider_profile_id, default_model_name,
    default_reasoning_effort, built_in
) VALUES
    ('role_evidence_analyst', '证据分析员', '区分事实、推断和缺口，并审计证据引用。',
     '你是证据分析员。只依据给定证据区分事实、推断和缺口，禁止把 AI 摘要当作原始证据。输出严格 JSON。',
     '审查研究输入、原始证据和前序观点，输出 summary、argument、confidence、claims、evidence_refs、challenges、revision_notes。',
     'DEBATE_ARGUMENT', 'provider_mimo_default', 'mimo-v2.5-pro', 'HIGH', TRUE),
    ('role_bull_analyst', '看多分析员', '构造有证据支持的上行路径并明确失效条件。',
     '你是看多分析员。寻找有证据支持的上行路径，同时明确失效条件；不得忽略反方证据。输出严格 JSON。',
     '基于研究输入和已传入观点提出可验证的看多论证，输出标准辩论 JSON。',
     'DEBATE_ARGUMENT', 'provider_deepseek_default', 'deepseek-chat', 'HIGH', TRUE),
    ('role_bear_analyst', '看空分析员', '构造下行风险、反例和拥挤交易风险。',
     '你是看空分析员。寻找下行风险、反例和拥挤交易风险，同时引用证据。输出严格 JSON。',
     '基于研究输入和已传入观点提出可验证的看空论证，输出标准辩论 JSON。',
     'DEBATE_ARGUMENT', 'provider_mimo_default', 'mimo-v2.5-pro', 'HIGH', TRUE),
    ('role_market_structure', '市场结构分析员', '核对流动性、波动、资金费和跨交易所一致性。',
     '你是市场结构分析员。核对价格、流动性、波动、资金费和跨交易所一致性，输出严格 JSON。',
     '分析市场结构是否确认研究结论，并质询其他角色，输出标准辩论 JSON。',
     'DEBATE_ARGUMENT', 'provider_sub2api_default', 'gpt-5.6-terra', 'XHIGH', TRUE),
    ('role_risk_controller', '风险控制员', '综合各方观点并执行不可放宽的风险门禁。',
     '你是风险控制员。综合全部上游观点，识别证据不足、集中度、流动性和执行风险，不得放宽门禁。输出严格 JSON。',
     '对本轮全部观点交叉质询并给出风险修订，输出标准辩论 JSON。',
     'RISK_ASSESSMENT', 'provider_deepseek_default', 'deepseek-chat', 'HIGH', TRUE),
    ('role_chair_arbiter', '主席仲裁', '独立综合完整辩论并保留主要分歧和缺失证据。',
     '你是独立主席。综合每轮论证、质询和修订，保留主要分歧和缺失证据；不得创建输入中不存在的交易参数。输出严格 JSON。',
     '对完整多轮辩论进行独立仲裁，输出 debate_summary、major_disagreements、missing_evidence、verdicts。',
     'CHAIR_VERDICT', 'provider_sub2api_default', 'gpt-5.6-sol', 'MAX', TRUE);

INSERT INTO workflow_node_definition (
    version_id, node_id, node_type, display_name, role_name, role_template_id,
    provider_profile_id, model_name, reasoning_effort, system_prompt,
    user_prompt_template, output_contract, context_mode, context_history_rounds,
    context_max_messages, maximum_output_tokens, timeout_seconds,
    retry_max_attempts, retry_backoff_seconds, operation, position_x, position_y
) VALUES
    ('workflowversion_standard_v1', 'node_research_input', 'INPUT', '研究输入', NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, 'NONE', 0, 0, 4096, 120, 1, 0,
     'research_input', 0, 240),
    ('workflowversion_standard_v1', 'node_source_collector', 'COLLECTOR', '信息源采集', NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, 'UPSTREAM', 0, 0, 4096, 300, 3, 5,
     'collect_enabled_sources', 180, 240),
    ('workflowversion_standard_v1', 'node_evidence_cleaner', 'CLEANER', '证据清洗与去重', NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, 'UPSTREAM', 0, 0, 4096, 120, 1, 0,
     'normalize_and_deduplicate', 360, 240),
    ('workflowversion_standard_v1', 'node_information_compressor', 'COMPRESSOR', 'AI 信息压缩', 'Information Compressor', NULL,
     'provider_mimo_default', 'mimo-v2.5-pro', 'HIGH',
     '你是研究信息压缩器。只能压缩输入文档，不得新增事实、预测或交易动作；必须保留 document_id、evidence_id、source_id 引用。只输出严格 JSON。',
     '压缩单个文档，输出 summary、key_points、risks、missing_evidence、citations。',
     'RESEARCH_FINDINGS', 'UPSTREAM', 0, 24, 4096, 180, 2, 2, NULL, 540, 240),
    ('workflowversion_standard_v1', 'node_quant_research', 'QUANT', '量化研究', NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, 'UPSTREAM', 0, 0, 4096, 600, 2, 5,
     'statistical_analysis', 660, 240),
    ('workflowversion_standard_v1', 'node_evidence_analyst', 'AGENT', '证据分析员', 'Evidence Analyst', 'role_evidence_analyst',
     'provider_mimo_default', 'mimo-v2.5-pro', 'HIGH',
     '你是证据分析员。只依据给定证据区分事实、推断和缺口，禁止把 AI 摘要当作原始证据。输出严格 JSON。',
     '审查研究输入、原始证据和前序观点，输出 summary、argument、confidence、claims、evidence_refs、challenges、revision_notes。',
     'DEBATE_ARGUMENT', 'LATEST', 3, 24, 4096, 180, 2, 2, NULL, 860, 40),
    ('workflowversion_standard_v1', 'node_bull_analyst', 'AGENT', '看多分析员', 'Bull Analyst', 'role_bull_analyst',
     'provider_deepseek_default', 'deepseek-chat', 'HIGH',
     '你是看多分析员。寻找有证据支持的上行路径，同时明确失效条件；不得忽略反方证据。输出严格 JSON。',
     '基于研究输入和已传入观点提出可验证的看多论证，输出标准辩论 JSON。',
     'DEBATE_ARGUMENT', 'LATEST', 3, 24, 4096, 180, 2, 2, NULL, 860, 170),
    ('workflowversion_standard_v1', 'node_bear_analyst', 'AGENT', '看空分析员', 'Bear Analyst', 'role_bear_analyst',
     'provider_mimo_default', 'mimo-v2.5-pro', 'HIGH',
     '你是看空分析员。寻找下行风险、反例和拥挤交易风险，同时引用证据。输出严格 JSON。',
     '基于研究输入和已传入观点提出可验证的看空论证，输出标准辩论 JSON。',
     'DEBATE_ARGUMENT', 'LATEST', 3, 24, 4096, 180, 2, 2, NULL, 860, 300),
    ('workflowversion_standard_v1', 'node_market_structure', 'AGENT', '市场结构分析员', 'Market Structure Analyst', 'role_market_structure',
     'provider_sub2api_default', 'gpt-5.6-terra', 'XHIGH',
     '你是市场结构分析员。核对价格、流动性、波动、资金费和跨交易所一致性，输出严格 JSON。',
     '分析市场结构是否确认研究结论，并质询其他角色，输出标准辩论 JSON。',
     'DEBATE_ARGUMENT', 'LATEST', 3, 24, 4096, 240, 2, 2, NULL, 860, 430),
    ('workflowversion_standard_v1', 'node_risk_controller', 'AGENT', '风险控制员', 'Risk Controller', 'role_risk_controller',
     'provider_deepseek_default', 'deepseek-chat', 'HIGH',
     '你是风险控制员。综合全部上游观点，识别证据不足、集中度、流动性和执行风险，不得放宽门禁。输出严格 JSON。',
     '对本轮全部观点交叉质询并给出风险修订，输出标准辩论 JSON。',
     'RISK_ASSESSMENT', 'UPSTREAM', 3, 32, 4096, 180, 2, 2, NULL, 980, 240),
    ('workflowversion_standard_v1', 'node_chair_arbiter', 'CHAIR', '主席仲裁', 'Chair Arbiter', 'role_chair_arbiter',
     'provider_sub2api_default', 'gpt-5.6-sol', 'MAX',
     '你是独立主席。综合每轮论证、质询和修订，保留主要分歧和缺失证据；不得创建输入中不存在的交易参数。输出严格 JSON。',
     '对完整多轮辩论进行独立仲裁，输出 debate_summary、major_disagreements、missing_evidence、verdicts。',
     'CHAIR_VERDICT', 'UPSTREAM', 3, 48, 8192, 300, 1, 0, NULL, 1200, 240),
    ('workflowversion_standard_v1', 'node_research_output', 'OUTPUT', '研究结果', NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, 'UPSTREAM', 0, 16, 4096, 120, 1, 0,
     'research_output', 1420, 240);

INSERT INTO workflow_edge_definition (
    version_id, edge_id, source_node_id, target_node_id, activation_mode, context_mode
) VALUES
    ('workflowversion_standard_v1', 'edge_input_collector', 'node_research_input', 'node_source_collector', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_collector_cleaner', 'node_source_collector', 'node_evidence_cleaner', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_cleaner_compressor', 'node_evidence_cleaner', 'node_information_compressor', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_compressor_quant', 'node_information_compressor', 'node_quant_research', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_quant_evidence', 'node_quant_research', 'node_evidence_analyst', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_quant_bull', 'node_quant_research', 'node_bull_analyst', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_quant_bear', 'node_quant_research', 'node_bear_analyst', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_quant_market', 'node_quant_research', 'node_market_structure', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_evidence_risk', 'node_evidence_analyst', 'node_risk_controller', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_bull_risk', 'node_bull_analyst', 'node_risk_controller', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_bear_risk', 'node_bear_analyst', 'node_risk_controller', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_market_risk', 'node_market_structure', 'node_risk_controller', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_risk_chair', 'node_risk_controller', 'node_chair_arbiter', 'ALL', 'INCLUDE'),
    ('workflowversion_standard_v1', 'edge_chair_output', 'node_chair_arbiter', 'node_research_output', 'ALL', 'INCLUDE');

--rollback DROP TRIGGER IF EXISTS trg_workflow_edge_immutable ON workflow_edge_definition;
--rollback DROP TRIGGER IF EXISTS trg_workflow_node_immutable ON workflow_node_definition;
--rollback DELETE FROM workflow_edge_definition WHERE version_id = 'workflowversion_standard_v1';
--rollback DELETE FROM workflow_node_definition WHERE version_id = 'workflowversion_standard_v1';
--rollback DELETE FROM workflow_definition_version WHERE version_id = 'workflowversion_standard_v1';
--rollback DELETE FROM workflow_definition WHERE definition_id = 'workflow_standard_product_research';
--rollback DELETE FROM agent_role_template WHERE role_template_id IN ('role_evidence_analyst', 'role_bull_analyst', 'role_bear_analyst', 'role_market_structure', 'role_risk_controller', 'role_chair_arbiter');
