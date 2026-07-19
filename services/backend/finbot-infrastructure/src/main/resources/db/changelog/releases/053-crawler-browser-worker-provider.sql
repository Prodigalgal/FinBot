--liquibase formatted sql
--changeset codex:053-crawler-browser-worker-provider splitStatements:true endDelimiter:;

ALTER TABLE crawler_header_profile
    DROP CONSTRAINT IF EXISTS ck_crawler_header_captcha_provider;

ALTER TABLE crawler_header_profile
    ADD CONSTRAINT ck_crawler_header_captcha_provider
        CHECK (captcha_bypass_provider IN (
            'NONE', 'CAPSOLVER', 'TWOCAPTCHA', 'FIRECRAWL_BROWSER', 'BROWSER_WORKER'
        ));

--rollback ALTER TABLE crawler_header_profile DROP CONSTRAINT IF EXISTS ck_crawler_header_captcha_provider;
--rollback ALTER TABLE crawler_header_profile ADD CONSTRAINT ck_crawler_header_captcha_provider CHECK (captcha_bypass_provider IN ('NONE', 'CAPSOLVER', 'TWOCAPTCHA', 'FIRECRAWL_BROWSER'));
