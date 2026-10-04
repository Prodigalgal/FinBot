--liquibase formatted sql

--changeset codex:072-forward-hypothesis-ledger splitStatements:true endDelimiter:;
CREATE TABLE research_hypothesis (
    hypothesis_id VARCHAR(80) PRIMARY KEY CHECK (hypothesis_id ~ '^hypothesis_[a-z0-9_-]{4,65}$'),
    candidate_id VARCHAR(80) NOT NULL UNIQUE REFERENCES debate_candidate(candidate_id),
    workflow_run_id VARCHAR(80) NOT NULL REFERENCES workflow_run(run_id),
    instrument_id VARCHAR(80) NOT NULL REFERENCES venue_instrument(instrument_id),
    symbol VARCHAR(48) NOT NULL,
    source_artifact_id VARCHAR(80) NOT NULL REFERENCES debate_protocol_artifact(artifact_id),
    initial_hypothesis JSONB NOT NULL CHECK (jsonb_typeof(initial_hypothesis) = 'object'),
    hypothesis JSONB NOT NULL CHECK (jsonb_typeof(hypothesis) = 'object'),
    initial_forecast JSONB,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_VALIDATION','WATCHING','CATALYST_NEAR','CONFIRMED',
        'PAPER_VALIDATION','COMPLETED','REFUTED','EXPIRED')),
    first_seen_at TIMESTAMPTZ NOT NULL,
    information_cutoff TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (information_cutoff <= first_seen_at AND first_seen_at <= recorded_at AND expires_at > first_seen_at)
);
CREATE INDEX ix_research_hypothesis_recent ON research_hypothesis(first_seen_at DESC,hypothesis_id);
CREATE INDEX ix_research_hypothesis_expiry ON research_hypothesis(expires_at,hypothesis_id)
    WHERE status NOT IN ('COMPLETED','REFUTED','EXPIRED');

CREATE TABLE research_hypothesis_revision (
    hypothesis_id VARCHAR(80) NOT NULL REFERENCES research_hypothesis(hypothesis_id),
    version BIGINT NOT NULL CHECK (version >= 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_VALIDATION','WATCHING','CATALYST_NEAR','CONFIRMED',
        'PAPER_VALIDATION','COMPLETED','REFUTED','EXPIRED')),
    reason VARCHAR(2000) NOT NULL CHECK (length(trim(reason)) > 0),
    source_artifact_id VARCHAR(80) UNIQUE REFERENCES debate_protocol_artifact(artifact_id),
    hypothesis JSONB CHECK (hypothesis IS NULL OR jsonb_typeof(hypothesis) = 'object'),
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (hypothesis_id,version)
);

-- This audit is additive. Application rollback keeps all original hypotheses and revisions.
--rollback SELECT 1;
