--liquibase formatted sql
--changeset codex:050-crawler-header-profiles splitStatements:true endDelimiter:;

CREATE TABLE crawler_header_profile (
    profile_id VARCHAR(80) PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    user_agent VARCHAR(500) NOT NULL,
    accept_header VARCHAR(2048),
    accept_language VARCHAR(500),
    additional_headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_crawler_header_profile_additional_object
        CHECK (jsonb_typeof(additional_headers) = 'object'),
    CONSTRAINT ck_crawler_header_profile_default_enabled
        CHECK (profile_id <> 'header_default' OR enabled)
);

CREATE UNIQUE INDEX ux_crawler_header_profile_display_name
    ON crawler_header_profile (lower(display_name))
    WHERE deleted_at IS NULL;

INSERT INTO crawler_header_profile (
    profile_id, display_name, user_agent, accept_header, accept_language,
    additional_headers, enabled, version, created_at, updated_at
) VALUES (
    'header_default',
    'FinBot 默认爬虫请求头',
    'FinBot/2.0 (contact: finbot@omnnu.xyz)',
    NULL,
    'zh-CN,zh;q=0.9,en;q=0.8',
    '{}'::jsonb,
    TRUE,
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);

ALTER TABLE information_source
    ADD COLUMN crawler_header_profile_id VARCHAR(80) NOT NULL DEFAULT 'header_default';

ALTER TABLE information_source
    ADD CONSTRAINT fk_information_source_crawler_header_profile
        FOREIGN KEY (crawler_header_profile_id)
        REFERENCES crawler_header_profile (profile_id);

CREATE INDEX ix_information_source_crawler_header_profile
    ON information_source (crawler_header_profile_id)
    WHERE deleted_at IS NULL;

--rollback DROP INDEX IF EXISTS ix_information_source_crawler_header_profile;
--rollback ALTER TABLE information_source DROP CONSTRAINT IF EXISTS fk_information_source_crawler_header_profile;
--rollback ALTER TABLE information_source DROP COLUMN IF EXISTS crawler_header_profile_id;
--rollback DROP INDEX IF EXISTS ux_crawler_header_profile_display_name;
--rollback DROP TABLE IF EXISTS crawler_header_profile;
