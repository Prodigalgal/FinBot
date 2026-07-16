--liquibase formatted sql

--changeset codex:033-information-source-management splitStatements:true endDelimiter:;
ALTER TABLE information_source
    ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX ix_information_source_active_priority
    ON information_source (enabled, priority, source_tier, id)
    WHERE deleted_at IS NULL;

--rollback DROP INDEX IF EXISTS ix_information_source_active_priority;
--rollback ALTER TABLE information_source DROP COLUMN IF EXISTS deleted_at;
