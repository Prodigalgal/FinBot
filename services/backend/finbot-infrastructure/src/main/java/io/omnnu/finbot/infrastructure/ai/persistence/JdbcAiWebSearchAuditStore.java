package io.omnnu.finbot.infrastructure.ai.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.ingestion.port.out.AiWebSearchAuditStore;
import io.omnnu.finbot.domain.ingestion.AiWebSearchBinding;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcAiWebSearchAuditStore implements AiWebSearchAuditStore {
    private final JdbcClient jdbcClient;

    public JdbcAiWebSearchAuditStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public void start(
            String invocationId,
            SourceId sourceId,
            AiWebSearchBinding binding,
            String queryHash,
            Instant startedAt) {
        jdbcClient.sql("""
                insert into ai_web_search_invocation (
                  invocation_id, source_id, provider_profile_id, model_name,
                  tool_type, query_hash, status, started_at
                ) values (
                  :invocationId, :sourceId, :providerProfileId, :modelName,
                  :toolType, :queryHash, 'RUNNING', :startedAt
                )
                """)
                .param("invocationId", invocationId)
                .param("sourceId", sourceId.value())
                .param("providerProfileId", binding.providerProfileId().value())
                .param("modelName", binding.modelName())
                .param("toolType", binding.tool().name())
                .param("queryHash", queryHash)
                .param("startedAt", timestamp(startedAt))
                .update();
    }

    @Override
    public void complete(
            String invocationId,
            String providerRequestId,
            long inputTokens,
            long outputTokens,
            int citationCount,
            Instant completedAt) {
        jdbcClient.sql("""
                update ai_web_search_invocation
                set status = 'COMPLETED', provider_request_id = :providerRequestId,
                    input_tokens = :inputTokens, output_tokens = :outputTokens,
                    citation_count = :citationCount, completed_at = :completedAt
                where invocation_id = :invocationId and status = 'RUNNING'
                """)
                .param("invocationId", invocationId)
                .param("providerRequestId", providerRequestId)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .param("citationCount", citationCount)
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    @Override
    public void fail(
            String invocationId,
            String errorCode,
            String errorMessage,
            Instant completedAt) {
        jdbcClient.sql("""
                update ai_web_search_invocation
                set status = 'FAILED', error_code = :errorCode,
                    error_message = :errorMessage, completed_at = :completedAt
                where invocation_id = :invocationId and status = 'RUNNING'
                """)
                .param("invocationId", invocationId)
                .param("errorCode", Objects.requireNonNull(errorCode, "errorCode"))
                .param("errorMessage", safe(errorMessage))
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var redacted = value
                .replaceAll("(?i)(api[_-]?key|secret|token|password)\\s*[=:]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .strip();
        return redacted.substring(0, Math.min(redacted.length(), 2_000));
    }
}
