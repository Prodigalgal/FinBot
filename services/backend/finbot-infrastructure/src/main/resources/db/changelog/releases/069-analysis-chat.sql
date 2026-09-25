--liquibase formatted sql

--changeset codex:069-analysis-chat splitStatements:true endDelimiter:;
CREATE TABLE analysis_chat_session (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_id VARCHAR(80) NOT NULL UNIQUE,
    title VARCHAR(160) NOT NULL,
    workflow_version_id VARCHAR(80) NOT NULL REFERENCES workflow_definition_version (version_id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_analysis_chat_id CHECK (chat_id ~ '^chat_[a-z0-9_-]{4,74}$'),
    CONSTRAINT ck_analysis_chat_title CHECK (length(trim(title)) BETWEEN 1 AND 160)
);

CREATE INDEX ix_analysis_chat_updated ON analysis_chat_session (updated_at DESC, id DESC);

CREATE TABLE analysis_chat_turn (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    turn_id VARCHAR(80) NOT NULL UNIQUE,
    chat_id VARCHAR(80) NOT NULL REFERENCES analysis_chat_session (chat_id),
    turn_number INTEGER NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    user_message TEXT NOT NULL,
    workflow_prompt TEXT NOT NULL,
    workflow_run_id VARCHAR(80) UNIQUE REFERENCES workflow_run (run_id),
    task_id VARCHAR(80) REFERENCES background_task (task_id),
    created_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    CONSTRAINT uq_analysis_chat_turn_number UNIQUE (chat_id, turn_number),
    CONSTRAINT uq_analysis_chat_turn_request UNIQUE (chat_id, request_key),
    CONSTRAINT ck_analysis_chat_turn_id CHECK (turn_id ~ '^chatturn_[a-z0-9_-]{4,70}$'),
    CONSTRAINT ck_analysis_chat_turn_number CHECK (turn_number > 0),
    CONSTRAINT ck_analysis_chat_turn_text CHECK (
        length(trim(user_message)) BETWEEN 1 AND 2000
        AND length(trim(workflow_prompt)) BETWEEN 1 AND 2000
    ),
    CONSTRAINT ck_analysis_chat_turn_link CHECK (
        (workflow_run_id IS NULL AND task_id IS NULL AND accepted_at IS NULL)
        OR (workflow_run_id IS NOT NULL AND task_id IS NOT NULL AND accepted_at IS NOT NULL)
    )
);

CREATE INDEX ix_analysis_chat_turn_page ON analysis_chat_turn (chat_id, turn_number DESC);

-- The chat ledger is additive. Application rollback keeps conversations and linked workflow audit records.
