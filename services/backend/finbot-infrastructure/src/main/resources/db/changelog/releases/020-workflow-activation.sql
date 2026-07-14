--liquibase formatted sql

--changeset codex:020-workflow-activation splitStatements:true endDelimiter:;
ALTER TABLE workflow_definition
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE workflow_definition
SET active = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE definition_id = 'workflow_standard_product_research';

CREATE INDEX ix_workflow_definition_active
    ON workflow_definition (active, definition_id)
    WHERE active = TRUE;
