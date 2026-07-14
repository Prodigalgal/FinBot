--liquibase formatted sql

--changeset codex:018-adjustable-leverage splitStatements:true endDelimiter:;
ALTER TABLE risk_policy
    ADD COLUMN preferred_leverage NUMERIC(12, 4);

UPDATE risk_policy
SET preferred_leverage = maximum_leverage;

ALTER TABLE risk_policy
    ALTER COLUMN preferred_leverage SET NOT NULL,
    DROP CONSTRAINT ck_risk_policy_values,
    ADD CONSTRAINT ck_risk_policy_values CHECK (
        minimum_confidence BETWEEN 0 AND 1
        AND risk_budget_usdt > 0
        AND maximum_notional_usdt > 0
        AND preferred_leverage >= 1
        AND maximum_leverage >= 1
        AND preferred_leverage <= maximum_leverage
        AND maximum_open_positions BETWEEN 1 AND 100
        AND maximum_stop_distance > 0 AND maximum_stop_distance < 1
        AND taker_fee_rate >= 0 AND taker_fee_rate < 0.1
        AND slippage_rate >= 0 AND slippage_rate < 0.1
        AND liquidation_buffer_rate >= 0 AND liquidation_buffer_rate < 0.1
    );

ALTER TABLE approved_trade_intent
    DROP CONSTRAINT ck_trade_intent_leverage,
    ADD CONSTRAINT ck_trade_intent_leverage CHECK (leverage >= 1);

ALTER TABLE oms_order
    DROP CONSTRAINT ck_oms_order_leverage,
    ADD CONSTRAINT ck_oms_order_leverage CHECK (leverage >= 1);

--rollback ALTER TABLE oms_order DROP CONSTRAINT IF EXISTS ck_oms_order_leverage;
--rollback ALTER TABLE oms_order ADD CONSTRAINT ck_oms_order_leverage CHECK (leverage >= 1 AND leverage <= 100);
--rollback ALTER TABLE approved_trade_intent DROP CONSTRAINT IF EXISTS ck_trade_intent_leverage;
--rollback ALTER TABLE approved_trade_intent ADD CONSTRAINT ck_trade_intent_leverage CHECK (leverage >= 1 AND leverage <= 100);
--rollback ALTER TABLE risk_policy DROP CONSTRAINT IF EXISTS ck_risk_policy_values;
--rollback ALTER TABLE risk_policy ADD CONSTRAINT ck_risk_policy_values CHECK (minimum_confidence BETWEEN 0 AND 1 AND risk_budget_usdt > 0 AND maximum_notional_usdt > 0 AND maximum_leverage >= 1 AND maximum_leverage <= 100 AND maximum_open_positions BETWEEN 1 AND 100 AND maximum_stop_distance > 0 AND maximum_stop_distance < 1 AND taker_fee_rate >= 0 AND taker_fee_rate < 0.1 AND slippage_rate >= 0 AND slippage_rate < 0.1 AND liquidation_buffer_rate >= 0 AND liquidation_buffer_rate < 0.1);
--rollback ALTER TABLE risk_policy DROP COLUMN IF EXISTS preferred_leverage;
