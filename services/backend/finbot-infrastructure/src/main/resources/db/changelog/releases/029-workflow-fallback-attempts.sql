--liquibase formatted sql

--changeset codex:029-workflow-fallback-attempts splitStatements:false
ALTER TABLE workflow_node_checkpoint
    DROP CONSTRAINT ck_workflow_checkpoint_position;
ALTER TABLE workflow_node_checkpoint
    ADD CONSTRAINT ck_workflow_checkpoint_position CHECK (
        round_index BETWEEN 0 AND 8
        AND iteration >= 0
        AND attempt BETWEEN 1 AND 10
    );

WITH exhausted_checkpoints AS (
    SELECT DISTINCT checkpoint.id,
           COALESCE(task.completed_at, CURRENT_TIMESTAMP) AS failed_at,
           COALESCE(NULLIF(task.error_code, ''), 'WORKFLOW_EXECUTION_RETRY_EXHAUSTED') AS error_code,
           LEFT(COALESCE(
               NULLIF(task.error_message, ''),
               'Workflow execution failed after background task retry exhaustion'
           ), 2000) AS error_message
    FROM workflow_run run
    JOIN background_task task
      ON run.idempotency_key = task.idempotency_key
      OR run.idempotency_key = task.payload ->> 'workflowIdempotencyKey'
    JOIN workflow_node_checkpoint checkpoint
      ON checkpoint.run_id = run.run_id
     AND checkpoint.status = 'RUNNING'
    WHERE run.status = 'RUNNING'
      AND task.task_type IN ('SCHEDULED_RESEARCH', 'INSTANT_RESEARCH')
      AND task.status = 'FAILED'
      AND task.attempt_count = task.maximum_attempts
), repaired_checkpoints AS (
    UPDATE workflow_node_checkpoint checkpoint
    SET status = 'FAILED',
        error_code = exhausted.error_code,
        error_message = exhausted.error_message,
        completed_at = exhausted.failed_at,
        updated_at = exhausted.failed_at
    FROM exhausted_checkpoints exhausted
    WHERE checkpoint.id = exhausted.id
    RETURNING checkpoint.run_id, checkpoint.completed_at,
              checkpoint.error_code, checkpoint.error_message
), candidates AS (
    SELECT DISTINCT ON (run.run_id)
        run.run_id,
        run.next_event_sequence AS event_sequence,
        repaired.completed_at AS failed_at,
        repaired.error_code,
        repaired.error_message AS safe_message
    FROM workflow_run run
    JOIN repaired_checkpoints repaired ON repaired.run_id = run.run_id
    WHERE run.status = 'RUNNING'
      AND NOT EXISTS (
          SELECT 1 FROM workflow_event event
          WHERE event.run_id = run.run_id AND event.event_type = 'workflow.failed'
      )
    ORDER BY run.run_id, repaired.completed_at DESC
), failed_runs AS (
    UPDATE workflow_run run
    SET status = 'FAILED',
        completed_at = candidate.failed_at,
        current_node_id = NULL,
        next_event_sequence = run.next_event_sequence + 1,
        version = run.version + 1,
        updated_at = candidate.failed_at
    FROM candidates candidate
    WHERE run.run_id = candidate.run_id AND run.status = 'RUNNING'
    RETURNING run.run_id, candidate.event_sequence, candidate.failed_at,
              candidate.error_code, candidate.safe_message
), failed_debates AS (
    UPDATE debate_session debate
    SET status = 'FAILED', completed_at = failed.failed_at
    FROM failed_runs failed
    WHERE debate.run_id = failed.run_id AND debate.status = 'RUNNING'
    RETURNING debate.run_id
)
INSERT INTO workflow_event (
    event_id, run_id, sequence, event_type, payload, occurred_at
)
SELECT
    'event_repair_' || SUBSTRING(MD5(failed.run_id || ':029') FROM 1 FOR 32),
    failed.run_id,
    failed.event_sequence,
    'workflow.failed',
    JSONB_BUILD_OBJECT(
        'eventId', JSONB_BUILD_OBJECT(
            'value', 'event_repair_' || SUBSTRING(MD5(failed.run_id || ':029') FROM 1 FOR 32)
        ),
        'runId', JSONB_BUILD_OBJECT('value', failed.run_id),
        'sequence', failed.event_sequence,
        'errorCode', failed.error_code,
        'safeMessage', failed.safe_message,
        'retryable', TRUE,
        'occurredAt', EXTRACT(EPOCH FROM failed.failed_at)
    ),
    failed.failed_at
FROM failed_runs failed;

--rollback ALTER TABLE workflow_node_checkpoint DROP CONSTRAINT ck_workflow_checkpoint_position;
--rollback ALTER TABLE workflow_node_checkpoint ADD CONSTRAINT ck_workflow_checkpoint_position CHECK (round_index BETWEEN 0 AND 8 AND iteration >= 0 AND attempt BETWEEN 1 AND 5);
