--liquibase formatted sql

--changeset codex:011-research-task-mode splitStatements:true endDelimiter:;
UPDATE background_task
SET payload = payload || '{"taskMode":"STANDARD"}'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE task_type = 'INSTANT_RESEARCH'
  AND NOT payload ? 'taskMode';

ALTER TABLE background_task
    ADD CONSTRAINT ck_background_task_research_mode CHECK (
        task_type <> 'INSTANT_RESEARCH'
        OR (
            payload ? 'taskMode'
            AND payload ->> 'taskMode' IN ('STANDARD', 'RESUME_FAILED')
        )
    ) NOT VALID;

ALTER TABLE background_task
    VALIDATE CONSTRAINT ck_background_task_research_mode;

--rollback ALTER TABLE background_task DROP CONSTRAINT IF EXISTS ck_background_task_research_mode;
--rollback UPDATE background_task SET payload = payload - 'taskMode' WHERE task_type = 'INSTANT_RESEARCH';
