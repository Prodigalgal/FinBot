package io.omnnu.finbot.infrastructure.workflow.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventFactory;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.domain.workflow.WorkflowEvent;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWorkflowEventPublisher implements WorkflowEventPublisher {
    private final JdbcClient jdbcClient;
    private final WorkflowEventCodec eventCodec;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public JdbcWorkflowEventPublisher(
            JdbcClient jdbcClient,
            WorkflowEventCodec eventCodec,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.eventCodec = Objects.requireNonNull(eventCodec, "eventCodec");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public WorkflowEvent publish(WorkflowRunId runId, WorkflowEventFactory eventFactory) {
        var occurredAt = clock.instant();
        var sequence = jdbcClient.sql("""
                update workflow_run
                set next_event_sequence = next_event_sequence + 1,
                    updated_at = :occurredAt
                where run_id = :runId
                returning next_event_sequence - 1
                """)
                .param("runId", runId.value())
                .param("occurredAt", timestamp(occurredAt))
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Workflow run does not exist"));
        var event = eventFactory.create(
                new WorkflowEventId(idGenerator.next("event_")),
                sequence,
                occurredAt);
        if (!event.runId().equals(runId) || event.sequence() != sequence) {
            throw new IllegalArgumentException("Workflow event factory returned inconsistent identity");
        }
        jdbcClient.sql("""
                insert into workflow_event (
                  event_id, run_id, sequence, event_type, payload, occurred_at
                ) values (
                  :eventId, :runId, :sequence, :eventType, cast(:payload as jsonb), :occurredAt
                )
                """)
                .param("eventId", event.eventId().value())
                .param("runId", runId.value())
                .param("sequence", event.sequence())
                .param("eventType", event.eventType())
                .param("payload", eventCodec.encode(event))
                .param("occurredAt", timestamp(event.occurredAt()))
                .update();
        return event;
    }
}
