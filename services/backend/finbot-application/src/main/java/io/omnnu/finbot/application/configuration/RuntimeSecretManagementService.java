package io.omnnu.finbot.application.configuration;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RuntimeSecretManagementService implements RuntimeSecretManagementUseCase {
    private static final Map<RuntimeSecretScope, Set<String>> ALLOWED_SECRET_NAMES = Map.of(
            RuntimeSecretScope.AI_PROVIDER, Set.of("API_KEY"),
            RuntimeSecretScope.EXCHANGE_ACCOUNT, Set.of("API_KEY", "API_SECRET"),
            RuntimeSecretScope.PROXY_ROUTE, Set.of("PROXY_URL"),
            RuntimeSecretScope.PROXY_GATEWAY, Set.of("SUBSCRIPTION_URL", "INLINE_NODES"),
            RuntimeSecretScope.INFORMATION_SOURCE, Set.of("API_KEY"));

    private final RuntimeSecretStore store;
    private final RuntimeSecretTargetRepository targets;
    private final Clock clock;

    public RuntimeSecretManagementService(
            RuntimeSecretStore store,
            RuntimeSecretTargetRepository targets,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public RuntimeSecretStatus put(UpdateRuntimeSecretCommand command) {
        Objects.requireNonNull(command, "command");
        var targetId = normalizeTarget(command.targetId());
        var secretName = normalizeName(command.secretName());
        var target = target(command.scope(), targetId, secretName);
        var value = validateValue(command.secretName(), command.value());
        return store.put(
                        command.scope(),
                        targetId,
                        secretName,
                        value,
                        target.fallbackEnvironmentVariable(),
                        command.expectedVersion(),
                        clock.instant())
                .orElseThrow(() -> new ConfigurationConflictException(
                        "运行时凭据已被其他请求修改，请刷新后重试"));
    }

    @Override
    public RuntimeSecretStatus clear(ClearRuntimeSecretCommand command) {
        Objects.requireNonNull(command, "command");
        var targetId = normalizeTarget(command.targetId());
        var secretName = normalizeName(command.secretName());
        var target = target(command.scope(), targetId, secretName);
        return store.clear(
                        command.scope(),
                        targetId,
                        secretName,
                        target.fallbackEnvironmentVariable(),
                        command.expectedVersion(),
                        clock.instant())
                .orElseThrow(() -> new ConfigurationConflictException(
                        "运行时凭据已被其他请求修改，请刷新后重试"));
    }

    private RuntimeSecretTarget target(RuntimeSecretScope scope, String targetId, String secretName) {
        var allowed = ALLOWED_SECRET_NAMES.get(Objects.requireNonNull(scope, "scope"));
        var normalizedSecretName = normalizeName(secretName);
        if (allowed == null || !allowed.contains(normalizedSecretName)) {
            throw new IllegalArgumentException("Secret is not allowed for scope " + scope);
        }
        return targets.find(scope, normalizeTarget(targetId), normalizedSecretName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Runtime secret target does not exist"));
    }

    private static String validateValue(String secretName, String value) {
        var normalized = Objects.requireNonNull(value, "value").strip();
        if (normalized.length() < 8 || normalized.length() > 16_384) {
            throw new IllegalArgumentException("Runtime secret value length is invalid");
        }
        if ("PROXY_URL".equals(normalizeName(secretName))) {
            var uri = URI.create(normalized);
            if (uri.getHost() == null || uri.getFragment() != null
                    || !("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("Proxy URL must use HTTP or HTTPS");
            }
        }
        return normalized;
    }

    private static String normalizeName(String value) {
        return Objects.requireNonNull(value, "secretName").strip().toUpperCase();
    }

    private static String normalizeTarget(String value) {
        var normalized = Objects.requireNonNull(value, "targetId").strip();
        if (normalized.isEmpty() || normalized.length() > 80) {
            throw new IllegalArgumentException("targetId is invalid");
        }
        return normalized;
    }
}
