package io.omnnu.finbot.infrastructure.quant;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.quant.QuantResearchStore;
import io.omnnu.finbot.domain.quant.QuantResearchEvent;
import io.omnnu.finbot.domain.quant.QuantResearchRequest;
import io.omnnu.finbot.domain.quant.ResearchAcceptedEvent;
import io.omnnu.finbot.domain.quant.ResearchCompletedEvent;
import io.omnnu.finbot.domain.quant.ResearchErrorCode;
import io.omnnu.finbot.domain.quant.ResearchFailedEvent;
import io.omnnu.finbot.domain.quant.ResearchRunId;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcQuantResearchStore implements QuantResearchStore {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcQuantResearchStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional
    public void start(QuantResearchRequest request, ResearchArtifactId inputArtifactId) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(inputArtifactId, "inputArtifactId");
        var inserted = jdbcClient.sql("""
                insert into quant_research_run (
                  research_run_id, workflow_run_id, input_artifact_id, idempotency_key,
                  research_kind, strategy_id, strategy_version, status, requested_at
                ) values (
                  :researchRunId, :workflowRunId, :inputArtifactId, :idempotencyKey,
                  :researchKind, :strategyId, :strategyVersion, 'REQUESTED', :requestedAt
                ) on conflict do nothing
                """)
                .param("researchRunId", request.researchRunId().value())
                .param("workflowRunId", request.workflowRunId().value())
                .param("inputArtifactId", inputArtifactId.value())
                .param("idempotencyKey", request.idempotencyKey())
                .param("researchKind", request.specification().kind().name())
                .param("strategyId", request.specification().strategyId())
                .param("strategyVersion", request.specification().strategyVersion())
                .param("requestedAt", timestamp(request.requestedAt()))
                .update();
        if (inserted == 0 && !matchesExistingRequest(request, inputArtifactId)) {
            throw new IllegalStateException("Quant research idempotency key conflicts with another request");
        }
    }

    @Override
    @Transactional
    public void appendEvent(QuantResearchEvent event) {
        Objects.requireNonNull(event, "event");
        jdbcClient.sql("""
                insert into quant_research_event (
                  event_id, research_run_id, sequence, event_type, payload, occurred_at
                ) values (
                  :eventId, :researchRunId, :sequence, :eventType, cast(:payload as jsonb), :occurredAt
                ) on conflict (event_id) do nothing
                """)
                .param("eventId", event.eventId().value())
                .param("researchRunId", event.researchRunId().value())
                .param("sequence", event.sequence())
                .param("eventType", event.eventType())
                .param("payload", json(event))
                .param("occurredAt", timestamp(event.occurredAt()))
                .update();
        if (event instanceof ResearchAcceptedEvent) {
            jdbcClient.sql("""
                    update quant_research_run
                    set status = 'RUNNING', started_at = coalesce(started_at, :startedAt)
                    where research_run_id = :researchRunId and status = 'REQUESTED'
                    """)
                    .param("researchRunId", event.researchRunId().value())
                    .param("startedAt", timestamp(event.occurredAt()))
                    .update();
        }
    }

    @Override
    @Transactional
    public void complete(
            ResearchCompletedEvent completed,
            ResearchArtifactId resultArtifactId,
            Instant completedAt) {
        Objects.requireNonNull(completed, "completed");
        Objects.requireNonNull(resultArtifactId, "resultArtifactId");
        Objects.requireNonNull(completedAt, "completedAt");
        var workflowRunId = workflowRunId(completed.researchRunId());
        completed.metrics().forEach(metric -> jdbcClient.sql("""
                insert into quant_metric_fact (research_run_id, metric_name, metric_value, metric_unit)
                values (:researchRunId, :metricName, :metricValue, :metricUnit)
                on conflict (research_run_id, metric_name) do update
                set metric_value = excluded.metric_value, metric_unit = excluded.metric_unit
                """)
                .param("researchRunId", completed.researchRunId().value())
                .param("metricName", metric.name())
                .param("metricValue", metric.value())
                .param("metricUnit", metric.unit().name())
                .update());

        var content = objectMapper.createObjectNode();
        content.put("researchRunId", completed.researchRunId().value());
        content.put("observationCount", completed.observationCount());
        content.put("resultFingerprint", completed.resultFingerprint());
        content.set("metrics", objectMapper.valueToTree(completed.metrics()));
        content.set("artifacts", objectMapper.valueToTree(completed.artifacts()));
        var contentJson = json(content);
        var provenance = objectMapper.createObjectNode();
        provenance.put("producer", "finbot-quant-service");
        provenance.put("terminalEventId", completed.eventId().value());
        provenance.put("terminalSequence", completed.sequence());
        jdbcClient.sql("""
                insert into research_artifact (
                  artifact_id, workflow_run_id, artifact_type, schema_version,
                  content, provenance, content_hash, created_at
                ) values (
                  :artifactId, :workflowRunId, 'QUANT_RESULT', 1,
                  cast(:content as jsonb), cast(:provenance as jsonb), :contentHash, :createdAt
                ) on conflict (artifact_id) do nothing
                """)
                .param("artifactId", resultArtifactId.value())
                .param("workflowRunId", workflowRunId)
                .param("content", contentJson)
                .param("provenance", json(provenance))
                .param("contentHash", sha256(contentJson))
                .param("createdAt", timestamp(completedAt))
                .update();
        jdbcClient.sql("""
                update quant_research_run
                set status = 'COMPLETED', observation_count = :observationCount,
                    result_fingerprint = :resultFingerprint, error_code = null,
                    error_message = null, started_at = coalesce(started_at, requested_at),
                    completed_at = :completedAt
                where research_run_id = :researchRunId
                  and status in ('REQUESTED', 'RUNNING', 'COMPLETED')
                """)
                .param("researchRunId", completed.researchRunId().value())
                .param("observationCount", completed.observationCount())
                .param("resultFingerprint", completed.resultFingerprint())
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    @Override
    public void fail(ResearchFailedEvent failed, Instant failedAt) {
        Objects.requireNonNull(failed, "failed");
        failTransport(failed.researchRunId(), failed.code(), failed.safeMessage(), failedAt);
    }

    @Override
    public void failTransport(
            ResearchRunId researchRunId,
            ResearchErrorCode errorCode,
            String safeMessage,
            Instant failedAt) {
        Objects.requireNonNull(researchRunId, "researchRunId");
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(safeMessage, "safeMessage");
        Objects.requireNonNull(failedAt, "failedAt");
        jdbcClient.sql("""
                update quant_research_run
                set status = :status, error_code = :errorCode, error_message = :errorMessage,
                    started_at = coalesce(started_at, requested_at), completed_at = :failedAt
                where research_run_id = :researchRunId and status in ('REQUESTED', 'RUNNING')
                """)
                .param("researchRunId", researchRunId.value())
                .param("status", errorCode == ResearchErrorCode.CANCELLED ? "CANCELLED" : "FAILED")
                .param("errorCode", errorCode.name())
                .param("errorMessage", safeMessage)
                .param("failedAt", timestamp(failedAt))
                .update();
    }

    private boolean matchesExistingRequest(
            QuantResearchRequest request,
            ResearchArtifactId inputArtifactId) {
        return jdbcClient.sql("""
                select count(*)
                from quant_research_run
                where research_run_id = :researchRunId
                  and workflow_run_id = :workflowRunId
                  and input_artifact_id = :inputArtifactId
                  and idempotency_key = :idempotencyKey
                  and research_kind = :researchKind
                  and strategy_id = :strategyId
                  and strategy_version = :strategyVersion
                """)
                .param("researchRunId", request.researchRunId().value())
                .param("workflowRunId", request.workflowRunId().value())
                .param("inputArtifactId", inputArtifactId.value())
                .param("idempotencyKey", request.idempotencyKey())
                .param("researchKind", request.specification().kind().name())
                .param("strategyId", request.specification().strategyId())
                .param("strategyVersion", request.specification().strategyVersion())
                .query(Long.class)
                .single() == 1L;
    }

    private String workflowRunId(ResearchRunId researchRunId) {
        return jdbcClient.sql("""
                select workflow_run_id from quant_research_run where research_run_id = :researchRunId
                """)
                .param("researchRunId", researchRunId.value())
                .query(String.class)
                .single();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode quant research persistence value", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
