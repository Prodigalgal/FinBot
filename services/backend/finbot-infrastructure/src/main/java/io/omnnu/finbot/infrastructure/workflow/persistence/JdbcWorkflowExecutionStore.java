package io.omnnu.finbot.infrastructure.workflow.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowCheckpoint;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.market.dto.ResearchMarketScope;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.application.workflow.port.out.WorkflowManagementRepository;
import io.omnnu.finbot.domain.workflow.AgentClaim;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessageId;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
import io.omnnu.finbot.domain.workflow.DebateId;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointId;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointStatus;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.research.ForecastSignal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWorkflowExecutionStore implements WorkflowExecutionStore {
    private final JdbcClient jdbcClient;
    private final WorkflowManagementRepository workflowRepository;
    private final ObjectMapper objectMapper;

    public JdbcWorkflowExecutionStore(
            JdbcClient jdbcClient,
            WorkflowManagementRepository workflowRepository,
            ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.workflowRepository = Objects.requireNonNull(workflowRepository, "workflowRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowExecutionContext> load(WorkflowRunId runId) {
        var run = jdbcClient.sql("""
                select workflow_run.status, workflow_run.request_summary,
                       workflow_run.workflow_version_id,
                       scope.instrument_id, scope.exchange, scope.environment, scope.symbol,
                       scope.interval_seconds, scope.forecast_horizon_seconds,
                       scope.market_reference_price,
                       coalesce((
                         select jsonb_agg(jsonb_build_object(
                           'artifactType', artifact.artifact_type,
                           'content', artifact.content
                         ) order by artifact.created_at, artifact.id)::text
                         from research_artifact artifact
                         where (artifact.workflow_run_id = workflow_run.run_id
                           or exists (
                             select 1 from workflow_evidence_binding binding
                             where binding.workflow_run_id = workflow_run.run_id
                               and binding.artifact_id = artifact.artifact_id
                               and binding.content_hash = artifact.content_hash
                           ))
                           and artifact.artifact_type in (
                             'EVIDENCE_PACKAGE', 'COMPRESSION_PACKAGE', 'QUANT_RESULT',
                             'RISK_ASSESSMENT'
                           )
                       ), '[]') as research_context
                from workflow_run
                left join research_market_scope scope
                  on scope.workflow_run_id = workflow_run.run_id
                where workflow_run.run_id = :runId
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new RunRoot(
                        WorkflowRunStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("request_summary"),
                        resultSet.getString("research_context"),
                        resultSet.getString("workflow_version_id"),
                        researchScope(resultSet)))
                .optional();
        if (run.isEmpty()) {
            return Optional.empty();
        }
        var value = run.orElseThrow();
        if (value.workflowVersionId() == null) {
            throw new IllegalStateException("Workflow run has no definition version");
        }
        var version = workflowRepository.findVersion(new WorkflowVersionId(value.workflowVersionId()))
                .orElseThrow(() -> new IllegalStateException("Workflow definition version is missing"));
        return Optional.of(new WorkflowExecutionContext(
                runId,
                value.status(),
                value.requestSummary(),
                value.researchContext(),
                version,
                value.marketScope()));
    }

    @Override
    public boolean markRunning(WorkflowRunId runId, Instant startedAt) {
        var changed = jdbcClient.sql("""
                update workflow_run
                set status = 'RUNNING', started_at = coalesce(started_at, :startedAt),
                    updated_at = :startedAt, version = version + 1
                where run_id = :runId and status = 'ACCEPTED'
                """)
                .param("runId", runId.value())
                .param("startedAt", timestamp(startedAt))
                .update();
        if (changed == 1) {
            return true;
        }
        return jdbcClient.sql("select count(*) from workflow_run where run_id = :runId and status = 'RUNNING'")
                .param("runId", runId.value())
                .query(Integer.class)
                .single() == 1;
    }

    @Override
    @Transactional
    public boolean resumeFailed(WorkflowRunId runId, Instant resumedAt) {
        var changed = jdbcClient.sql("""
                update workflow_run
                set status = 'ACCEPTED', completed_at = null, current_node_id = null,
                    updated_at = :resumedAt, version = version + 1
                where run_id = :runId and status = 'FAILED'
                """)
                .param("runId", runId.value())
                .param("resumedAt", timestamp(resumedAt))
                .update();
        if (changed == 1) {
            jdbcClient.sql("""
                    update debate_session
                    set status = 'RUNNING', completed_at = null
                    where run_id = :runId and status = 'FAILED'
                    """)
                    .param("runId", runId.value())
                    .update();
        }
        return changed == 1;
    }

    @Override
    public void saveCheckpoint(WorkflowCheckpoint checkpoint) {
        var changed = jdbcClient.sql("""
                insert into workflow_node_checkpoint (
                  checkpoint_id, run_id, node_id, round_index, iteration, attempt,
                  status, result_summary, error_code, error_message,
                  started_at, completed_at, updated_at
                ) values (
                  :checkpointId, :runId, :nodeId, :roundIndex, :iteration, :attempt,
                  :status, :resultSummary, :errorCode, :errorMessage,
                  :startedAt, :completedAt, :updatedAt
                ) on conflict (run_id, node_id, round_index, iteration) do update
                set attempt = excluded.attempt,
                    status = excluded.status,
                    result_summary = excluded.result_summary,
                    error_code = excluded.error_code,
                    error_message = excluded.error_message,
                    started_at = coalesce(workflow_node_checkpoint.started_at, excluded.started_at),
                    completed_at = excluded.completed_at,
                    updated_at = excluded.updated_at
                where workflow_node_checkpoint.status in ('PENDING', 'RUNNING')
                   or workflow_node_checkpoint.status = excluded.status
                   or (workflow_node_checkpoint.status = 'FAILED' and excluded.status = 'RUNNING')
                """)
                .param("checkpointId", checkpoint.checkpointId().value())
                .param("runId", checkpoint.runId().value())
                .param("nodeId", checkpoint.nodeId().value())
                .param("roundIndex", checkpoint.roundIndex())
                .param("iteration", checkpoint.iteration())
                .param("attempt", checkpoint.attempt())
                .param("status", checkpoint.status().name())
                .param("resultSummary", checkpoint.resultSummary())
                .param("errorCode", checkpoint.errorCode())
                .param("errorMessage", checkpoint.errorMessage())
                .param("startedAt", timestamp(checkpoint.startedAt()))
                .param("completedAt", timestamp(checkpoint.completedAt()))
                .param("updatedAt", timestamp(checkpoint.updatedAt()))
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Checkpoint is already final with a different outcome");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowCheckpoint> findCheckpoint(
            WorkflowRunId runId,
            WorkflowNodeId nodeId,
            int roundIndex,
            int iteration) {
        return jdbcClient.sql("""
                select checkpoint_id, attempt, status, result_summary, error_code,
                       error_message, started_at, completed_at, updated_at
                from workflow_node_checkpoint
                where run_id = :runId and node_id = :nodeId
                  and round_index = :roundIndex and iteration = :iteration
                """)
                .param("runId", runId.value())
                .param("nodeId", nodeId.value())
                .param("roundIndex", roundIndex)
                .param("iteration", iteration)
                .query((resultSet, rowNumber) -> new WorkflowCheckpoint(
                        new WorkflowCheckpointId(resultSet.getString("checkpoint_id")),
                        runId,
                        nodeId,
                        roundIndex,
                        iteration,
                        resultSet.getInt("attempt"),
                        WorkflowCheckpointStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("result_summary"),
                        resultSet.getString("error_code"),
                        resultSet.getString("error_message"),
                        nullableInstant(resultSet.getObject("started_at", OffsetDateTime.class)),
                        nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .optional();
    }

    @Override
    public void startDebate(DebateSession session) {
        jdbcClient.sql("""
                insert into debate_session (
                  debate_id, run_id, status, configured_rounds, completed_rounds,
                  chair_node_id, started_at, completed_at
                ) values (
                  :debateId, :runId, :status, :configuredRounds, :completedRounds,
                  :chairNodeId, :startedAt, :completedAt
                ) on conflict (run_id) do nothing
                """)
                .param("debateId", session.debateId().value())
                .param("runId", session.runId().value())
                .param("status", session.status().name())
                .param("configuredRounds", session.configuredRounds())
                .param("completedRounds", session.completedRounds())
                .param("chairNodeId", session.chairNodeId().value())
                .param("startedAt", timestamp(session.startedAt()))
                .param("completedAt", timestamp(session.completedAt()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DebateSession> findDebate(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select debate_id, status, configured_rounds, completed_rounds,
                       chair_node_id, started_at, completed_at
                from debate_session where run_id = :runId
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new DebateSession(
                        new DebateId(resultSet.getString("debate_id")),
                        runId,
                        DebateStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("configured_rounds"),
                        resultSet.getInt("completed_rounds"),
                        new WorkflowNodeId(resultSet.getString("chair_node_id")),
                        instant(resultSet.getObject("started_at", OffsetDateTime.class)),
                        nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class))))
                .optional();
    }

    @Override
    public void updateDebate(
            DebateId debateId,
            DebateStatus status,
            int completedRounds,
            Instant completedAt) {
        jdbcClient.sql("""
                update debate_session
                set status = :status,
                    completed_rounds = :completedRounds,
                    completed_at = :completedAt
                where debate_id = :debateId
                """)
                .param("debateId", debateId.value())
                .param("status", status.name())
                .param("completedRounds", completedRounds)
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    @Override
    @Transactional
    public void saveMessage(AgentMessage message) {
        jdbcClient.sql("""
                insert into agent_message (
                  message_id, debate_id, run_id, node_id, role_name, round_index,
                  turn_index, message_type, status, summary, argument, confidence,
                  claims, evidence_refs, challenges, revision_notes, forecast, created_at
                ) values (
                  :messageId, :debateId, :runId, :nodeId, :roleName, :roundIndex,
                  :turnIndex, :messageType, :status, :summary, :argument, :confidence,
                  cast(:claims as jsonb), cast(:evidenceRefs as jsonb),
                  cast(:challenges as jsonb), cast(:revisionNotes as jsonb),
                  cast(:forecast as jsonb), :createdAt
                ) on conflict (message_id) do nothing
                """)
                .param("messageId", message.messageId().value())
                .param("debateId", message.debateId().value())
                .param("runId", message.runId().value())
                .param("nodeId", message.nodeId().value())
                .param("roleName", message.roleName())
                .param("roundIndex", message.roundIndex())
                .param("turnIndex", message.turnIndex())
                .param("messageType", message.messageType().name())
                .param("status", message.status().name())
                .param("summary", message.content().summary())
                .param("argument", message.content().argument())
                .param("confidence", message.content().confidence())
                .param("claims", json(message.content().claims()))
                .param("evidenceRefs", json(message.content().evidenceReferences()))
                .param("challenges", json(message.content().challenges()))
                .param("revisionNotes", json(message.content().revisionNotes()))
                .param("forecast", message.content().forecast() == null ? null : json(message.content().forecast()))
                .param("createdAt", timestamp(message.createdAt()))
                .update();
        for (var index = 0; index < message.repliesTo().size(); index++) {
            jdbcClient.sql("""
                    insert into agent_message_reply (message_id, replied_to_message_id, reply_order)
                    values (:messageId, :repliedToMessageId, :replyOrder)
                    on conflict do nothing
                    """)
                    .param("messageId", message.messageId().value())
                    .param("repliedToMessageId", message.repliesTo().get(index).value())
                    .param("replyOrder", index)
                    .update();
        }
        if (message.content().forecast() != null) {
            saveForecast(message, message.content().forecast());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentMessage> messages(DebateId debateId) {
        var rows = jdbcClient.sql("""
                select message_id, run_id, node_id, role_name, round_index, turn_index,
                       message_type, status, summary, argument, confidence,
                       claims::text as claims, evidence_refs::text as evidence_refs,
                       challenges::text as challenges, revision_notes::text as revision_notes,
                       forecast::text as forecast,
                       created_at
                from agent_message
                where debate_id = :debateId
                order by case when round_index = 0 then 32767 else round_index end,
                         turn_index, id
                """)
                .param("debateId", debateId.value())
                .query((resultSet, rowNumber) -> messageRow(resultSet))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        var replies = new HashMap<AgentMessageId, List<AgentMessageId>>();
        jdbcClient.sql("""
                select reply.message_id, reply.replied_to_message_id
                from agent_message_reply reply
                join agent_message message on message.message_id = reply.message_id
                where message.debate_id = :debateId
                order by reply.message_id, reply.reply_order
                """)
                .param("debateId", debateId.value())
                .query((resultSet, rowNumber) -> new ReplyRow(
                        new AgentMessageId(resultSet.getString("message_id")),
                        new AgentMessageId(resultSet.getString("replied_to_message_id"))))
                .list()
                .forEach(reply -> replies
                        .computeIfAbsent(reply.messageId(), ignored -> new ArrayList<>())
                        .add(reply.repliedToMessageId()));
        return rows.stream()
                .map(row -> row.toDomain(debateId, replies.getOrDefault(row.messageId(), List.of())))
                .toList();
    }

    @Override
    public void completeRun(WorkflowRunId runId, boolean partial, Instant completedAt) {
        jdbcClient.sql("""
                update workflow_run
                set status = :status, completed_at = :completedAt,
                    current_node_id = null, updated_at = :completedAt,
                    version = version + 1
                where run_id = :runId and status = 'RUNNING'
                """)
                .param("runId", runId.value())
                .param("status", partial ? "PARTIAL" : "COMPLETED")
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    @Override
    public boolean failRun(WorkflowRunId runId, String errorCode, String safeMessage, Instant failedAt) {
        return jdbcClient.sql("""
                update workflow_run
                set status = 'FAILED', completed_at = :failedAt,
                    current_node_id = null, updated_at = :failedAt,
                    version = version + 1
                where run_id = :runId and status in ('ACCEPTED', 'RUNNING')
                """)
                .param("runId", runId.value())
                .param("failedAt", timestamp(failedAt))
                .update() == 1;
    }

    private MessageRow messageRow(ResultSet resultSet) throws SQLException {
        return new MessageRow(
                new AgentMessageId(resultSet.getString("message_id")),
                new WorkflowRunId(resultSet.getString("run_id")),
                new WorkflowNodeId(resultSet.getString("node_id")),
                resultSet.getString("role_name"),
                resultSet.getInt("round_index"),
                resultSet.getInt("turn_index"),
                AgentMessageType.valueOf(resultSet.getString("message_type")),
                AgentMessageStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("summary"),
                resultSet.getString("argument"),
                resultSet.getBigDecimal("confidence"),
                claims(resultSet.getString("claims")),
                strings(resultSet.getString("evidence_refs")),
                strings(resultSet.getString("challenges")),
                strings(resultSet.getString("revision_notes")),
                forecast(resultSet.getString("forecast")),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)));
    }

    private List<AgentClaim> claims(String json) {
        try {
            return List.of(objectMapper.readValue(json, AgentClaim[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode agent claims", exception);
        }
    }

    private List<String> strings(String json) {
        try {
            return List.of(objectMapper.readValue(json, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode agent message strings", exception);
        }
    }

    private ForecastSignal forecast(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ForecastSignal.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode research forecast", exception);
        }
    }

    private void saveForecast(AgentMessage message, ForecastSignal forecast) {
        jdbcClient.sql("""
                insert into research_forecast (
                  forecast_id, workflow_run_id, message_id, instrument_id, exchange, environment,
                  symbol, interval_seconds, horizon_seconds, market_reference_price, direction,
                  reference_price, expected_low, expected_high, invalidation_price,
                  confidence, thesis, evidence_refs, status, issued_at, target_at
                )
                select :forecastId, :runId, :messageId, scope.instrument_id, scope.exchange, scope.environment,
                       scope.symbol, scope.interval_seconds, scope.forecast_horizon_seconds,
                       scope.market_reference_price, :direction, :referencePrice, :expectedLow, :expectedHigh,
                       :invalidationPrice, :confidence, :thesis, cast(:evidenceRefs as jsonb),
                       'PENDING', :issuedAt, :issuedAt + make_interval(secs => scope.forecast_horizon_seconds)
                from research_market_scope scope
                where scope.workflow_run_id = :runId
                on conflict (workflow_run_id) do nothing
                """)
                .param("forecastId", "forecast_" + message.messageId().value().substring("message_".length()))
                .param("runId", message.runId().value())
                .param("messageId", message.messageId().value())
                .param("direction", forecast.direction().name())
                .param("referencePrice", forecast.referencePrice())
                .param("expectedLow", forecast.expectedLow())
                .param("expectedHigh", forecast.expectedHigh())
                .param("invalidationPrice", forecast.invalidationPrice())
                .param("confidence", forecast.confidence())
                .param("thesis", forecast.thesis())
                .param("evidenceRefs", json(forecast.evidenceReferences()))
                .param("issuedAt", timestamp(message.createdAt()))
                .update();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode workflow execution value", exception);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static ResearchMarketScope researchScope(ResultSet resultSet) throws SQLException {
        var instrumentId = resultSet.getString("instrument_id");
        return instrumentId == null
                ? null
                : new ResearchMarketScope(
                        new InstrumentId(instrumentId),
                        ExchangeVenue.valueOf(resultSet.getString("exchange")),
                        ExchangeEnvironment.valueOf(resultSet.getString("environment")),
                        resultSet.getString("symbol"),
                        resultSet.getInt("interval_seconds"),
                        resultSet.getInt("forecast_horizon_seconds"),
                        resultSet.getBigDecimal("market_reference_price"));
    }

    private record RunRoot(
            WorkflowRunStatus status,
            String requestSummary,
            String researchContext,
            String workflowVersionId,
            ResearchMarketScope marketScope) {
    }

    private record ReplyRow(AgentMessageId messageId, AgentMessageId repliedToMessageId) {
    }

    private record MessageRow(
            AgentMessageId messageId,
            WorkflowRunId runId,
            WorkflowNodeId nodeId,
            String roleName,
            int roundIndex,
            int turnIndex,
            AgentMessageType messageType,
            AgentMessageStatus status,
            String summary,
            String argument,
            java.math.BigDecimal confidence,
            List<AgentClaim> claims,
            List<String> evidenceReferences,
            List<String> challenges,
            List<String> revisionNotes,
            ForecastSignal forecast,
            Instant createdAt) {
        AgentMessage toDomain(DebateId debateId, List<AgentMessageId> repliesTo) {
            return new AgentMessage(
                    messageId,
                    debateId,
                    runId,
                    nodeId,
                    roleName,
                    roundIndex,
                    turnIndex,
                    messageType,
                    status,
                    new AgentMessageContent(
                            summary, argument, confidence, claims, evidenceReferences,
                            challenges, revisionNotes, forecast),
                    repliesTo,
                    createdAt);
        }
    }
}
