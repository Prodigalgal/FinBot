package io.omnnu.finbot.infrastructure.workflow;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.workflow.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.WorkflowCommandStore;
import io.omnnu.finbot.application.workflow.WorkflowEventReader;
import io.omnnu.finbot.application.workflow.WorkflowRunQuery;
import io.omnnu.finbot.application.workflow.WorkflowRunSnapshot;
import io.omnnu.finbot.application.workflow.WorkflowIdempotencyConflictException;
import io.omnnu.finbot.domain.workflow.WorkflowAccepted;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWorkflowStore implements WorkflowCommandStore, WorkflowEventReader, WorkflowRunQuery {
    private final JdbcClient jdbcClient;
    private final WorkflowEventCodec eventCodec;

    public JdbcWorkflowStore(JdbcClient jdbcClient, WorkflowEventCodec eventCodec) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
    }

    @Override
    @Transactional
    public StartWorkflowResult accept(StartWorkflowCommand command, WorkflowAccepted acceptedEvent) {
        var inserted = jdbcClient.sql("""
                insert into workflow_run (
                  run_id, workflow_type, status, trigger_type, request_summary,
                  workflow_version_id, idempotency_key, version, accepted_at, created_at, updated_at
                ) values (
                  :runId, :workflowType, 'ACCEPTED', :triggerType, :requestSummary,
                  coalesce(:workflowVersionId, (
                    select version_id from workflow_definition_version
                    where definition_id = 'workflow_standard_product_research'
                      and status = 'PUBLISHED'
                  )),
                  :idempotencyKey, 0, :acceptedAt, :acceptedAt, :acceptedAt
                ) on conflict (idempotency_key) do nothing
                """)
                .param("runId", acceptedEvent.runId().value())
                .param("workflowType", command.workflowType().name())
                .param("triggerType", command.trigger().name())
                .param("requestSummary", command.requestSummary())
                .param("workflowVersionId", command.workflowVersionId() == null
                        ? null
                        : command.workflowVersionId().value())
                .param("idempotencyKey", command.idempotencyKey())
                .param("acceptedAt", timestamp(acceptedEvent.occurredAt()))
                .update();
        if (inserted == 1) {
            insertEvent(acceptedEvent);
            jdbcClient.sql("""
                insert into outbox_event (
                  event_id, aggregate_type, aggregate_id, event_type, payload,
                  status, attempts, available_at, created_at
                ) values (
                  :eventId, 'WORKFLOW_RUN', :runId, :eventType, cast(:payload as jsonb),
                  'PENDING', 0, :occurredAt, :occurredAt
                )
                """)
                .param("eventId", acceptedEvent.eventId().value())
                .param("runId", acceptedEvent.runId().value())
                .param("eventType", acceptedEvent.eventType())
                .param("payload", eventCodec.encode(acceptedEvent))
                .param("occurredAt", timestamp(acceptedEvent.occurredAt()))
                .update();
            return new StartWorkflowResult(
                    acceptedEvent.runId(),
                    acceptedEvent.eventId(),
                    acceptedEvent.occurredAt());
        }
        return existingAccepted(command);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowEvent> loadAfter(WorkflowRunId runId, long afterSequence, int limit) {
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        var safeLimit = Math.max(1, Math.min(limit, 1_000));
        return jdbcClient.sql("""
                select event_type, payload::text as payload
                from workflow_event
                where run_id = :runId and sequence > :afterSequence
                order by sequence
                limit :limit
                """)
                .param("runId", runId.value())
                .param("afterSequence", afterSequence)
                .param("limit", safeLimit)
                .query((resultSet, rowNumber) -> eventCodec.decode(
                        resultSet.getString("event_type"),
                        resultSet.getString("payload")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowRunSnapshot> find(WorkflowRunId runId) {
        Objects.requireNonNull(runId, "runId");
        return jdbcClient.sql("""
                select workflow_type, status, trigger_type, request_summary, accepted_at, updated_at
                from workflow_run
                where run_id = :runId
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new WorkflowRunSnapshot(
                        runId,
                        WorkflowType.valueOf(resultSet.getString("workflow_type")),
                        WorkflowRunStatus.valueOf(resultSet.getString("status")),
                        WorkflowTrigger.valueOf(resultSet.getString("trigger_type")),
                        resultSet.getString("request_summary"),
                        resultSet.getObject("accepted_at", java.time.OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()))
                .optional();
    }

    private void insertEvent(WorkflowEvent event) {
        jdbcClient.sql("""
                insert into workflow_event (
                  event_id, run_id, sequence, event_type, payload, occurred_at
                ) values (
                  :eventId, :runId, :sequence, :eventType, cast(:payload as jsonb), :occurredAt
                )
                """)
                .param("eventId", event.eventId().value())
                .param("runId", event.runId().value())
                .param("sequence", event.sequence())
                .param("eventType", event.eventType())
                .param("payload", eventCodec.encode(event))
                .param("occurredAt", timestamp(event.occurredAt()))
                .update();
    }

    private StartWorkflowResult existingAccepted(StartWorkflowCommand command) {
        var existing = jdbcClient.sql("""
                select run.run_id, run.workflow_type, run.trigger_type, run.request_summary,
                       run.workflow_version_id, run.accepted_at, event.event_id
                from workflow_run run
                join workflow_event event on event.run_id = run.run_id and event.sequence = 1
                where run.idempotency_key = :idempotencyKey
                """)
                .param("idempotencyKey", command.idempotencyKey())
                .query((resultSet, rowNumber) -> new ExistingAcceptedWorkflow(
                        new WorkflowRunId(resultSet.getString("run_id")),
                        WorkflowType.valueOf(resultSet.getString("workflow_type")),
                        WorkflowTrigger.valueOf(resultSet.getString("trigger_type")),
                        resultSet.getString("request_summary"),
                        resultSet.getString("workflow_version_id"),
                        new io.omnnu.finbot.domain.workflow.WorkflowEventId(resultSet.getString("event_id")),
                        resultSet.getObject("accepted_at", java.time.OffsetDateTime.class).toInstant()))
                .single();
        var requestedVersion = command.workflowVersionId() == null ? null : command.workflowVersionId().value();
        if (existing.workflowType() != command.workflowType()
                || existing.trigger() != command.trigger()
                || !existing.requestSummary().equals(command.requestSummary())
                || (requestedVersion != null && !requestedVersion.equals(existing.workflowVersionId()))) {
            throw new WorkflowIdempotencyConflictException();
        }
        return new StartWorkflowResult(existing.runId(), existing.eventId(), existing.acceptedAt());
    }

    private record ExistingAcceptedWorkflow(
            WorkflowRunId runId,
            WorkflowType workflowType,
            WorkflowTrigger trigger,
            String requestSummary,
            String workflowVersionId,
            io.omnnu.finbot.domain.workflow.WorkflowEventId eventId,
            java.time.Instant acceptedAt) {
    }
}
