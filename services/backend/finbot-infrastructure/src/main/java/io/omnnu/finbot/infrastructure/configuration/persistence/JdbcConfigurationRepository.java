package io.omnnu.finbot.infrastructure.configuration.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.configuration.dto.AiModelProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderProfile;
import io.omnnu.finbot.application.configuration.port.out.ConfigurationRepository;
import io.omnnu.finbot.application.configuration.dto.SystemSetting;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.SettingSource;
import io.omnnu.finbot.domain.configuration.SettingType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
                       enabled, connect_timeout_seconds, request_timeout_seconds,
                       maximum_concurrent_requests, acquire_timeout_seconds, version, updated_at
                from ai_provider_profile
                where deleted_at is null
                order by display_name, profile_id
                """)
                .query((resultSet, rowNumber) -> provider(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiModelProfile> listModels() {
        return jdbcClient.sql("""
                select model.model_profile_id, model.provider_profile_id, model.model_name,
                       model.default_reasoning_effort, model.maximum_reasoning_effort,
                       model.input_usd_per_million, model.output_usd_per_million,
                       model.enabled, model.version, model.updated_at
                from ai_model_profile model
                join ai_provider_profile provider
                  on provider.profile_id = model.provider_profile_id
                 and provider.deleted_at is null
                order by model.provider_profile_id, model.model_name
                """)
                .query((resultSet, rowNumber) -> model(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, io.omnnu.finbot.application.configuration.dto.AiProviderUsage> providerUsages() {
        return jdbcClient.sql("""
                select provider.profile_id,
                       (select count(*)
                        from workflow_node_definition node
                        join workflow_definition_version version
                          on version.version_id = node.version_id
                        where version.status in ('DRAFT', 'PUBLISHED')
                          and (node.provider_profile_id = provider.profile_id
                            or node.fallback_provider_profile_id = provider.profile_id)) as workflow_nodes,
                       (select count(*)
                        from agent_role_template role
                        where role.default_provider_profile_id = provider.profile_id) as role_templates,
                       (select count(*)
                        from trade_execution_ai_stage stage
                        where stage.provider_profile_id = provider.profile_id
                           or stage.fallback_provider_profile_id = provider.profile_id) as execution_stages
                from ai_provider_profile provider
                where provider.deleted_at is null
                """)
                .query((resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("profile_id"),
                        new io.omnnu.finbot.application.configuration.dto.AiProviderUsage(
                                resultSet.getLong("workflow_nodes"),
                                resultSet.getLong("role_templates"),
                                resultSet.getLong("execution_stages"))))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
    }

    @Override
    @Transactional
    public Optional<AiProviderProfile> createProvider(AiProviderProfile provider, Instant createdAt) {
        var changed = jdbcClient.sql("""
                insert into ai_provider_profile (
                  profile_id, display_name, protocol, reasoning_parameter_style,
                  base_url, base_url_env, api_key_env, enabled,
                  connect_timeout_seconds, request_timeout_seconds,
                  maximum_concurrent_requests, acquire_timeout_seconds,
                  version, created_at, updated_at
                ) values (
                  :profileId, :displayName, :protocol, :reasoningStyle,
                  :baseUrl, :baseUrlEnv, :apiKeyEnv, :enabled,
                  :connectTimeout, :requestTimeout,
                  :maximumConcurrentRequests, :acquireTimeoutSeconds,
                  0, :createdAt, :createdAt
                ) on conflict (profile_id) do nothing
                """)
                .param("profileId", provider.profileId())
                .param("displayName", provider.displayName())
                .param("protocol", provider.protocol().name())
                .param("reasoningStyle", provider.reasoningParameterStyle().name())
                .param("baseUrl", provider.baseUrl())
                .param("baseUrlEnv", provider.baseUrlEnv())
                .param("apiKeyEnv", provider.apiKeyEnv())
                .param("enabled", provider.enabled())
                .param("connectTimeout", provider.connectTimeoutSeconds())
                .param("requestTimeout", provider.requestTimeoutSeconds())
                .param("maximumConcurrentRequests", provider.maximumConcurrentRequests())
                .param("acquireTimeoutSeconds", provider.acquireTimeoutSeconds())
                .param("createdAt", timestamp(createdAt))
                .update();
        if (changed != 1) {
            return Optional.empty();
        }
        return findProvider(provider.profileId());
    }

    @Override
    @Transactional
    public Optional<AiModelProfile> createModel(AiModelProfile model, Instant createdAt) {
        return insertModel(model, createdAt) == 1
                ? findModel(model.modelProfileId())
                : Optional.empty();
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
                    maximum_concurrent_requests = :maximumConcurrentRequests,
                    acquire_timeout_seconds = :acquireTimeoutSeconds,
                    version = version + 1,
                    updated_at = :updatedAt
                where profile_id = :profileId
                  and version = :expectedVersion
                  and deleted_at is null
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
                .param("maximumConcurrentRequests", profile.maximumConcurrentRequests())
                .param("acquireTimeoutSeconds", profile.acquireTimeoutSeconds())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? findProvider(profile.profileId()) : Optional.empty();
    }

    @Override
    @Transactional
    public boolean archiveProvider(String profileId, long expectedVersion, Instant archivedAt) {
        var changed = jdbcClient.sql("""
                update ai_provider_profile provider
                set enabled = false,
                    deleted_at = :archivedAt,
                    version = version + 1,
                    updated_at = :archivedAt
                where provider.profile_id = :profileId
                  and provider.version = :expectedVersion
                  and provider.deleted_at is null
                  and not exists (
                    select 1
                    from workflow_node_definition node
                    join workflow_definition_version version
                      on version.version_id = node.version_id
                    where version.status in ('DRAFT', 'PUBLISHED')
                      and (node.provider_profile_id = provider.profile_id
                        or node.fallback_provider_profile_id = provider.profile_id)
                  )
                  and not exists (
                    select 1 from agent_role_template role
                    where role.default_provider_profile_id = provider.profile_id
                  )
                  and not exists (
                    select 1 from trade_execution_ai_stage stage
                    where stage.provider_profile_id = provider.profile_id
                       or stage.fallback_provider_profile_id = provider.profile_id
                  )
                """)
                .param("profileId", profileId)
                .param("expectedVersion", expectedVersion)
                .param("archivedAt", timestamp(archivedAt))
                .update();
        if (changed != 1) {
            return false;
        }
        jdbcClient.sql("""
                update ai_model_profile
                set enabled = false,
                    version = version + 1,
                    updated_at = :archivedAt
                where provider_profile_id = :profileId and enabled = true
                """)
                .param("profileId", profileId)
                .param("archivedAt", timestamp(archivedAt))
                .update();
        jdbcClient.sql("""
                delete from runtime_secret_override
                where scope_type = 'AI_PROVIDER' and target_id = :profileId
                """)
                .param("profileId", profileId)
                .update();
        return true;
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
                       enabled, connect_timeout_seconds, request_timeout_seconds,
                       maximum_concurrent_requests, acquire_timeout_seconds, version, updated_at
                from ai_provider_profile
                where profile_id = :profileId and deleted_at is null
                """)
                .param("profileId", profileId)
                .query((resultSet, rowNumber) -> provider(resultSet))
                .optional();
    }

    private Optional<AiModelProfile> findModel(String modelProfileId) {
        return jdbcClient.sql("""
                select model.model_profile_id, model.provider_profile_id, model.model_name,
                       model.default_reasoning_effort, model.maximum_reasoning_effort,
                       model.input_usd_per_million, model.output_usd_per_million,
                       model.enabled, model.version, model.updated_at
                from ai_model_profile model
                join ai_provider_profile provider
                  on provider.profile_id = model.provider_profile_id
                 and provider.deleted_at is null
                where model.model_profile_id = :modelProfileId
                """)
                .param("modelProfileId", modelProfileId)
                .query((resultSet, rowNumber) -> model(resultSet))
                .optional();
    }

    private int insertModel(AiModelProfile model, Instant createdAt) {
        return jdbcClient.sql("""
                insert into ai_model_profile (
                  model_profile_id, provider_profile_id, model_name,
                  default_reasoning_effort, maximum_reasoning_effort,
                  input_usd_per_million, output_usd_per_million,
                  enabled, version, created_at, updated_at
                ) values (
                  :modelProfileId, :providerProfileId, :modelName,
                  :defaultReasoningEffort, :maximumReasoningEffort,
                  :inputRate, :outputRate, :enabled, 0, :createdAt, :createdAt
                ) on conflict (provider_profile_id, model_name) do nothing
                """)
                .param("modelProfileId", model.modelProfileId())
                .param("providerProfileId", model.providerProfileId())
                .param("modelName", model.modelName())
                .param("defaultReasoningEffort", model.defaultReasoningEffort().name())
                .param("maximumReasoningEffort", model.maximumReasoningEffort().name())
                .param("inputRate", model.inputUsdPerMillion())
                .param("outputRate", model.outputUsdPerMillion())
                .param("enabled", model.enabled())
                .param("createdAt", timestamp(createdAt))
                .update();
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
                resultSet.getInt("maximum_concurrent_requests"),
                resultSet.getInt("acquire_timeout_seconds"),
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
