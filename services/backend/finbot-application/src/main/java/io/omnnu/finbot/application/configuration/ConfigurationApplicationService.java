package io.omnnu.finbot.application.configuration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public final class ConfigurationApplicationService implements ConfigurationUseCase {
    private final ConfigurationRepository repository;
    private final EnvironmentValueResolver environment;
    private final Clock clock;

    public ConfigurationApplicationService(
            ConfigurationRepository repository,
            EnvironmentValueResolver environment,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ConfigurationSnapshot snapshot() {
        return new ConfigurationSnapshot(
                repository.listSettings(),
                repository.listProviders().stream().map(this::toView).toList(),
                repository.listModels());
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
        var profile = new AiProviderProfile(
                command.profileId(),
                command.displayName(),
                command.protocol(),
                command.reasoningParameterStyle(),
                command.baseUrl(),
                command.baseUrlEnv(),
                command.apiKeyEnv(),
                command.enabled(),
                command.connectTimeoutSeconds(),
                command.requestTimeoutSeconds(),
                command.expectedVersion() + 1,
                clock.instant());
        return repository.updateProvider(profile, command.expectedVersion(), clock.instant())
                .map(this::toView)
                .orElseThrow(() -> new ConfigurationConflictException(
                        "AI 厂商配置不存在或版本冲突，请刷新后重试"));
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

    private AiProviderView toView(AiProviderProfile profile) {
        var resolvedBaseUrl = profile.baseUrl() == null
                ? environment.resolve(profile.baseUrlEnv()).orElse(null)
                : profile.baseUrl();
        var apiKeyConfigured = environment.resolve(profile.apiKeyEnv())
                .filter(value -> !value.isBlank())
                .isPresent();
        return new AiProviderView(
                profile.profileId(),
                profile.displayName(),
                profile.protocol(),
                profile.reasoningParameterStyle(),
                resolvedBaseUrl,
                profile.baseUrlEnv(),
                profile.apiKeyEnv(),
                resolvedBaseUrl != null && !resolvedBaseUrl.isBlank(),
                apiKeyConfigured,
                profile.enabled(),
                profile.connectTimeoutSeconds(),
                profile.requestTimeoutSeconds(),
                profile.version(),
                profile.updatedAt());
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
