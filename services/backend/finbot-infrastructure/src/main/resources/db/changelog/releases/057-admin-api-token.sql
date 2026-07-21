--liquibase formatted sql

--changeset codex:057-admin-api-token splitStatements:true endDelimiter:;
CREATE TABLE admin_api_token (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_id VARCHAR(80) NOT NULL UNIQUE,
    token_digest CHAR(64) NOT NULL UNIQUE,
    fingerprint CHAR(16) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    username VARCHAR(80) NOT NULL,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_api_token_id CHECK (token_id ~ '^apitoken_[a-z0-9_-]{4,71}$'),
    CONSTRAINT ck_admin_api_token_digest CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_admin_api_token_fingerprint CHECK (fingerprint ~ '^[0-9a-f]{16}$'),
    CONSTRAINT ck_admin_api_token_name CHECK (length(btrim(display_name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_admin_api_token_username CHECK (length(btrim(username)) BETWEEN 1 AND 80),
    CONSTRAINT ck_admin_api_token_version CHECK (version >= 0),
    CONSTRAINT ck_admin_api_token_time CHECK (
        (expires_at IS NULL OR expires_at > created_at)
        AND (last_used_at IS NULL OR last_used_at >= created_at)
        AND (revoked_at IS NULL OR revoked_at >= created_at)
        AND updated_at >= created_at
        AND (last_used_at IS NULL OR updated_at >= last_used_at)
        AND (revoked_at IS NULL OR updated_at >= revoked_at)
    )
);

CREATE INDEX ix_admin_api_token_authentication
    ON admin_api_token (token_digest, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX ix_admin_api_token_created
    ON admin_api_token (created_at DESC, id DESC);

--rollback DROP TABLE IF EXISTS admin_api_token;
