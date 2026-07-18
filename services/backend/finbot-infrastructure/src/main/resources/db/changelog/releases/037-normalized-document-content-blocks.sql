--liquibase formatted sql

--changeset codex:037-normalized-document-content-blocks splitStatements:true endDelimiter:;
ALTER TABLE normalized_document
    ADD COLUMN content_blocks JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE normalized_document
    ADD CONSTRAINT ck_normalized_document_content_blocks CHECK (
        jsonb_typeof(content_blocks) = 'array'
    );

--rollback ALTER TABLE normalized_document DROP CONSTRAINT ck_normalized_document_content_blocks;
--rollback ALTER TABLE normalized_document DROP COLUMN content_blocks;
