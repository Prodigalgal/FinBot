package io.omnnu.finbot.infrastructure.research.persistence;

import io.omnnu.finbot.application.research.dto.ResearchHistoryDetail;
import io.omnnu.finbot.application.research.port.out.ResearchHistoryRepository;
import io.omnnu.finbot.application.research.dto.ResearchReplaySource;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcResearchHistoryRepository implements ResearchHistoryRepository {
    private static final String SUMMARY_COLUMNS = """
            run_id, workflow_type, status, trigger_type, request_summary,
            workflow_version_id, total_input_tokens, total_output_tokens,
            total_cost_usd, accepted_at, started_at, completed_at, updated_at
            """;

    private final JdbcClient jdbcClient;

    public JdbcResearchHistoryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResearchHistoryDetail.Summary> list(WorkflowRunStatus status, int limit) {
        var sql = "select " + SUMMARY_COLUMNS + " from workflow_run";
        if (status != null) {
            sql += " where status = :status";
        }
        sql += " order by accepted_at desc, id desc limit :limit";
        var statement = jdbcClient.sql(sql).param("limit", Math.max(1, Math.min(limit, 200)));
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        return statement.query((resultSet, rowNumber) -> summary(resultSet)).list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchHistoryDetail> find(WorkflowRunId runId) {
        var summary = jdbcClient.sql("select " + SUMMARY_COLUMNS + " from workflow_run where run_id = :runId")
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> summary(resultSet))
                .optional();
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResearchHistoryDetail(
                summary.orElseThrow(),
                events(runId),
                checkpoints(runId),
                agentTurns(runId),
                aiInvocations(runId),
                artifacts(runId),
                quantRuns(runId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchReplaySource> replaySource(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select workflow_type, status, trigger_type, request_summary,
                       workflow_version_id, idempotency_key
                from workflow_run where run_id = :runId
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new ResearchReplaySource(
                        runId,
                        WorkflowType.valueOf(resultSet.getString("workflow_type")),
                        WorkflowRunStatus.valueOf(resultSet.getString("status")),
                        WorkflowTrigger.valueOf(resultSet.getString("trigger_type")),
                        resultSet.getString("request_summary"),
                        new WorkflowVersionId(resultSet.getString("workflow_version_id")),
                        resultSet.getString("idempotency_key")))
                .optional();
    }

    private List<ResearchHistoryDetail.Event> events(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select sequence, event_type, payload::text as payload_json, occurred_at
                from workflow_event where run_id = :runId order by sequence
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new ResearchHistoryDetail.Event(
                        resultSet.getLong("sequence"),
                        resultSet.getString("event_type"),
                        resultSet.getString("payload_json"),
                        instant(resultSet, "occurred_at")))
                .list();
    }

    private List<ResearchHistoryDetail.Checkpoint> checkpoints(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select checkpoint.node_id, coalesce(node.display_name, checkpoint.node_id) as display_name,
                       checkpoint.round_index, checkpoint.attempt, checkpoint.status,
                       checkpoint.result_summary, checkpoint.error_code, checkpoint.error_message,
                       checkpoint.started_at, checkpoint.completed_at, checkpoint.updated_at
                from workflow_node_checkpoint checkpoint
                join workflow_run run on run.run_id = checkpoint.run_id
                left join workflow_node_definition node
                  on node.version_id = run.workflow_version_id and node.node_id = checkpoint.node_id
                where checkpoint.run_id = :runId
                order by checkpoint.round_index, checkpoint.id
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new ResearchHistoryDetail.Checkpoint(
                        resultSet.getString("node_id"),
                        resultSet.getString("display_name"),
                        resultSet.getInt("round_index"),
                        resultSet.getInt("attempt"),
                        resultSet.getString("status"),
                        resultSet.getString("result_summary"),
                        resultSet.getString("error_code"),
                        resultSet.getString("error_message"),
                        instant(resultSet, "started_at"),
                        instant(resultSet, "completed_at"),
                        instant(resultSet, "updated_at")))
                .list();
    }

    private List<ResearchHistoryDetail.AgentTurn> agentTurns(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select message_id, node_id, role_name, round_index, turn_index,
                       message_type, status, summary, argument, confidence,
                       claims::text as claims_json, evidence_refs::text as evidence_refs_json,
                       challenges::text as challenges_json,
                       revision_notes::text as revision_notes_json, created_at
                from agent_message where run_id = :runId
                order by case when round_index = 0 then 32767 else round_index end, turn_index, id
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new ResearchHistoryDetail.AgentTurn(
                        resultSet.getString("message_id"),
                        resultSet.getString("node_id"),
                        resultSet.getString("role_name"),
                        resultSet.getInt("round_index"),
                        resultSet.getInt("turn_index"),
                        resultSet.getString("message_type"),
                        resultSet.getString("status"),
                        resultSet.getString("summary"),
                        resultSet.getString("argument"),
                        resultSet.getBigDecimal("confidence"),
                        resultSet.getString("claims_json"),
                        resultSet.getString("evidence_refs_json"),
                        resultSet.getString("challenges_json"),
                        resultSet.getString("revision_notes_json"),
                        instant(resultSet, "created_at")))
                .list();
    }

    private List<ResearchHistoryDetail.AiInvocation> aiInvocations(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select invocation_id, node_id, provider_profile_id, model_name,
                       reasoning_effort, status, input_tokens, output_tokens,
                       estimated_cost_usd, latency_milliseconds, finish_reason,
                       error_code, error_message, started_at, completed_at
                from ai_invocation where run_id = :runId order by started_at, id
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new ResearchHistoryDetail.AiInvocation(
                        resultSet.getString("invocation_id"),
                        resultSet.getString("node_id"),
                        resultSet.getString("provider_profile_id"),
                        resultSet.getString("model_name"),
                        ReasoningEffort.valueOf(resultSet.getString("reasoning_effort")),
                        resultSet.getString("status"),
                        resultSet.getLong("input_tokens"),
                        resultSet.getLong("output_tokens"),
                        resultSet.getBigDecimal("estimated_cost_usd"),
                        (Long) resultSet.getObject("latency_milliseconds"),
                        resultSet.getString("finish_reason"),
                        resultSet.getString("error_code"),
                        resultSet.getString("error_message"),
                        instant(resultSet, "started_at"),
                        instant(resultSet, "completed_at")))
                .list();
    }

    private List<ResearchHistoryDetail.Artifact> artifacts(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select artifact_id, artifact_type, schema_version, content_hash, created_at
                from research_artifact where workflow_run_id = :runId
                union all
                select artifact_id, 'MARKET_DATA', schema_version, sha256_hex, created_at
                from market_data_artifact where workflow_run_id = :runId
                order by created_at, artifact_id
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new ResearchHistoryDetail.Artifact(
                        resultSet.getString("artifact_id"),
                        resultSet.getString("artifact_type"),
                        resultSet.getInt("schema_version"),
                        resultSet.getString("content_hash"),
                        instant(resultSet, "created_at")))
                .list();
    }

    private List<ResearchHistoryDetail.QuantRun> quantRuns(WorkflowRunId runId) {
        return jdbcClient.sql("""
                select run.research_run_id, run.research_kind, run.strategy_id,
                       run.strategy_version, run.status, run.observation_count,
                       run.result_fingerprint,
                       coalesce((select jsonb_object_agg(metric.metric_name, metric.metric_value)
                                 from quant_metric_fact metric
                                 where metric.research_run_id = run.research_run_id), '{}'::jsonb)::text as metrics_json,
                       run.error_code, run.error_message, run.requested_at, run.completed_at
                from quant_research_run run
                where run.workflow_run_id = :runId
                order by run.requested_at, run.id
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> new ResearchHistoryDetail.QuantRun(
                        resultSet.getString("research_run_id"),
                        resultSet.getString("research_kind"),
                        resultSet.getString("strategy_id"),
                        resultSet.getString("strategy_version"),
                        resultSet.getString("status"),
                        resultSet.getLong("observation_count"),
                        resultSet.getString("result_fingerprint"),
                        resultSet.getString("metrics_json"),
                        resultSet.getString("error_code"),
                        resultSet.getString("error_message"),
                        instant(resultSet, "requested_at"),
                        instant(resultSet, "completed_at")))
                .list();
    }

    private static ResearchHistoryDetail.Summary summary(ResultSet resultSet) throws SQLException {
        return new ResearchHistoryDetail.Summary(
                resultSet.getString("run_id"),
                WorkflowType.valueOf(resultSet.getString("workflow_type")),
                WorkflowRunStatus.valueOf(resultSet.getString("status")),
                WorkflowTrigger.valueOf(resultSet.getString("trigger_type")),
                resultSet.getString("request_summary"),
                resultSet.getString("workflow_version_id"),
                resultSet.getLong("total_input_tokens"),
                resultSet.getLong("total_output_tokens"),
                resultSet.getBigDecimal("total_cost_usd"),
                instant(resultSet, "accepted_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "completed_at"),
                instant(resultSet, "updated_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
