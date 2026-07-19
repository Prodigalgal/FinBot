--liquibase formatted sql
--changeset codex:052-crawler-header-camouflage splitStatements:true endDelimiter:;

ALTER TABLE crawler_header_profile
    ADD COLUMN browser_template VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN retain_sensitive_headers_on_cross_origin BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN cross_origin_retain_headers JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN captcha_bypass_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN captcha_bypass_provider VARCHAR(32) NOT NULL DEFAULT 'NONE';

ALTER TABLE crawler_header_profile
    ADD CONSTRAINT ck_crawler_header_browser_template
        CHECK (browser_template IN (
            'NONE', 'CHROME_WINDOWS', 'CHROME_MAC', 'FIREFOX_WINDOWS', 'EDGE_WINDOWS', 'CUSTOM'
        )),
    ADD CONSTRAINT ck_crawler_header_captcha_provider
        CHECK (captcha_bypass_provider IN (
            'NONE', 'CAPSOLVER', 'TWOCAPTCHA', 'FIRECRAWL_BROWSER'
        )),
    ADD CONSTRAINT ck_crawler_header_captcha_enabled_provider
        CHECK (captcha_bypass_enabled = FALSE OR captcha_bypass_provider <> 'NONE'),
    ADD CONSTRAINT ck_crawler_header_cross_origin_retain_array
        CHECK (jsonb_typeof(cross_origin_retain_headers) = 'array');

--rollback ALTER TABLE crawler_header_profile DROP CONSTRAINT IF EXISTS ck_crawler_header_cross_origin_retain_array;
--rollback ALTER TABLE crawler_header_profile DROP CONSTRAINT IF EXISTS ck_crawler_header_captcha_enabled_provider;
--rollback ALTER TABLE crawler_header_profile DROP CONSTRAINT IF EXISTS ck_crawler_header_captcha_provider;
--rollback ALTER TABLE crawler_header_profile DROP CONSTRAINT IF EXISTS ck_crawler_header_browser_template;
--rollback ALTER TABLE crawler_header_profile DROP COLUMN IF EXISTS captcha_bypass_provider;
--rollback ALTER TABLE crawler_header_profile DROP COLUMN IF EXISTS captcha_bypass_enabled;
--rollback ALTER TABLE crawler_header_profile DROP COLUMN IF EXISTS cross_origin_retain_headers;
--rollback ALTER TABLE crawler_header_profile DROP COLUMN IF EXISTS retain_sensitive_headers_on_cross_origin;
--rollback ALTER TABLE crawler_header_profile DROP COLUMN IF EXISTS browser_template;
