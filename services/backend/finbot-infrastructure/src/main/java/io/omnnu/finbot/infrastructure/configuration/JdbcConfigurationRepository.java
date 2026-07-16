package io.omnnu.finbot.infrastructure.configuration;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.configuration.AiModelProfile;
import io.omnnu.finbot.application.configuration.AiProviderProfile;
import io.omnnu.finbot.application.configuration.ConfigurationRepository;
import io.omnnu.finbot.application.configuration.SystemSetting;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.SettingSource;
import io.omnnu.finbot.domain.configuration.SettingType;
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
public class JdbcConfigurationRepository implements ConfigurationRepository {
    private final JdbcClient jdbcClient;

    public JdbcConfigurationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<SystemSetting> listSettings() {
        return jdbcClient.sql("""
                select setting_key, setting_type, value_text, source, description, version, updated_at
                from system_setting
                order by setting_key
                """)
                .query((resultSet, rowNumber) -> setting(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiProviderProfile> listProviders() {
        return jdbcClient.sql("""
                select profile_id, display_name, protocol, reasoning_parameter_style,
                       base_url, base_url_env, api_key_env,
                       enabled, connect_timeout_seconds, request_timeout_seconds, version, updated_at
                from ai_provider_profile
                order by display_name, profile_id
                """)
                .query((resultSet, rowNumber) -> provider(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiModelProfile> listModels() {
        return jdbcClient.sql("""
                select model_profile_id, provider_profile_id, model_name, default_reasoning_effort,
                       maximum_reasoning_effort,
                       input_usd_per_million, output_usd_per_million, enabled, version, updated_at
                from ai_model_profile
                order by provider_profile_id, model_name
                """)
                .query((resultSet, rowNumber) -> model(resultSet))
                .list();
    }

    @Override
    @Transactional
    public Optional<SystemSetting> updateSetting(
            String key,
            String value,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                update system_setting
                set value_text = :value,
                    source = 'USER',
                    version = version + 1,
                    updated_at = :updatedAt
                where setting_key = :key and version = :expectedVersion
                """)
                .param("key", key)
                .param("value", value)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? findSetting(key) : Optional.empty();
    }

    @Override
    @Transactional
    public Optional<AiProviderProfile> updateProvider(
            AiProviderProfile profile,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                update ai_provider_profile
                set display_name = :displayName,
                    protocol = :protocol,
                    reasoning_parameter_style = :reasoningStyle,
                    base_url = :baseUrl,
                    base_url_env = :baseUrlEnv,
                    api_key_env = :apiKeyEnv,
                    enabled = :enabled,
                    connect_timeout_seconds = :connectTimeout,
                    request_timeout_seconds = :requestTimeout,
                    version = version + 1,
                    updated_at = :updatedAt
                where profile_id = :profileId and version = :expectedVersion
                """)
                .param("profileId", profile.profileId())
                .param("displayName", profile.displayName())
                .param("protocol", profile.protocol().name())
                .param("reasoningStyle", profile.reasoningParameterStyle().name())
                .param("baseUrl", profile.baseUrl())
                .param("baseUrlEnv", profile.baseUrlEnv())
                .param("apiKeyEnv", profile.apiKeyEnv())
                .param("enabled", profile.enabled())
                .param("connectTimeout", profile.connectTimeoutSeconds())
                .param("requestTimeout", profile.requestTimeoutSeconds())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? findProvider(profile.profileId()) : Optional.empty();
    }

    @Override
    @Transactional
    public Optional<AiModelProfile> updateModel(
            AiModelProfile profile,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                update ai_model_profile
                set default_reasoning_effort = :reasoningEffort,
                    maximum_reasoning_effort = :maximumReasoningEffort,
                    input_usd_per_million = :inputRate,
                    output_usd_per_million = :outputRate,
                    enabled = :enabled,
                    version = version + 1,
                    updated_at = :updatedAt
                where model_profile_id = :modelProfileId and version = :expectedVersion
                """)
                .param("modelProfileId", profile.modelProfileId())
                .param("reasoningEffort", profile.defaultReasoningEffort().name())
                .param("maximumReasoningEffort", profile.maximumReasoningEffort().name())
                .param("inputRate", profile.inputUsdPerMillion())
                .param("outputRate", profile.outputUsdPerMillion())
                .param("enabled", profile.enabled())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? findModel(profile.modelProfileId()) : Optional.empty();
    }

    private Optional<SystemSetting> findSetting(String key) {
        return jdbcClient.sql("""
                select setting_key, setting_type, value_text, source, description, version, updated_at
                from system_setting where setting_key = :key
                """)
                .param("key", key)
                .query((resultSet, rowNumber) -> setting(resultSet))
                .optional();
    }

    private Optional<AiProviderProfile> findProvider(String profileId) {
        return jdbcClient.sql("""
                select profile_id, display_name, protocol, reasoning_parameter_style,
                       base_url, base_url_env, api_key_env,
                       enabled, connect_timeout_seconds, request_timeout_seconds, version, updated_at
                from ai_provider_profile where profile_id = :profileId
                """)
                .param("profileId", profileId)
                .query((resultSet, rowNumber) -> provider(resultSet))
                .optional();
    }

    private Optional<AiModelProfile> findModel(String modelProfileId) {
        return jdbcClient.sql("""
                select model_profile_id, provider_profile_id, model_name, default_reasoning_effort,
                       maximum_reasoning_effort,
                       input_usd_per_million, output_usd_per_million, enabled, version, updated_at
                from ai_model_profile where model_profile_id = :modelProfileId
                """)
                .param("modelProfileId", modelProfileId)
                .query((resultSet, rowNumber) -> model(resultSet))
                .optional();
    }

    private static SystemSetting setting(ResultSet resultSet) throws SQLException {
        return new SystemSetting(
                resultSet.getString("setting_key"),
                SettingType.valueOf(resultSet.getString("setting_type")),
                resultSet.getString("value_text"),
                SettingSource.valueOf(resultSet.getString("source")),
                resultSet.getString("description"),
                resultSet.getLong("version"),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)));
    }

    private static AiProviderProfile provider(ResultSet resultSet) throws SQLException {
        return new AiProviderProfile(
                resultSet.getString("profile_id"),
                resultSet.getString("display_name"),
                AiProtocol.valueOf(resultSet.getString("protocol")),
                io.omnnu.finbot.domain.configuration.ReasoningParameterStyle.valueOf(
                        resultSet.getString("reasoning_parameter_style")),
                resultSet.getString("base_url"),
                resultSet.getString("base_url_env"),
                resultSet.getString("api_key_env"),
                resultSet.getBoolean("enabled"),
                resultSet.getInt("connect_timeout_seconds"),
                resultSet.getInt("request_timeout_seconds"),
                resultSet.getLong("version"),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)));
    }

    private static AiModelProfile model(ResultSet resultSet) throws SQLException {
        return new AiModelProfile(
                resultSet.getString("model_profile_id"),
                resultSet.getString("provider_profile_id"),
                resultSet.getString("model_name"),
                ReasoningEffort.valueOf(resultSet.getString("default_reasoning_effort")),
                ReasoningEffort.valueOf(resultSet.getString("maximum_reasoning_effort")),
                resultSet.getBigDecimal("input_usd_per_million"),
                resultSet.getBigDecimal("output_usd_per_million"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("version"),
                instant(resultSet.getObject("updated_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }
}
