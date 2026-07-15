--liquibase formatted sql

--changeset codex:023-estimated-trading splitStatements:true endDelimiter:;
ALTER TABLE trade_automation_run
    DROP CONSTRAINT ck_trade_automation_status,
    ADD CONSTRAINT ck_trade_automation_status CHECK (status IN (
        'STARTED', 'NO_ACTION', 'BLOCKED', 'ESTIMATED', 'ORDER_PLANNED',
        'SUBMITTED', 'COMPLETED', 'FAILED'
    ));

CREATE TABLE estimated_trade_projection (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    projection_id VARCHAR(80) NOT NULL UNIQUE,
    automation_run_id VARCHAR(80) NOT NULL
        REFERENCES trade_automation_run (automation_run_id) ON DELETE CASCADE,
    workflow_run_id VARCHAR(80) NOT NULL
        REFERENCES workflow_run (run_id) ON DELETE CASCADE,
    proposal_id VARCHAR(80) NOT NULL REFERENCES trade_proposal (proposal_id),
    instrument_id VARCHAR(80) NOT NULL REFERENCES venue_instrument (instrument_id),
    exchange VARCHAR(16) NOT NULL,
    symbol VARCHAR(48) NOT NULL,
    side VARCHAR(8) NOT NULL,
    policy_version VARCHAR(80) NOT NULL REFERENCES risk_policy (policy_version),
    entry_reference NUMERIC(38, 18) NOT NULL,
    market_price NUMERIC(38, 18) NOT NULL,
    target_price NUMERIC(38, 18) NOT NULL,
    stop_price NUMERIC(38, 18) NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    contract_size NUMERIC(38, 18) NOT NULL,
    notional_usdt NUMERIC(38, 18) NOT NULL,
    leverage NUMERIC(18, 8) NOT NULL,
    initial_margin_usdt NUMERIC(38, 18) NOT NULL,
    estimated_entry_cost_usdt NUMERIC(38, 18) NOT NULL,
    estimated_target_exit_cost_usdt NUMERIC(38, 18) NOT NULL,
    estimated_stop_exit_cost_usdt NUMERIC(38, 18) NOT NULL,
    estimated_profit_usdt NUMERIC(38, 18) NOT NULL,
    estimated_loss_usdt NUMERIC(38, 18) NOT NULL,
    risk_reward_ratio NUMERIC(38, 18) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_estimated_trade_proposal_instrument UNIQUE (proposal_id, instrument_id),
    CONSTRAINT ck_estimated_trade_projection_id CHECK (
        projection_id ~ '^projection_[a-z0-9_-]{4,65}$'
    ),
    CONSTRAINT ck_estimated_trade_exchange CHECK (exchange IN ('GATE', 'BYBIT')),
    CONSTRAINT ck_estimated_trade_symbol CHECK (symbol ~ '^[A-Z0-9_-]{2,48}$'),
    CONSTRAINT ck_estimated_trade_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_estimated_trade_values CHECK (
        entry_reference > 0 AND market_price > 0 AND target_price > 0 AND stop_price > 0
        AND quantity > 0 AND contract_size > 0 AND notional_usdt > 0
        AND leverage >= 1 AND initial_margin_usdt > 0
        AND estimated_entry_cost_usdt >= 0
        AND estimated_target_exit_cost_usdt >= 0
        AND estimated_stop_exit_cost_usdt >= 0
        AND estimated_profit_usdt > 0 AND estimated_loss_usdt > 0
        AND risk_reward_ratio > 0
    )
);

CREATE INDEX ix_estimated_trade_projection_workflow
    ON estimated_trade_projection (workflow_run_id, calculated_at DESC, id DESC);

CREATE VIEW trading_activity_projection AS
SELECT 'decision:' || decision.decision_id AS activity_id,
       decision.decision_id AS source_event_id,
       'DECISION'::VARCHAR AS activity_type,
       'LOCAL_OMS'::VARCHAR AS source,
       NULL::VARCHAR AS account_id,
       NULL::VARCHAR AS exchange,
       decision.symbol,
       decision.action AS status,
       CASE WHEN decision.action IN ('BUY', 'SELL') THEN decision.action END AS side,
       NULL::NUMERIC AS quantity,
       decision.entry_reference AS price,
       NULL::NUMERIC AS amount,
       'USDT'::VARCHAR AS currency,
       NULL::VARCHAR AS exchange_order_id,
       NULL::VARCHAR AS client_order_id,
       'AI 决策'::TEXT AS title,
       CASE decision.action
           WHEN 'WATCH' THEN '观察：证据或交易条件不足，不创建订单'
           WHEN 'HOLD' THEN '等待：维持当前状态，不创建订单'
           ELSE '方向性建议，等待产品映射与确定性风控处理'
       END::TEXT AS detail,
       jsonb_strip_nulls(jsonb_build_object(
           'workflowRunId', decision.workflow_run_id,
           'decisionId', decision.decision_id,
           'decisionKind', decision.decision_kind,
           'confidence', decision.confidence,
           'entryReference', decision.entry_reference,
           'targetPrice', decision.target_price,
           'invalidationPrice', decision.invalidation_price,
           'rationale', decision.rationale
       )) AS details,
       decision.created_at AS occurred_at,
       decision.created_at AS received_at
FROM trade_decision decision

UNION ALL

SELECT 'proposal:' || proposal.proposal_id,
       proposal.proposal_id,
       'PROPOSAL',
       'LOCAL_OMS',
       NULL::VARCHAR,
       NULL::VARCHAR,
       proposal.symbol,
       proposal.status,
       proposal.action,
       NULL::NUMERIC,
       proposal.entry_reference,
       NULL::NUMERIC,
       'USDT',
       NULL::VARCHAR,
       NULL::VARCHAR,
       '交易建议',
       '方向性建议已生成；它本身不是订单，也不会绕过风控',
       jsonb_strip_nulls(jsonb_build_object(
           'proposalId', proposal.proposal_id,
           'decisionId', proposal.decision_id,
           'entryReference', proposal.entry_reference,
           'targetPrice', proposal.target_price,
           'invalidationPrice', proposal.invalidation_price,
           'supersededAt', proposal.superseded_at
       )),
       proposal.created_at,
       proposal.created_at
FROM trade_proposal proposal

UNION ALL

SELECT 'review:' || review.review_id,
       review.review_id,
       'AI_REVIEW',
       'LOCAL_OMS',
       NULL::VARCHAR,
       NULL::VARCHAR,
       decision.symbol,
       review.status,
       CASE WHEN decision.action IN ('BUY', 'SELL') THEN decision.action END,
       NULL::NUMERIC,
       decision.entry_reference,
       NULL::NUMERIC,
       'USDT',
       NULL::VARCHAR,
       NULL::VARCHAR,
       CASE review.stage WHEN 'DRAFT' THEN '最终执行机器人初审' ELSE '最终执行机器人反思终审' END,
       CASE review.status WHEN 'COMPLETED' THEN 'AI 审查已完成' ELSE coalesce(review.error_message, 'AI 审查失败') END,
       jsonb_strip_nulls(jsonb_build_object(
           'reviewId', review.review_id,
           'automationRunId', review.automation_run_id,
           'workflowRunId', review.workflow_run_id,
           'stage', review.stage,
           'providerProfileId', invocation.provider_profile_id,
           'modelName', invocation.model_name,
           'reasoningEffort', invocation.reasoning_effort,
           'outputHash', review.output_hash,
           'errorCode', review.error_code,
           'errorMessage', review.error_message
       )),
       review.created_at,
       review.created_at
FROM trade_execution_ai_review review
LEFT JOIN LATERAL (
    SELECT candidate.symbol, candidate.action, candidate.entry_reference
    FROM trade_decision candidate
    WHERE candidate.workflow_run_id = review.workflow_run_id
    ORDER BY candidate.created_at DESC, candidate.id DESC
    LIMIT 1
) decision ON TRUE
LEFT JOIN ai_invocation invocation ON invocation.invocation_id = review.invocation_id

UNION ALL

SELECT 'risk:' || assessment.assessment_id,
       assessment.assessment_id,
       'RISK_ASSESSMENT',
       'LOCAL_OMS',
       assessment.account_id,
       account.exchange,
       proposal.symbol,
       assessment.status,
       proposal.action,
       assessment.quantity,
       proposal.entry_reference,
       assessment.initial_margin_usdt,
       'USDT',
       NULL::VARCHAR,
       NULL::VARCHAR,
       '确定性风险评估',
       CASE assessment.status WHEN 'APPROVED' THEN '通过风险门禁，可创建模拟订单' ELSE '未通过风险门禁，不创建订单' END,
       jsonb_strip_nulls(jsonb_build_object(
           'assessmentId', assessment.assessment_id,
           'automationRunId', assessment.automation_run_id,
           'workflowRunId', assessment.workflow_run_id,
           'proposalId', assessment.proposal_id,
           'policyVersion', assessment.policy_version,
           'reasons', assessment.reasons,
           'notionalUsdt', assessment.notional_usdt,
           'leverage', assessment.leverage,
           'initialMarginUsdt', assessment.initial_margin_usdt,
           'estimatedMaximumLossUsdt', assessment.estimated_max_loss_usdt,
           'approximateLiquidationPrice', assessment.approximate_liquidation_price
       )),
       assessment.assessed_at,
       assessment.assessed_at
FROM risk_assessment assessment
JOIN trade_proposal proposal ON proposal.proposal_id = assessment.proposal_id
JOIN exchange_account account ON account.account_id = assessment.account_id

UNION ALL

SELECT 'estimate:' || projection.projection_id,
       projection.projection_id,
       'ESTIMATE',
       'LOCAL_OMS',
       NULL::VARCHAR,
       projection.exchange,
       projection.symbol,
       'ESTIMATED',
       projection.side,
       projection.quantity,
       projection.entry_reference,
       projection.estimated_profit_usdt,
       'USDT',
       NULL::VARCHAR,
       NULL::VARCHAR,
       '预估交易（不会下单）',
       '仅研究产品的内部仓位与盈亏测算，不代表交易所成交',
       jsonb_strip_nulls(jsonb_build_object(
           'projectionId', projection.projection_id,
           'automationRunId', projection.automation_run_id,
           'workflowRunId', projection.workflow_run_id,
           'proposalId', projection.proposal_id,
           'instrumentId', projection.instrument_id,
           'policyVersion', projection.policy_version,
           'marketPrice', projection.market_price,
           'targetPrice', projection.target_price,
           'stopPrice', projection.stop_price,
           'contractSize', projection.contract_size,
           'notionalUsdt', projection.notional_usdt,
           'leverage', projection.leverage,
           'initialMarginUsdt', projection.initial_margin_usdt,
           'estimatedEntryCostUsdt', projection.estimated_entry_cost_usdt,
           'estimatedTargetExitCostUsdt', projection.estimated_target_exit_cost_usdt,
           'estimatedStopExitCostUsdt', projection.estimated_stop_exit_cost_usdt,
           'estimatedProfitUsdt', projection.estimated_profit_usdt,
           'estimatedLossUsdt', projection.estimated_loss_usdt,
           'riskRewardRatio', projection.risk_reward_ratio
       )),
       projection.calculated_at,
       projection.calculated_at
FROM estimated_trade_projection projection

UNION ALL

SELECT 'oms-order:' || order_record.order_id,
       order_record.order_id,
       'OMS_ORDER',
       'LOCAL_OMS',
       order_record.account_ref,
       order_record.exchange,
       order_record.symbol,
       order_record.status,
       order_record.side,
       order_record.requested_quantity,
       order_record.average_fill_price,
       NULL::NUMERIC,
       'USDT',
       order_record.exchange_order_id,
       order_record.client_order_id,
       'OMS 模拟订单',
       'FinBot 本地订单状态；只有交易所事实记录才能证明成交',
       jsonb_strip_nulls(jsonb_build_object(
           'omsOrderId', order_record.order_id,
           'intentId', order_record.intent_id,
           'environment', order_record.environment,
           'requestedQuantity', order_record.requested_quantity,
           'filledQuantity', order_record.filled_quantity,
           'averageFillPrice', order_record.average_fill_price,
           'leverage', order_record.leverage,
           'version', order_record.version,
           'submittedAt', order_record.submitted_at,
           'terminalAt', order_record.terminal_at
       )),
       order_record.created_at,
       order_record.updated_at
FROM oms_order order_record

UNION ALL

SELECT 'oms-event:' || event.event_id,
       event.event_id,
       'OMS_EVENT',
       'LOCAL_OMS',
       order_record.account_ref,
       order_record.exchange,
       order_record.symbol,
       event.to_status,
       order_record.side,
       order_record.requested_quantity,
       order_record.average_fill_price,
       NULL::NUMERIC,
       'USDT',
       order_record.exchange_order_id,
       order_record.client_order_id,
       'OMS 状态变化',
       event.event_type,
       jsonb_strip_nulls(jsonb_build_object(
           'eventId', event.event_id,
           'omsOrderId', event.order_id,
           'sequence', event.sequence,
           'eventType', event.event_type,
           'fromStatus', event.from_status,
           'toStatus', event.to_status,
           'payload', event.payload
       )),
       event.occurred_at,
       event.recorded_at
FROM oms_order_event event
JOIN oms_order order_record ON order_record.order_id = event.order_id

UNION ALL

SELECT 'submission:' || attempt.attempt_id,
       attempt.attempt_id,
       'SUBMISSION_ATTEMPT',
       'LOCAL_OMS',
       order_record.account_ref,
       order_record.exchange,
       order_record.symbol,
       attempt.status,
       order_record.side,
       order_record.requested_quantity,
       order_record.average_fill_price,
       NULL::NUMERIC,
       'USDT',
       coalesce(attempt.exchange_order_id, order_record.exchange_order_id),
       order_record.client_order_id,
       '交易所提交尝试',
       CASE attempt.status
           WHEN 'ACKNOWLEDGED' THEN '交易所已确认接收模拟订单'
           ELSE coalesce(attempt.error_message, attempt.status)
       END,
       jsonb_strip_nulls(jsonb_build_object(
           'submissionAttemptId', attempt.attempt_id,
           'omsOrderId', attempt.order_id,
           'attemptNumber', attempt.attempt_number,
           'httpStatus', attempt.http_status,
           'errorCode', attempt.error_code,
           'errorMessage', attempt.error_message,
           'startedAt', attempt.started_at,
           'completedAt', attempt.completed_at
       )),
       attempt.started_at,
       coalesce(attempt.completed_at, attempt.started_at)
FROM exchange_submission_attempt attempt
JOIN oms_order order_record ON order_record.order_id = attempt.order_id

UNION ALL

SELECT 'exchange-account:' || snapshot.snapshot_id,
       snapshot.source_event_id,
       'ACCOUNT',
       'EXCHANGE',
       snapshot.account_id,
       account.exchange,
       NULL::VARCHAR,
       'SNAPSHOT',
       NULL::VARCHAR,
       NULL::NUMERIC,
       NULL::NUMERIC,
       snapshot.equity,
       snapshot.currency,
       NULL::VARCHAR,
       NULL::VARCHAR,
       '交易所账户快照',
       '交易所模拟账户权益快照',
       jsonb_build_object(
           'equity', snapshot.equity,
           'availableBalance', snapshot.available_balance,
           'marginBalance', snapshot.margin_balance,
           'unrealizedPnl', snapshot.unrealized_pnl
       ),
       snapshot.occurred_at,
       snapshot.received_at
FROM exchange_account_snapshot snapshot
JOIN exchange_account account USING (account_id)

UNION ALL

SELECT 'exchange-balance:' || balance.fact_id,
       balance.source_event_id,
       'BALANCE',
       'EXCHANGE',
       balance.account_id,
       account.exchange,
       NULL::VARCHAR,
       balance.reason,
       NULL::VARCHAR,
       NULL::NUMERIC,
       NULL::NUMERIC,
       coalesce(balance.change_amount, balance.total),
       balance.currency,
       NULL::VARCHAR,
       NULL::VARCHAR,
       '交易所余额变动',
       balance.reason,
       jsonb_strip_nulls(jsonb_build_object(
           'total', balance.total,
           'available', balance.available,
           'changeAmount', balance.change_amount,
           'reason', balance.reason
       )),
       balance.occurred_at,
       balance.received_at
FROM exchange_balance_fact balance
JOIN exchange_account account USING (account_id)

UNION ALL

SELECT 'exchange-order:' || order_fact.fact_id,
       order_fact.source_event_id,
       'ORDER',
       'EXCHANGE',
       order_fact.account_id,
       account.exchange,
       order_fact.symbol,
       order_fact.status,
       order_fact.side,
       order_fact.quantity,
       coalesce(order_fact.average_fill_price, order_fact.limit_price),
       NULL::NUMERIC,
       'USDT',
       order_fact.exchange_order_id,
       order_fact.client_order_id,
       '交易所订单事实',
       '来自交易所模拟账户的订单历史',
       jsonb_strip_nulls(jsonb_build_object(
           'orderType', order_fact.order_type,
           'quantity', order_fact.quantity,
           'filledQuantity', order_fact.filled_quantity,
           'limitPrice', order_fact.limit_price,
           'averageFillPrice', order_fact.average_fill_price,
           'reduceOnly', order_fact.reduce_only
       )),
       order_fact.occurred_at,
       order_fact.received_at
FROM exchange_order_fact order_fact
JOIN exchange_account account USING (account_id)

UNION ALL

SELECT 'exchange-fill:' || fill.fact_id,
       fill.source_event_id,
       'FILL',
       'EXCHANGE',
       fill.account_id,
       account.exchange,
       fill.symbol,
       'FILLED',
       fill.side,
       fill.quantity,
       fill.price,
       fill.realized_pnl,
       fill.fee_currency,
       fill.exchange_order_id,
       fill.client_order_id,
       '交易所成交事实',
       '来自交易所模拟账户的成交历史',
       jsonb_strip_nulls(jsonb_build_object(
           'exchangeFillId', fill.exchange_fill_id,
           'fee', fill.fee,
           'feeCurrency', fill.fee_currency,
           'realizedPnl', fill.realized_pnl
       )),
       fill.occurred_at,
       fill.received_at
FROM exchange_fill_fact fill
JOIN exchange_account account USING (account_id)

UNION ALL

SELECT 'exchange-position:' || position.snapshot_id,
       position.source_event_id,
       'POSITION',
       'EXCHANGE',
       position.account_id,
       account.exchange,
       position.symbol,
       position.side,
       position.side,
       position.quantity,
       position.mark_price,
       position.unrealized_pnl,
       'USDT',
       NULL::VARCHAR,
       NULL::VARCHAR,
       '交易所持仓快照',
       '模拟账户持仓与未实现盈亏快照',
       jsonb_strip_nulls(jsonb_build_object(
           'entryPrice', position.entry_price,
           'markPrice', position.mark_price,
           'liquidationPrice', position.liquidation_price,
           'leverage', position.leverage,
           'unrealizedPnl', position.unrealized_pnl,
           'margin', position.margin
       )),
       position.occurred_at,
       position.received_at
FROM exchange_position_snapshot position
JOIN exchange_account account USING (account_id)

UNION ALL

SELECT 'exchange-pnl:' || pnl.fact_id,
       pnl.source_event_id,
       'REALIZED_PNL',
       'EXCHANGE',
       pnl.account_id,
       account.exchange,
       pnl.symbol,
       pnl.source_type,
       NULL::VARCHAR,
       NULL::NUMERIC,
       NULL::NUMERIC,
       pnl.amount,
       pnl.currency,
       pnl.related_order_id,
       NULL::VARCHAR,
       '交易所已实现盈亏',
       pnl.source_type,
       jsonb_strip_nulls(jsonb_build_object(
           'relatedOrderId', pnl.related_order_id,
           'relatedFillId', pnl.related_fill_id,
           'amount', pnl.amount,
           'currency', pnl.currency
       )),
       pnl.occurred_at,
       pnl.received_at
FROM realized_pnl_fact pnl
JOIN exchange_account account USING (account_id)

UNION ALL

SELECT 'reconciliation:' || reconciliation.reconciliation_id,
       reconciliation.reconciliation_id,
       'RECONCILIATION',
       'EXCHANGE',
       reconciliation.account_id,
       account.exchange,
       NULL::VARCHAR,
       reconciliation.status,
       NULL::VARCHAR,
       NULL::NUMERIC,
       NULL::NUMERIC,
       reconciliation.discrepancy_count::NUMERIC,
       NULL::VARCHAR,
       NULL::VARCHAR,
       NULL::VARCHAR,
       '交易所对账',
       CASE reconciliation.status
           WHEN 'COMPLETED' THEN '交易所模拟账户对账完成'
           ELSE coalesce(reconciliation.error_message, reconciliation.status)
       END,
       jsonb_strip_nulls(jsonb_build_object(
           'reconciliationId', reconciliation.reconciliation_id,
           'discrepancyCount', reconciliation.discrepancy_count,
           'errorCode', reconciliation.error_code,
           'errorMessage', reconciliation.error_message,
           'completedAt', reconciliation.completed_at
       )),
       reconciliation.started_at,
       coalesce(reconciliation.completed_at, reconciliation.started_at)
FROM exchange_reconciliation_run reconciliation
JOIN exchange_account account USING (account_id);

--rollback DROP VIEW IF EXISTS trading_activity_projection;
--rollback DROP TABLE IF EXISTS estimated_trade_projection;
--rollback ALTER TABLE trade_automation_run DROP CONSTRAINT IF EXISTS ck_trade_automation_status;
--rollback ALTER TABLE trade_automation_run ADD CONSTRAINT ck_trade_automation_status CHECK (status IN ('STARTED', 'NO_ACTION', 'BLOCKED', 'ORDER_PLANNED', 'SUBMITTED', 'COMPLETED', 'FAILED'));
