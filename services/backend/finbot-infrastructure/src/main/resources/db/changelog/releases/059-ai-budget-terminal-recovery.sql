--liquibase formatted sql

--changeset codex:059-ai-budget-terminal-recovery splitStatements:true endDelimiter:;
CREATE INDEX ix_ai_budget_reservation_open
    ON ai_budget_reservation (run_id, invocation_id)
    WHERE status = 'RESERVED';

WITH terminal_reservation AS (
    SELECT reservation.run_id,
           sum(reservation.reserved_tokens) AS reserved_tokens,
           sum(reservation.reserved_cost_usd) AS reserved_cost_usd
    FROM ai_budget_reservation reservation
    JOIN ai_invocation invocation
      ON invocation.invocation_id = reservation.invocation_id
    WHERE reservation.status = 'RESERVED'
      AND invocation.status IN ('COMPLETED', 'FAILED')
    GROUP BY reservation.run_id
)
UPDATE workflow_run run
SET reserved_tokens = greatest(0, run.reserved_tokens - terminal.reservation_tokens),
    reserved_cost_usd = greatest(0, run.reserved_cost_usd - terminal.reservation_cost),
    updated_at = CURRENT_TIMESTAMP
FROM (
    SELECT run_id,
           reserved_tokens AS reservation_tokens,
           reserved_cost_usd AS reservation_cost
    FROM terminal_reservation
) terminal
WHERE run.run_id = terminal.run_id;

UPDATE ai_budget_reservation reservation
SET status = 'RELEASED',
    released_at = CURRENT_TIMESTAMP
FROM ai_invocation invocation
WHERE invocation.invocation_id = reservation.invocation_id
  AND reservation.status = 'RESERVED'
  AND invocation.status IN ('COMPLETED', 'FAILED');

-- Historical terminal reservation repair is intentionally irreversible.
--rollback DROP INDEX IF EXISTS ix_ai_budget_reservation_open;
