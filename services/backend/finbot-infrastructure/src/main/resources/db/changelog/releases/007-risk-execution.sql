--liquibase formatted sql

--changeset codex:007-risk-execution splitStatements:true endDelimiter:;
CREATE TABLE trade_automation_run (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    automation_run_id VARCHAR(80) NOT NULL UNIQUE,
    workflow_run_id VARCHAR(80) NOT NULL UNIQUE REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL,
    decision_id VARCHAR(80),
    proposal_id VARCHAR(80),
    risk_assessment_id VARCHAR(80),
    intent_id VARCHAR(80),
    order_id VARCHAR(80),
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_trade_automation_run_id CHECK (
        automation_run_id ~ '^automation_[a-z0-9_-]{4,65}$'
    ),
    CONSTRAINT ck_trade_automation_status CHECK (status IN (
        'STARTED', 'NO_ACTION', 'BLOCKED', 'ORDER_PLANNED',
        'SUBMITTED', 'COMPLETED', 'FAILED'
    )),
    CONSTRAINT ck_trade_automation_time CHECK (
        completed_at IS NULL OR completed_at >= started_at
    )
);

CREATE TABLE trade_execution_ai_stage (
    stage VARCHAR(24) PRIMARY KEY,
    provider_profile_id VARCHAR(80) NOT NULL REFERENCES ai_provider_profile (profile_id),
    model_name VARCHAR(160) NOT NULL,
    reasoning_effort VARCHAR(24) NOT NULL,
    system_prompt TEXT NOT NULL,
    user_prompt_template TEXT NOT NULL,
    maximum_output_tokens INTEGER NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_trade_execution_ai_stage CHECK (stage IN ('DRAFT', 'REFLECTION')),
    CONSTRAINT ck_trade_execution_ai_reasoning CHECK (reasoning_effort IN (
        'PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'
    )),
    CONSTRAINT ck_trade_execution_ai_limits CHECK (
        maximum_output_tokens BETWEEN 256 AND 16384
        AND timeout_seconds BETWEEN 10 AND 1800
        AND version >= 0
    )
);

CREATE TABLE trade_execution_ai_review (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    review_id VARCHAR(80) NOT NULL UNIQUE,
    automation_run_id VARCHAR(80) NOT NULL REFERENCES trade_automation_run (automation_run_id) ON DELETE CASCADE,
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    stage VARCHAR(24) NOT NULL,
    invocation_id VARCHAR(80) REFERENCES ai_invocation (invocation_id),
    status VARCHAR(24) NOT NULL,
    output JSONB,
    output_hash CHAR(64),
    error_code VARCHAR(80),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_trade_execution_ai_review_stage UNIQUE (automation_run_id, stage),
    CONSTRAINT ck_trade_execution_ai_review_id CHECK (
        review_id ~ '^review_[a-z0-9_-]{4,69}$'
    ),
    CONSTRAINT ck_trade_execution_ai_review_stage CHECK (stage IN ('DRAFT', 'REFLECTION')),
    CONSTRAINT ck_trade_execution_ai_review_status CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT ck_trade_execution_ai_review_output CHECK (
        (status = 'COMPLETED' AND output IS NOT NULL AND jsonb_typeof(output) = 'object'
            AND output_hash ~ '^[0-9a-f]{64}$')
        OR (status = 'FAILED' AND output IS NULL AND output_hash IS NULL)
    )
);

CREATE TABLE risk_policy (
    policy_version VARCHAR(80) PRIMARY KEY,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    test_environment_only BOOLEAN NOT NULL DEFAULT TRUE,
    minimum_confidence NUMERIC(5, 4) NOT NULL,
    risk_budget_usdt NUMERIC(20, 8) NOT NULL,
    maximum_notional_usdt NUMERIC(20, 8) NOT NULL,
    maximum_leverage NUMERIC(12, 4) NOT NULL,
    maximum_open_positions INTEGER NOT NULL,
    maximum_stop_distance NUMERIC(8, 6) NOT NULL,
    taker_fee_rate NUMERIC(10, 8) NOT NULL,
    slippage_rate NUMERIC(10, 8) NOT NULL,
    liquidation_buffer_rate NUMERIC(10, 8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_risk_policy_values CHECK (
        minimum_confidence BETWEEN 0 AND 1
        AND risk_budget_usdt > 0
        AND maximum_notional_usdt > 0
        AND maximum_leverage >= 1 AND maximum_leverage <= 100
        AND maximum_open_positions BETWEEN 1 AND 100
        AND maximum_stop_distance > 0 AND maximum_stop_distance < 1
        AND taker_fee_rate >= 0 AND taker_fee_rate < 0.1
        AND slippage_rate >= 0 AND slippage_rate < 0.1
        AND liquidation_buffer_rate >= 0 AND liquidation_buffer_rate < 0.1
    )
);

CREATE UNIQUE INDEX uq_risk_policy_active ON risk_policy (active) WHERE active;

CREATE TABLE risk_assessment (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assessment_id VARCHAR(80) NOT NULL UNIQUE,
    automation_run_id VARCHAR(80) NOT NULL REFERENCES trade_automation_run (automation_run_id) ON DELETE CASCADE,
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    proposal_id VARCHAR(80) NOT NULL REFERENCES trade_proposal (proposal_id),
    account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    policy_version VARCHAR(80) NOT NULL REFERENCES risk_policy (policy_version),
    status VARCHAR(16) NOT NULL,
    reasons JSONB NOT NULL,
    quantity NUMERIC(38, 18),
    notional_usdt NUMERIC(38, 18),
    leverage NUMERIC(12, 4),
    initial_margin_usdt NUMERIC(38, 18),
    estimated_max_loss_usdt NUMERIC(38, 18),
    approximate_liquidation_price NUMERIC(38, 18),
    assessed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_risk_assessment_proposal_account UNIQUE (proposal_id, account_id),
    CONSTRAINT ck_risk_assessment_id CHECK (
        assessment_id ~ '^assessment_[a-z0-9_-]{4,65}$'
    ),
    CONSTRAINT ck_risk_assessment_status CHECK (status IN ('APPROVED', 'BLOCKED')),
    CONSTRAINT ck_risk_assessment_reasons CHECK (jsonb_typeof(reasons) = 'array'),
    CONSTRAINT ck_risk_assessment_values CHECK (
        (status = 'APPROVED' AND quantity > 0 AND notional_usdt > 0
            AND leverage >= 1 AND initial_margin_usdt > 0
            AND estimated_max_loss_usdt > 0 AND approximate_liquidation_price > 0)
        OR (status = 'BLOCKED' AND quantity IS NULL AND notional_usdt IS NULL
            AND leverage IS NULL AND initial_margin_usdt IS NULL
            AND estimated_max_loss_usdt IS NULL AND approximate_liquidation_price IS NULL)
    )
);

ALTER TABLE approved_trade_intent
    DROP CONSTRAINT approved_trade_intent_proposal_id_key,
    ADD COLUMN account_id VARCHAR(80) NOT NULL REFERENCES exchange_account (account_id),
    ADD COLUMN risk_assessment_id VARCHAR(80) NOT NULL UNIQUE REFERENCES risk_assessment (assessment_id),
    ADD COLUMN leverage NUMERIC(12, 4) NOT NULL,
    ADD CONSTRAINT uq_trade_intent_proposal_account UNIQUE (proposal_id, account_id),
    ADD CONSTRAINT ck_trade_intent_leverage CHECK (leverage >= 1 AND leverage <= 100);

ALTER TABLE oms_order
    DROP CONSTRAINT ck_oms_status,
    ADD COLUMN environment VARCHAR(16) NOT NULL,
    ADD COLUMN leverage NUMERIC(12, 4) NOT NULL,
    ADD COLUMN submission_claim_owner VARCHAR(120),
    ADD COLUMN submission_claim_until TIMESTAMPTZ,
    ADD CONSTRAINT fk_oms_order_account
        FOREIGN KEY (account_ref) REFERENCES exchange_account (account_id),
    ADD CONSTRAINT ck_oms_order_environment CHECK (environment IN ('TESTNET', 'DEMO')),
    ADD CONSTRAINT ck_oms_order_leverage CHECK (leverage >= 1 AND leverage <= 100),
    ADD CONSTRAINT ck_oms_status CHECK (status IN (
        'PLANNED', 'SUBMITTING', 'SUBMITTED', 'PARTIALLY_FILLED', 'FILLED',
        'CANCELLED', 'REJECTED', 'EXPIRED', 'RECONCILED'
    )),
    ADD CONSTRAINT ck_oms_submission_claim CHECK (
        (status = 'SUBMITTING' AND submission_claim_owner IS NOT NULL
            AND submission_claim_until IS NOT NULL)
        OR status <> 'SUBMITTING'
    );

CREATE TABLE exchange_submission_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_id VARCHAR(80) NOT NULL UNIQUE,
    order_id VARCHAR(80) NOT NULL REFERENCES oms_order (order_id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    exchange_order_id VARCHAR(120),
    http_status INTEGER,
    response_payload JSONB,
    error_code VARCHAR(80),
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_exchange_submission_attempt UNIQUE (order_id, attempt_number),
    CONSTRAINT ck_exchange_submission_attempt_id CHECK (
        attempt_id ~ '^attempt_[a-z0-9_-]{4,68}$'
    ),
    CONSTRAINT ck_exchange_submission_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_exchange_submission_status CHECK (status IN (
        'STARTED', 'ACKNOWLEDGED', 'REJECTED', 'UNKNOWN'
    )),
    CONSTRAINT ck_exchange_submission_response CHECK (
        response_payload IS NULL OR jsonb_typeof(response_payload) = 'object'
    ),
    CONSTRAINT ck_exchange_submission_time CHECK (
        completed_at IS NULL OR completed_at >= started_at
    )
);

INSERT INTO trade_execution_ai_stage (
    stage, provider_profile_id, model_name, reasoning_effort, system_prompt,
    user_prompt_template, maximum_output_tokens, timeout_seconds, enabled
) VALUES
    ('DRAFT', 'provider_sub2api_default', 'gpt-5.6-sol', 'MAX',
     '你是模拟交易执行机器人。仅根据主席裁决、可追溯证据、量化指标和真实市场价格生成严格 JSON 决策。不得输出思维链，不得猜测缺失价格，不得创建输入中不存在的标的。证据不足时必须 WATCH 或 HOLD。',
     '生成 action、symbol、confidence、entry_reference、target_price、invalidation_price、rationale、evidence_refs。action 只能是 BUY、SELL、HOLD、WATCH。',
     4096, 300, TRUE),
    ('REFLECTION', 'provider_sub2api_default', 'gpt-5.6-sol', 'MAX',
     '你是最终执行反思机器人。独立检查初稿决策是否受到证据、价格、量化结果和风险边界支持。不得输出思维链。任何字段矛盾、证据缺失或风险不清晰都必须 REJECT。',
     '输出 verdict、reasons 和 decision。verdict 只能 APPROVE 或 REJECT；APPROVE 时 decision 必须是完整修订后的交易决策。',
     4096, 300, TRUE);

INSERT INTO risk_policy (
    policy_version, active, test_environment_only, minimum_confidence,
    risk_budget_usdt, maximum_notional_usdt, maximum_leverage,
    maximum_open_positions, maximum_stop_distance, taker_fee_rate,
    slippage_rate, liquidation_buffer_rate
) VALUES (
    'paper-default-v1', TRUE, TRUE, 0.6500,
    5.00000000, 100.00000000, 20.0000,
    3, 0.100000, 0.00060000, 0.00050000, 0.00200000
);

INSERT INTO schedule_definition (
    schedule_id, display_name, task_type, payload, enabled, interval_seconds,
    priority, maximum_attempts, next_run_at
) VALUES
    ('schedule_gate_order_reconciliation', 'Gate TestNet 订单对账', 'ORDER_RECONCILIATION',
     '{"accountId":"account_gate_testnet_default"}'::jsonb, TRUE, 60, 85, 10, CURRENT_TIMESTAMP),
    ('schedule_bybit_order_reconciliation', 'Bybit Demo 订单对账', 'ORDER_RECONCILIATION',
     '{"accountId":"account_bybit_demo_default"}'::jsonb, TRUE, 60, 85, 10, CURRENT_TIMESTAMP);

--rollback DROP TABLE IF EXISTS exchange_submission_attempt;
--rollback DELETE FROM schedule_definition WHERE schedule_id IN ('schedule_gate_order_reconciliation', 'schedule_bybit_order_reconciliation');
--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS ck_oms_order_leverage;
--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS ck_oms_order_environment;
--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS ck_oms_submission_claim;
--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS ck_oms_status;
--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS fk_oms_order_account;
--rollback ALTER TABLE oms_order DROP COLUMN IF EXISTS submission_claim_until;
--rollback ALTER TABLE oms_order DROP COLUMN IF EXISTS submission_claim_owner;
--rollback ALTER TABLE oms_order DROP COLUMN IF EXISTS leverage;
--rollback ALTER TABLE oms_order DROP COLUMN IF EXISTS environment;
--rollback ALTER TABLE oms_order ADD CONSTRAINT ck_oms_status CHECK (status IN ('PLANNED', 'SUBMITTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'RECONCILED'));
--rollback ALTER TABLE approved_trade_intent DROP CONSTRAINT IF EXISTS ck_trade_intent_leverage;
--rollback ALTER TABLE approved_trade_intent DROP COLUMN IF EXISTS leverage;
--rollback ALTER TABLE approved_trade_intent DROP COLUMN IF EXISTS risk_assessment_id;
--rollback ALTER TABLE approved_trade_intent DROP COLUMN IF EXISTS account_id;
--rollback ALTER TABLE approved_trade_intent ADD CONSTRAINT approved_trade_intent_proposal_id_key UNIQUE (proposal_id);
--rollback DROP TABLE IF EXISTS risk_assessment;
--rollback DROP INDEX IF EXISTS uq_risk_policy_active;
--rollback DROP TABLE IF EXISTS risk_policy;
--rollback DROP TABLE IF EXISTS trade_execution_ai_review;
--rollback DROP TABLE IF EXISTS trade_execution_ai_stage;
--rollback DROP TABLE IF EXISTS trade_automation_run;
