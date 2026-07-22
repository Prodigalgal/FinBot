package io.omnnu.finbot.infrastructure.network.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.network.dto.ProxyGatewayProfile;
import io.omnnu.finbot.application.network.port.out.ProxyGatewayProfileRepository;
import io.omnnu.finbot.application.network.dto.ProxyEngine;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcProxyGatewayProfileRepository implements ProxyGatewayProfileRepository {
    private static final String SELECT = """
            select gateway_id, display_name, control_url, subscription_url_env, inline_nodes_env,
                   engine, preferred_names::text, maximum_nodes, refresh_seconds, allow_insecure_tls,
                   enabled, version, updated_at
            from proxy_gateway_profile
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcProxyGatewayProfileRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public List<ProxyGatewayProfile> list() {
        return jdbcClient.sql(SELECT + " order by gateway_id")
                .query((resultSet, rowNumber) -> profile(resultSet))
                .list();
    }

    @Override
    public Optional<ProxyGatewayProfile> find(String gatewayId) {
        return jdbcClient.sql(SELECT + " where gateway_id = :gatewayId")
                .param("gatewayId", gatewayId)
                .query((resultSet, rowNumber) -> profile(resultSet))
                .optional();
    }

    @Override
    @Transactional
    public Optional<ProxyGatewayProfile> update(
            ProxyGatewayProfile profile,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                update proxy_gateway_profile
                set engine = :engine,
                    preferred_names = cast(:preferredNames as jsonb),
                    maximum_nodes = :maximumNodes,
                    refresh_seconds = :refreshSeconds,
                    allow_insecure_tls = :allowInsecureTls,
                    enabled = :enabled,
                    version = version + 1,
                    updated_at = :updatedAt
                where gateway_id = :gatewayId and version = :expectedVersion
                """)
                .param("gatewayId", profile.gatewayId())
                .param("engine", profile.engine().name())
                .param("preferredNames", json(profile.preferredNames()))
                .param("maximumNodes", profile.maximumNodes())
                .param("refreshSeconds", profile.refreshSeconds())
                .param("allowInsecureTls", profile.allowInsecureTls())
                .param("enabled", profile.enabled())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? find(profile.gatewayId()) : Optional.empty();
    }

    private ProxyGatewayProfile profile(ResultSet resultSet) throws SQLException {
        return new ProxyGatewayProfile(
                resultSet.getString("gateway_id"),
                resultSet.getString("display_name"),
                URI.create(resultSet.getString("control_url")),
                resultSet.getString("subscription_url_env"),
                resultSet.getString("inline_nodes_env"),
                ProxyEngine.valueOf(resultSet.getString("engine")),
                strings(resultSet.getString("preferred_names")),
                resultSet.getInt("maximum_nodes"),
                resultSet.getInt("refresh_seconds"),
                resultSet.getBoolean("allow_insecure_tls"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("version"),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private List<String> strings(String value) {
        try {
            return List.of(objectMapper.readValue(value, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode proxy gateway preferred names", exception);
        }
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode proxy gateway preferred names", exception);
        }
    }
}
