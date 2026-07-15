package io.omnnu.finbot.infrastructure.network;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.network.NetworkDiagnostic;
import io.omnnu.finbot.application.network.NetworkDiagnosticBatchClaim;
import io.omnnu.finbot.application.network.NetworkDiagnosticStore;
import io.omnnu.finbot.application.network.NetworkDiagnosticStart;
import io.omnnu.finbot.application.network.NetworkProbeResult;
import io.omnnu.finbot.application.network.ProxyRouteDecision;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcNetworkDiagnosticStore implements NetworkDiagnosticStore {
    private final JdbcClient jdbcClient;

    public JdbcNetworkDiagnosticStore(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional
    public NetworkDiagnosticBatchClaim prepareBatch(
            String batchId,
            String batchIdempotencyKey,
            String requestFingerprint,
            Instant createdAt) {
        var inserted = jdbcClient.sql("""
                insert into network_diagnostic_batch (
                  batch_id, idempotency_key, request_fingerprint, created_at
                ) values (
                  :batchId, :idempotencyKey, :requestFingerprint, :createdAt
                ) on conflict (idempotency_key) do nothing
                """)
                .param("batchId", batchId)
                .param("idempotencyKey", batchIdempotencyKey)
                .param("requestFingerprint", requestFingerprint)
                .param("createdAt", timestamp(createdAt))
                .update();
        return jdbcClient.sql("""
                select request_fingerprint
                from network_diagnostic_batch where idempotency_key = :idempotencyKey
                """)
                .param("idempotencyKey", batchIdempotencyKey)
                .query((resultSet, rowNumber) -> new NetworkDiagnosticBatchClaim(
                        resultSet.getString("request_fingerprint"), inserted == 1))
                .single();
    }

    @Override
    @Transactional
    public NetworkDiagnosticStart start(
            String diagnosticId,
            String batchIdempotencyKey,
            OutboundRoute route,
            Instant startedAt) {
        var inserted = jdbcClient.sql("""
                insert into network_diagnostic_run (
                  diagnostic_id, batch_idempotency_key, route_type,
                  status, proxy_configured, proxied,
                  safe_endpoint, started_at
                ) values (
                  :diagnosticId, :batchIdempotencyKey, :routeType,
                  'RUNNING', false, false, 'pending', :startedAt
                ) on conflict (batch_idempotency_key, route_type) do nothing
                """)
                .param("diagnosticId", diagnosticId)
                .param("batchIdempotencyKey", batchIdempotencyKey)
                .param("routeType", route.name())
                .param("startedAt", timestamp(startedAt))
                .update();
        var diagnostic = inserted == 1
                ? find(diagnosticId)
                : findByBatchRoute(batchIdempotencyKey, route);
        return new NetworkDiagnosticStart(diagnostic, inserted == 1);
    }

    @Override
    @Transactional
    public NetworkDiagnostic complete(
            String diagnosticId,
            ProxyRouteDecision decision,
            NetworkProbeResult result,
            Instant completedAt) {
        jdbcClient.sql("""
                update network_diagnostic_run
                set status = :status,
                    proxy_configured = :proxyConfigured,
                    proxied = :proxied,
                    safe_endpoint = :safeEndpoint,
                    http_status = :httpStatus,
                    latency_milliseconds = :latency,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    completed_at = :completedAt
                where diagnostic_id = :diagnosticId and status = 'RUNNING'
                """)
                .param("diagnosticId", diagnosticId)
                .param("status", result.ready() ? "READY" : "FAILED")
                .param("proxyConfigured", decision.proxyUrl() != null)
                .param("proxied", decision.proxied())
                .param("safeEndpoint", decision.redactedEndpoint())
                .param("httpStatus", result.httpStatus())
                .param("latency", result.latencyMilliseconds())
                .param("errorCode", result.errorCode())
                .param("errorMessage", safe(result.errorMessage()))
                .param("completedAt", timestamp(completedAt))
                .update();
        return find(diagnosticId);
    }

    @Override
    @Transactional
    public NetworkDiagnostic block(
            String diagnosticId,
            OutboundRoute route,
            String safeMessage,
            Instant completedAt) {
        jdbcClient.sql("""
                update network_diagnostic_run
                set status = 'BLOCKED',
                    safe_endpoint = :safeEndpoint,
                    error_code = 'ROUTE_UNAVAILABLE',
                    error_message = :errorMessage,
                    completed_at = :completedAt
                where diagnostic_id = :diagnosticId and route_type = :routeType and status = 'RUNNING'
                """)
                .param("diagnosticId", diagnosticId)
                .param("routeType", route.name())
                .param("safeEndpoint", route.name().toLowerCase(java.util.Locale.ROOT))
                .param("errorMessage", safe(safeMessage))
                .param("completedAt", timestamp(completedAt))
                .update();
        return find(diagnosticId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NetworkDiagnostic> list(int limit) {
        return jdbcClient.sql("""
                select diagnostic_id, route_type, status, proxy_configured, proxied,
                       safe_endpoint, http_status, latency_milliseconds, error_code,
                       error_message, started_at, completed_at
                from network_diagnostic_run
                order by started_at desc, id desc
                limit :limit
                """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> diagnostic(resultSet))
                .list();
    }

    private NetworkDiagnostic find(String diagnosticId) {
        return jdbcClient.sql("""
                select diagnostic_id, route_type, status, proxy_configured, proxied,
                       safe_endpoint, http_status, latency_milliseconds, error_code,
                       error_message, started_at, completed_at
                from network_diagnostic_run where diagnostic_id = :diagnosticId
                """)
                .param("diagnosticId", diagnosticId)
                .query((resultSet, rowNumber) -> diagnostic(resultSet))
                .single();
    }

    private NetworkDiagnostic findByBatchRoute(
            String batchIdempotencyKey,
            OutboundRoute route) {
        return jdbcClient.sql("""
                select diagnostic_id, route_type, status, proxy_configured, proxied,
                       safe_endpoint, http_status, latency_milliseconds, error_code,
                       error_message, started_at, completed_at
                from network_diagnostic_run
                where batch_idempotency_key = :batchIdempotencyKey and route_type = :routeType
                """)
                .param("batchIdempotencyKey", batchIdempotencyKey)
                .param("routeType", route.name())
                .query((resultSet, rowNumber) -> diagnostic(resultSet))
                .single();
    }

    private static NetworkDiagnostic diagnostic(ResultSet resultSet) throws SQLException {
        return new NetworkDiagnostic(
                resultSet.getString("diagnostic_id"),
                OutboundRoute.valueOf(resultSet.getString("route_type")),
                resultSet.getString("status"),
                resultSet.getBoolean("proxy_configured"),
                resultSet.getBoolean("proxied"),
                resultSet.getString("safe_endpoint"),
                resultSet.getObject("http_status", Integer.class),
                resultSet.getObject("latency_milliseconds", Long.class),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getObject("started_at", OffsetDateTime.class).toInstant(),
                nullableInstant(resultSet.getObject("completed_at", OffsetDateTime.class)));
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }
}
