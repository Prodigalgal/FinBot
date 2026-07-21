package io.omnnu.finbot.application.configuration;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public final class ConfigurationApplicationService implements ConfigurationUseCase {
    private static final String AI_PROVIDER_KEYS_ENVIRONMENT = "FINBOT_AI_PROVIDER_KEYS_JSON";

    private final ConfigurationRepository repository;
    private final EnvironmentValueResolver environment;
    private final RuntimeSecretStore runtimeSecrets;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public ConfigurationApplicationService(
            ConfigurationRepository repository,
            EnvironmentValueResolver environment,
            RuntimeSecretStore runtimeSecrets,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ConfigurationSnapshot snapshot() {
        var usages = repository.providerUsages();
        return new ConfigurationSnapshot(
                repository.listSettings(),
                repository.listProviders().stream()
                        .map(provider -> toView(
                                provider,
                                usages.getOrDefault(provider.profileId(), AiProviderUsage.NONE)))
                        .toList(),
                repository.listModels());
    }

    @Override
    public AiProviderView createProvider(CreateProviderCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        var providerId = idGenerator.next("provider_");
        var provider = new AiProviderProfile(
                providerId,
                command.displayName(),
                command.protocol(),
                command.reasoningParameterStyle(),
                validateBaseUrl(command.baseUrl()),
                null,
                AI_PROVIDER_KEYS_ENVIRONMENT,
                command.enabled(),
                command.connectTimeoutSeconds(),
                command.requestTimeoutSeconds(),
                command.maximumConcurrentRequests(),
                command.acquireTimeoutSeconds(),
                0,
                now);
        return repository.createProvider(provider, now)
                .map(created -> toView(created, AiProviderUsage.NONE))
                .orElseThrow(() -> new ConfigurationConflictException(
                        "AI 厂商已存在，请刷新后重试"));
    }

    @Override
    public AiModelProfile createModel(CreateModelCommand command) {
        Objects.requireNonNull(command, "command");
        var providerExists = repository.listProviders().stream()
                .anyMatch(provider -> provider.profileId().equals(command.providerProfileId()));
        if (!providerExists) {
            throw new IllegalArgumentException("AI provider does not exist");
        }
        var now = clock.instant();
        var model = new AiModelProfile(
                idGenerator.next("model_"),
                command.providerProfileId(),
                command.modelName(),
                command.defaultReasoningEffort(),
                command.maximumReasoningEffort(),
                command.inputUsdPerMillion(),
                command.outputUsdPerMillion(),
                command.enabled(),
                0,
                now);
        return repository.createModel(model, now)
                .orElseThrow(() -> new ConfigurationConflictException(
                        "该厂商下已存在同名模型，请刷新后重试"));
    }

    @Override
    public SystemSetting updateSetting(UpdateSettingCommand command) {
        Objects.requireNonNull(command, "command");
        var current = repository.listSettings().stream()
                .filter(setting -> setting.key().equals(command.key()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown setting: " + command.key()));
        var normalizedValue = validateSettingValue(current, command.value());
        enforceSafetyInvariant(current.key(), normalizedValue);
        return repository.updateSetting(
                        current.key(),
                        normalizedValue,
                        command.expectedVersion(),
                        clock.instant())
                .orElseThrow(() -> new ConfigurationConflictException(
                        "配置已被其他请求修改，请刷新后重试"));
    }

    @Override
    public AiProviderView updateProvider(UpdateProviderCommand command) {
        Objects.requireNonNull(command, "command");
        var current = repository.listProviders().stream()
                .filter(provider -> provider.profileId().equals(command.profileId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI provider profile"));
        var requestedBaseUrl = validateBaseUrl(command.baseUrl());
        var currentResolvedBaseUrl = current.baseUrl() == null
                ? environment.resolve(current.baseUrlEnv()).orElse(null)
                : current.baseUrl();
        var preserveBaseEnvironment = current.baseUrlEnv() != null
                && requestedBaseUrl.equals(currentResolvedBaseUrl);
        var profile = new AiProviderProfile(
                command.profileId(),
                command.displayName(),
                command.protocol(),
                command.reasoningParameterStyle(),
                preserveBaseEnvironment ? null : requestedBaseUrl,
                preserveBaseEnvironment ? current.baseUrlEnv() : null,
                current.apiKeyEnv(),
                command.enabled(),
                command.connectTimeoutSeconds(),
                command.requestTimeoutSeconds(),
                command.maximumConcurrentRequests(),
                command.acquireTimeoutSeconds(),
                command.expectedVersion() + 1,
                clock.instant());
        return repository.updateProvider(profile, command.expectedVersion(), clock.instant())
                .map(updated -> toView(
                        updated,
                        repository.providerUsages().getOrDefault(
                                updated.profileId(), AiProviderUsage.NONE)))
                .orElseThrow(() -> new ConfigurationConflictException(
                        "AI 厂商配置不存在或版本冲突，请刷新后重试"));
    }

    @Override
    public void deleteProvider(DeleteProviderCommand command) {
        Objects.requireNonNull(command, "command");
        var usage = repository.providerUsages().getOrDefault(
                command.profileId(), AiProviderUsage.NONE);
        if (usage.inUse()) {
            throw new ConfigurationConflictException(
                    "AI 厂商正在被工作流节点、角色或执行阶段使用，请先解绑后再删除");
        }
        if (!repository.archiveProvider(
                command.profileId(), command.expectedVersion(), clock.instant())) {
            throw new ConfigurationConflictException(
                    "AI 厂商不存在、版本冲突或刚被其他配置引用，请刷新后重试");
        }
    }

    @Override
    public AiModelProfile updateModel(UpdateModelCommand command) {
        Objects.requireNonNull(command, "command");
        var current = repository.listModels().stream()
                .filter(model -> model.modelProfileId().equals(command.modelProfileId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model profile: " + command.modelProfileId()));
        var updated = new AiModelProfile(
                current.modelProfileId(),
                current.providerProfileId(),
                current.modelName(),
                command.defaultReasoningEffort(),
                command.maximumReasoningEffort(),
                command.inputUsdPerMillion(),
                command.outputUsdPerMillion(),
                command.enabled(),
                command.expectedVersion() + 1,
                clock.instant());
        return repository.updateModel(updated, command.expectedVersion(), clock.instant())
                .orElseThrow(() -> new ConfigurationConflictException(
                        "AI 模型配置不存在或版本冲突，请刷新后重试"));
    }

    private AiProviderView toView(AiProviderProfile profile, AiProviderUsage usage) {
        var resolvedBaseUrl = profile.baseUrl() == null
                ? environment.resolve(profile.baseUrlEnv()).orElse(null)
                : profile.baseUrl();
        var credential = runtimeSecrets.status(
                RuntimeSecretScope.AI_PROVIDER,
                profile.profileId(),
                "API_KEY",
                profile.apiKeyEnv());
        return new AiProviderView(
                profile.profileId(),
                profile.displayName(),
                profile.protocol(),
                profile.reasoningParameterStyle(),
                resolvedBaseUrl,
                resolvedBaseUrl != null && !resolvedBaseUrl.isBlank(),
                credential.configured(),
                credential.source(),
                credential.fingerprint(),
                credential.version(),
                credential.updatedAt(),
                profile.enabled(),
                profile.connectTimeoutSeconds(),
                profile.requestTimeoutSeconds(),
                profile.maximumConcurrentRequests(),
                profile.acquireTimeoutSeconds(),
                usage.workflowNodeCount(),
                usage.roleTemplateCount(),
                usage.executionStageCount(),
                usage.totalCount(),
                profile.version(),
                profile.updatedAt());
    }

    private static String validateBaseUrl(String value) {
        var normalized = Objects.requireNonNull(value, "baseUrl").strip();
        var uri = URI.create(normalized);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme())
                        || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("AI provider base URL is invalid");
        }
        return normalized;
    }

    private static String validateSettingValue(SystemSetting setting, String value) {
        var normalized = Objects.requireNonNull(value, "value").strip();
        try {
            return switch (setting.type()) {
                case BOOLEAN -> parseBoolean(normalized);
                case INTEGER -> Long.toString(Long.parseLong(normalized));
                case DECIMAL -> new BigDecimal(normalized).stripTrailingZeros().toPlainString();
                case DURATION -> Duration.parse(normalized.toUpperCase(Locale.ROOT)).toString();
                case TEXT -> normalized;
            };
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid value for " + setting.key(), exception);
        }
    }

    private static String parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(value)) {
            return "false";
        }
        throw new IllegalArgumentException("boolean value must be true or false");
    }

    private static void enforceSafetyInvariant(String key, String value) {
        if ("execution.live.enabled".equals(key) && "true".equals(value)) {
            throw new IllegalArgumentException("FinBot does not permit live trading");
        }
        if ("ingestion.firecrawl.proxy_required".equals(key) && "false".equals(value)) {
            throw new IllegalArgumentException("Firecrawl direct fallback is prohibited");
        }
    }
}
