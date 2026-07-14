--liquibase formatted sql

--changeset codex:014-trade-automation-retry splitStatements:true endDelimiter:;
ALTER TABLE trade_automation_run
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 1;

ALTER TABLE trade_automation_run
    ADD CONSTRAINT ck_trade_automation_attempt_count CHECK (
        attempt_count BETWEEN 1 AND 20
    );

--rollback ALTER TABLE trade_automation_run DROP CONSTRAINT IF EXISTS ck_trade_automation_attempt_count;
--rollback ALTER TABLE trade_automation_run DROP COLUMN IF EXISTS attempt_count;
