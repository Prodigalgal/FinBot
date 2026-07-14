--liquibase formatted sql

--changeset codex:008-workflow-idempotency splitStatements:true endDelimiter:;
ALTER TABLE workflow_run ADD COLUMN idempotency_key VARCHAR(200);

UPDATE workflow_run
SET idempotency_key = 'migration:' || run_id
WHERE idempotency_key IS NULL;

ALTER TABLE workflow_run ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE workflow_run
    ADD CONSTRAINT uq_workflow_run_idempotency UNIQUE (idempotency_key);

--rollback ALTER TABLE workflow_run DROP CONSTRAINT IF EXISTS uq_workflow_run_idempotency;
--rollback ALTER TABLE workflow_run DROP COLUMN IF EXISTS idempotency_key;
