--liquibase formatted sql

--changeset codex:067-full-chain-decision-panels splitStatements:true endDelimiter:;
ALTER TABLE debate_session
    ADD COLUMN panel_key VARCHAR(64),
    ADD COLUMN panel_purpose VARCHAR(32),
    ADD COLUMN input_hash CHAR(64),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE debate_session
SET panel_key = 'research',
    panel_purpose = 'RESEARCH'
WHERE panel_key IS NULL;

ALTER TABLE debate_session
    ALTER COLUMN panel_key SET NOT NULL,
    ALTER COLUMN panel_purpose SET NOT NULL,
    DROP CONSTRAINT uq_debate_session_run,
    ADD CONSTRAINT uq_debate_session_run_panel UNIQUE (run_id, panel_key),
    ADD CONSTRAINT ck_debate_session_panel_key
        CHECK (panel_key ~ '^[a-z][a-z0-9_-]{2,63}$'),
    ADD CONSTRAINT ck_debate_session_panel_purpose
        CHECK (panel_purpose IN ('EVIDENCE', 'RESEARCH', 'PRINCIPAL_REVIEW', 'EXECUTION')),
    ADD CONSTRAINT ck_debate_session_input_hash
        CHECK (input_hash IS NULL OR input_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_debate_session_version CHECK (version >= 0);

CREATE INDEX ix_debate_session_run_purpose_status
    ON debate_session (run_id, panel_purpose, status, panel_key);

-- Historical sessions intentionally keep input_hash NULL because their frozen inputs cannot be reconstructed reliably.

-- This migration is intentionally forward-only. Restoring UNIQUE(run_id) after a second panel exists
-- would be destructive; application rollback keeps this additive ledger and deploys the previous image.
