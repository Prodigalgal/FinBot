--liquibase formatted sql

--changeset codex:013-terminalize-exhausted-workflows splitStatements:false
WITH candidates AS (
    SELECT DISTINCT ON (run.run_id)
        run.run_id,
        run.next_event_sequence AS event_sequence,
        COALESCE(checkpoint.completed_at, task.completed_at, CURRENT_TIMESTAMP) AS failed_at,
        COALESCE(
            NULLIF(checkpoint.error_code, ''),
            'WORKFLOW_EXECUTION_RETRY_EXHAUSTED'
        ) AS error_code,
        LEFT(
            COALESCE(
                NULLIF(checkpoint.error_message, ''),
                'Workflow execution failed after background task retry exhaustion'
            ),
            2000
        ) AS safe_message
    FROM workflow_run run
    JOIN background_task task
      ON run.idempotency_key = task.idempotency_key
      OR run.idempotency_key = task.payload ->> 'workflowIdempotencyKey'
    JOIN workflow_node_checkpoint checkpoint
      ON checkpoint.run_id = run.run_id
     AND checkpoint.status = 'FAILED'
    WHERE run.status = 'RUNNING'
      AND task.task_type IN ('SCHEDULED_RESEARCH', 'INSTANT_RESEARCH')
      AND task.status = 'FAILED'
      AND task.attempt_count = task.maximum_attempts
      AND NOT EXISTS (
          SELECT 1
          FROM workflow_node_checkpoint active_checkpoint
          WHERE active_checkpoint.run_id = run.run_id
            AND active_checkpoint.status = 'RUNNING'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM workflow_event existing_failure
          WHERE existing_failure.run_id = run.run_id
            AND existing_failure.event_type = 'workflow.failed'
      )
    ORDER BY run.run_id, checkpoint.updated_at DESC, checkpoint.id DESC
), failed_runs AS (
    UPDATE workflow_run run
    SET status = 'FAILED',
        completed_at = candidate.failed_at,
        current_node_id = NULL,
        next_event_sequence = run.next_event_sequence + 1,
        version = run.version + 1,
        updated_at = candidate.failed_at
    FROM candidates candidate
    WHERE run.run_id = candidate.run_id
      AND run.status = 'RUNNING'
    RETURNING
        run.run_id,
        candidate.event_sequence,
        candidate.failed_at,
        candidate.error_code,
        candidate.safe_message
), failed_debates AS (
    UPDATE debate_session debate
    SET status = 'FAILED',
        completed_at = failed_run.failed_at
    FROM failed_runs failed_run
    WHERE debate.run_id = failed_run.run_id
      AND debate.status = 'RUNNING'
    RETURNING debate.run_id
)
INSERT INTO workflow_event (
    event_id,
    run_id,
    sequence,
    event_type,
    payload,
    occurred_at
)
SELECT
    'event_repair_' || SUBSTRING(MD5(failed_run.run_id) FROM 1 FOR 32),
    failed_run.run_id,
    failed_run.event_sequence,
    'workflow.failed',
    JSONB_BUILD_OBJECT(
        'eventId', JSONB_BUILD_OBJECT(
            'value', 'event_repair_' || SUBSTRING(MD5(failed_run.run_id) FROM 1 FOR 32)
        ),
        'runId', JSONB_BUILD_OBJECT('value', failed_run.run_id),
        'sequence', failed_run.event_sequence,
        'errorCode', failed_run.error_code,
        'safeMessage', failed_run.safe_message,
        'retryable', TRUE,
        'occurredAt', EXTRACT(EPOCH FROM failed_run.failed_at)
    ),
    failed_run.failed_at
FROM failed_runs failed_run;
