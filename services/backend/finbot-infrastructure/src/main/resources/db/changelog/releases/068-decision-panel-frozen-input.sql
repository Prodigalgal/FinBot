--liquibase formatted sql

--changeset codex:068-decision-panel-frozen-input splitStatements:true endDelimiter:;
ALTER TABLE debate_session
    ADD COLUMN frozen_input JSONB;

ALTER TABLE debate_session
    ADD CONSTRAINT ck_debate_session_frozen_input CHECK (
        frozen_input IS NULL OR jsonb_typeof(frozen_input) IN ('object', 'array')
    ),
    ADD CONSTRAINT ck_debate_session_input_pair CHECK (
        frozen_input IS NULL OR input_hash IS NOT NULL
    );

-- Existing sessions intentionally keep frozen_input NULL because their first-attempt input cannot be reconstructed.
-- This migration is forward-only; application rollback preserves the additive frozen-input ledger.
