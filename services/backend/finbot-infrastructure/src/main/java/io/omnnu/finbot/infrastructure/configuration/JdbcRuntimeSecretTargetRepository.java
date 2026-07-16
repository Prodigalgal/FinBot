package io.omnnu.finbot.infrastructure.configuration;

import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretTarget;
import io.omnnu.finbot.application.configuration.RuntimeSecretTargetRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcRuntimeSecretTargetRepository implements RuntimeSecretTargetRepository {
    private final JdbcClient jdbcClient;

    public JdbcRuntimeSecretTargetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    public Optional<RuntimeSecretTarget> find(
            RuntimeSecretScope scope,
            String targetId,
            String secretName) {
        var query = switch (scope) {
            case AI_PROVIDER -> "select api_key_env from ai_provider_profile where profile_id = :targetId and deleted_at is null";
            case EXCHANGE_ACCOUNT -> switch (secretName) {
                case "API_KEY" -> "select api_key_env from exchange_account where account_id = :targetId";
                case "API_SECRET" -> "select api_secret_env from exchange_account where account_id = :targetId";
                default -> throw new IllegalArgumentException("Unsupported exchange account secret");
            };
            case PROXY_ROUTE -> "select proxy_url_env from network_proxy_route where route_type = :targetId";
            case PROXY_GATEWAY -> switch (secretName) {
                case "SUBSCRIPTION_URL" -> "select subscription_url_env from proxy_gateway_profile where gateway_id = :targetId";
                case "INLINE_NODES" -> "select inline_nodes_env from proxy_gateway_profile where gateway_id = :targetId";
                default -> throw new IllegalArgumentException("Unsupported proxy gateway secret");
            };
            case INFORMATION_SOURCE -> "select credential_env from information_source where source_id = :targetId";
        };
        return jdbcClient.sql(query)
                .param("targetId", targetId)
                .query((resultSet, rowNumber) -> new RuntimeSecretTarget(resultSet.getString(1)))
                .optional();
    }
}
