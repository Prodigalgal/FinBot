package io.omnnu.finbot.infrastructure.ai;

import io.omnnu.finbot.application.ai.AiRuntimeBinding;
import io.omnnu.finbot.application.ai.AiRuntimeBindingResolver;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import java.net.URI;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class JdbcAiRuntimeProfileResolver implements AiRuntimeBindingResolver {
    private final JdbcClient jdbcClient;

    public JdbcAiRuntimeProfileResolver(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    AiRuntimeProfile resolve(AiProviderProfileId profileId) {
        var stored = jdbcClient.sql("""
                select protocol, reasoning_parameter_style, base_url, base_url_env, api_key_env,
                       enabled, request_timeout_seconds
                from ai_provider_profile where profile_id = :profileId
                """)
                .param("profileId", profileId.value())
                .query((resultSet, rowNumber) -> new StoredProfile(
                        AiProtocol.valueOf(resultSet.getString("protocol")),
                        ReasoningParameterStyle.valueOf(resultSet.getString("reasoning_parameter_style")),
                        resultSet.getString("base_url"),
                        resultSet.getString("base_url_env"),
                        resultSet.getString("api_key_env"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getInt("request_timeout_seconds")))
                .optional()
                .orElseThrow(() -> new AiProviderConfigurationException("AI provider profile does not exist"));
        if (!stored.enabled()) {
            throw new AiProviderConfigurationException("AI provider profile is disabled");
        }
        var baseUrl = stored.baseUrl() == null
                ? environment(stored.baseUrlEnv(), "AI provider base URL")
                : stored.baseUrl();
        var uri = parseAndValidateUri(baseUrl);
        var apiKey = environment(stored.apiKeyEnv(), "AI provider credential");
        return new AiRuntimeProfile(
                profileId,
                stored.protocol(),
                stored.reasoningParameterStyle(),
                uri,
                apiKey,
                stored.requestTimeoutSeconds());
    }

    @Override
    public AiRuntimeBinding resolve(AiModelBinding binding) {
        var resolved = jdbcClient.sql("""
                select provider.protocol, model.maximum_reasoning_effort
                from ai_provider_profile provider
                join ai_model_profile model
                  on model.provider_profile_id = provider.profile_id
                where provider.profile_id = :profileId
                  and model.model_name = :modelName
                  and provider.enabled = true
                  and model.enabled = true
                """)
                .param("profileId", binding.providerProfileId().value())
                .param("modelName", binding.modelName())
                .query((resultSet, rowNumber) -> new AiRuntimeBinding(
                        AiProtocol.valueOf(resultSet.getString("protocol")),
                        io.omnnu.finbot.domain.configuration.ReasoningEffort.valueOf(
                                resultSet.getString("maximum_reasoning_effort"))))
                .optional()
                .orElseThrow(() -> new AiProviderConfigurationException(
                        "AI provider or model profile does not exist or is disabled"));
        if (!resolved.maximumReasoningEffort().supports(binding.reasoningEffort())) {
            throw new AiProviderConfigurationException(
                    "Requested reasoning effort exceeds the configured model capability");
        }
        return resolved;
    }

    private static String environment(String name, String label) {
        var value = name == null ? null : System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AiProviderConfigurationException(label + " is not configured");
        }
        return value.strip();
    }

    private static URI parseAndValidateUri(String value) {
        var uri = URI.create(value.strip());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new AiProviderConfigurationException("AI provider base URL is invalid");
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        if ("http".equalsIgnoreCase(uri.getScheme())
                && (localOrPrivate(uri.getHost()) || insecureHttpExplicitlyAllowed())) {
            return uri;
        }
        throw new AiProviderConfigurationException("Cleartext public AI provider transport is prohibited");
    }

    private static boolean insecureHttpExplicitlyAllowed() {
        return Boolean.parseBoolean(System.getenv("FINBOT_ALLOW_INSECURE_AI_HTTP"));
    }

    private static boolean localOrPrivate(String host) {
        return "localhost".equalsIgnoreCase(host)
                || host.startsWith("127.")
                || host.startsWith("10.")
                || host.startsWith("192.168.")
                || private172(host)
                || host.endsWith(".svc")
                || host.endsWith(".svc.cluster.local");
    }

    private static boolean private172(String host) {
        if (!host.startsWith("172.")) {
            return false;
        }
        var parts = host.split("\\.");
        if (parts.length < 2) {
            return false;
        }
        try {
            var second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private record StoredProfile(
            AiProtocol protocol,
            ReasoningParameterStyle reasoningParameterStyle,
            String baseUrl,
            String baseUrlEnv,
            String apiKeyEnv,
            boolean enabled,
            int requestTimeoutSeconds) {
    }
}
