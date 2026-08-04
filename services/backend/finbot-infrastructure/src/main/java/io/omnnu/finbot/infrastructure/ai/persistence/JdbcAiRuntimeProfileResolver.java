package io.omnnu.finbot.infrastructure.ai.persistence;

import io.omnnu.finbot.infrastructure.ai.adapter.AiProviderConfigurationException;
import io.omnnu.finbot.infrastructure.ai.client.AiRuntimeProfile;
import io.omnnu.finbot.infrastructure.ai.client.AiRuntimeProfileResolver;

import io.omnnu.finbot.application.ai.dto.AiRuntimeBinding;
import io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver;
import io.omnnu.finbot.application.configuration.port.out.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore;
import io.omnnu.finbot.application.configuration.validation.PublicAiProviderEndpointPolicy;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class JdbcAiRuntimeProfileResolver implements AiRuntimeBindingResolver, AiRuntimeProfileResolver {
    private final JdbcClient jdbcClient;
    private final RuntimeSecretStore runtimeSecrets;
    private final EnvironmentValueResolver environment;

    public JdbcAiRuntimeProfileResolver(
            JdbcClient jdbcClient,
            RuntimeSecretStore runtimeSecrets,
            EnvironmentValueResolver environment) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public AiRuntimeProfile resolve(AiProviderProfileId profileId) {
        return resolveProvider(profileId, "");
    }

    @Override
    public AiRuntimeProfile resolve(AiProviderProfileId profileId, String modelName) {
        return resolveProvider(profileId, Objects.requireNonNull(modelName, "modelName"));
    }

    private AiRuntimeProfile resolveProvider(AiProviderProfileId profileId, String modelName) {
        var stored = jdbcClient.sql("""
                select provider.protocol, provider.reasoning_parameter_style,
                       provider.base_url, provider.base_url_env, provider.api_key_env,
                       provider.enabled, provider.request_timeout_seconds,
                       provider.maximum_concurrent_requests, provider.acquire_timeout_seconds, provider.version,
                       coalesce(model.token_limit_parameter_style, 'PROTOCOL_DEFAULT') token_limit_parameter_style
                from ai_provider_profile provider
                left join ai_model_profile model
                  on model.provider_profile_id = provider.profile_id
                 and model.model_name = :modelName
                 and model.enabled = true
                where provider.profile_id = :profileId and provider.deleted_at is null
                """)
                .param("profileId", profileId.value())
                .param("modelName", modelName)
                .query((resultSet, rowNumber) -> new StoredProfile(
                        AiProtocol.valueOf(resultSet.getString("protocol")),
                        ReasoningParameterStyle.valueOf(resultSet.getString("reasoning_parameter_style")),
                        resultSet.getString("base_url"),
                        resultSet.getString("base_url_env"),
                        resultSet.getString("api_key_env"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getInt("request_timeout_seconds"),
                        resultSet.getInt("maximum_concurrent_requests"),
                        resultSet.getInt("acquire_timeout_seconds"),
                        resultSet.getLong("version"),
                        TokenLimitParameterStyle.valueOf(
                                resultSet.getString("token_limit_parameter_style"))))
                .optional()
                .orElseThrow(() -> new AiProviderConfigurationException("AI provider profile does not exist"));
        if (!stored.enabled()) {
            throw new AiProviderConfigurationException("AI provider profile is disabled");
        }
        var baseUrl = stored.baseUrl() == null
                ? environment(stored.baseUrlEnv(), "AI provider base URL")
                : stored.baseUrl();
        var uri = parseAndValidateUri(baseUrl);
        var apiKey = runtimeSecrets.resolve(
                        RuntimeSecretScope.AI_PROVIDER,
                        profileId.value(),
                        "API_KEY",
                        stored.apiKeyEnv())
                .orElseThrow(() -> new AiProviderConfigurationException(
                        "AI provider credential is not configured"));
        return new AiRuntimeProfile(
                profileId,
                stored.protocol(),
                stored.reasoningParameterStyle(),
                stored.tokenLimitParameterStyle(),
                uri,
                apiKey,
                stored.requestTimeoutSeconds(),
                stored.maximumConcurrentRequests(),
                stored.acquireTimeoutSeconds(),
                stored.configurationVersion());
    }

    @Override
    public AiRuntimeBinding resolve(AiModelBinding binding) {
        var resolved = jdbcClient.sql("""
                select provider.protocol, model.maximum_reasoning_effort,
                       provider.acquire_timeout_seconds
                from ai_provider_profile provider
                join ai_model_profile model
                  on model.provider_profile_id = provider.profile_id
                where provider.profile_id = :profileId
                  and model.model_name = :modelName
                  and provider.deleted_at is null
                  and provider.enabled = true
                  and model.enabled = true
                """)
                .param("profileId", binding.providerProfileId().value())
                .param("modelName", binding.modelName())
                .query((resultSet, rowNumber) -> new AiRuntimeBinding(
                        AiProtocol.valueOf(resultSet.getString("protocol")),
                        io.omnnu.finbot.domain.configuration.ReasoningEffort.valueOf(
                                resultSet.getString("maximum_reasoning_effort")),
                        java.time.Duration.ofSeconds(resultSet.getInt("acquire_timeout_seconds"))))
                .optional()
                .orElseThrow(() -> new AiProviderConfigurationException(
                        "AI provider or model profile does not exist or is disabled"));
        if (!resolved.maximumReasoningEffort().supports(binding.reasoningEffort())) {
            throw new AiProviderConfigurationException(
                    "Requested reasoning effort exceeds the configured model capability");
        }
        return resolved;
    }

    private String environment(String name, String label) {
        var value = name == null ? null : environment.resolve(name).orElse(null);
        if (value == null || value.isBlank()) {
            throw new AiProviderConfigurationException(label + " is not configured");
        }
        return value.strip();
    }

    private static java.net.URI parseAndValidateUri(String value) {
        try {
            return PublicAiProviderEndpointPolicy.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new AiProviderConfigurationException(exception.getMessage());
        }
    }

    private record StoredProfile(
            AiProtocol protocol,
            ReasoningParameterStyle reasoningParameterStyle,
            String baseUrl,
            String baseUrlEnv,
            String apiKeyEnv,
            boolean enabled,
            int requestTimeoutSeconds,
            int maximumConcurrentRequests,
            int acquireTimeoutSeconds,
            long configurationVersion,
            TokenLimitParameterStyle tokenLimitParameterStyle) {
    }
}
