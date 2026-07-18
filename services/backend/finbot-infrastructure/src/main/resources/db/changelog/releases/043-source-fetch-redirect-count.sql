--liquibase formatted sql

--changeset codex:043-source-fetch-redirect-count splitStatements:true endDelimiter:;
ALTER TABLE source_fetch_attempt
    DROP CONSTRAINT ck_source_fetch_attempt_counters;

ALTER TABLE source_fetch_attempt
    ADD COLUMN redirect_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE source_fetch_attempt
    ADD CONSTRAINT ck_source_fetch_attempt_counters CHECK (
        response_bytes >= 0 AND retry_count >= 0 AND redirect_count >= 0
    );

--rollback ALTER TABLE source_fetch_attempt DROP CONSTRAINT ck_source_fetch_attempt_counters;
--rollback ALTER TABLE source_fetch_attempt DROP COLUMN redirect_count;
--rollback ALTER TABLE source_fetch_attempt ADD CONSTRAINT ck_source_fetch_attempt_counters CHECK (response_bytes >= 0 AND retry_count >= 0);
