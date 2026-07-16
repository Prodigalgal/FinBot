package io.omnnu.finbot.application.network;

import io.omnnu.finbot.application.configuration.ConfigurationConflictException;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ProxyGatewayControlService implements ProxyGatewayControlUseCase {
    private final ProxyGatewayProfileRepository profiles;
    private final RuntimeSecretStore runtimeSecrets;
    private final ProxyGatewayControlGateway gateway;
    private final Clock clock;

    public ProxyGatewayControlService(
            ProxyGatewayProfileRepository profiles,
            RuntimeSecretStore runtimeSecrets,
            ProxyGatewayControlGateway gateway,
            Clock clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<ProxyGatewayReloadResult> reload(String gatewayId) {
        var profile = profiles.find(Objects.requireNonNull(gatewayId, "gatewayId").strip())
                .filter(ProxyGatewayProfile::enabled)
                .orElseThrow(() -> new IllegalArgumentException("Proxy gateway does not exist or is disabled"));
        var configuration = new ProxyGatewayRuntimeConfiguration(
                secret(profile, "SUBSCRIPTION_URL", profile.subscriptionUrlEnvironment()),
                secret(profile, "INLINE_NODES", profile.inlineNodesEnvironment()),
                profile.preferredNames(),
                profile.maximumNodes(),
                profile.refreshSeconds(),
                profile.allowInsecureTls());
        var requestedAt = clock.instant();
        return gateway.apply(profile, configuration)
                .thenApply(ignored -> new ProxyGatewayReloadResult(
                        profile.gatewayId(), "RELOAD_ACCEPTED", requestedAt));
    }

    @Override
    public CompletionStage<ProxyGatewayRuntimeStatus> status(String gatewayId) {
        var profile = profiles.find(Objects.requireNonNull(gatewayId, "gatewayId").strip())
                .filter(ProxyGatewayProfile::enabled)
                .orElseThrow(() -> new IllegalArgumentException("Proxy gateway does not exist or is disabled"));
        return gateway.status(profile).exceptionally(exception -> unavailableStatus(profile, exception));
    }

    @Override
    public List<CompletionStage<ProxyGatewayReloadResult>> reloadAll() {
        return profiles.list().stream()
                .filter(ProxyGatewayProfile::enabled)
                .map(profile -> reloadSafely(profile.gatewayId()))
                .toList();
    }

    private CompletionStage<ProxyGatewayReloadResult> reloadSafely(String gatewayId) {
        try {
            return reload(gatewayId);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedStage(exception);
        }
    }

    @Override
    public ProxyGatewayProfile update(UpdateProxyGatewayProfileCommand command) {
        Objects.requireNonNull(command, "command");
        var current = profiles.find(command.gatewayId())
                .orElseThrow(() -> new IllegalArgumentException("Proxy gateway does not exist"));
        if (command.maximumNodes() < 1 || command.maximumNodes() > 128) {
            throw new IllegalArgumentException("maximumNodes must be between 1 and 128");
        }
        if (command.refreshSeconds() < 60 || command.refreshSeconds() > 86_400) {
            throw new IllegalArgumentException("refreshSeconds must be between 60 and 86400");
        }
        var preferredNames = command.preferredNames().stream()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .distinct()
                .limit(32)
                .toList();
        var updatedAt = clock.instant();
        var updated = new ProxyGatewayProfile(
                current.gatewayId(),
                current.displayName(),
                current.controlUrl(),
                current.subscriptionUrlEnvironment(),
                current.inlineNodesEnvironment(),
                preferredNames,
                command.maximumNodes(),
                command.refreshSeconds(),
                command.allowInsecureTls(),
                command.enabled(),
                command.expectedVersion() + 1,
                updatedAt);
        return profiles.update(updated, command.expectedVersion(), updatedAt)
                .orElseThrow(() -> new ConfigurationConflictException(
                        "代理网关配置已被其他请求修改，请刷新后重试"));
    }

    private String secret(ProxyGatewayProfile profile, String secretName, String environmentVariable) {
        return runtimeSecrets.resolve(
                        RuntimeSecretScope.PROXY_GATEWAY,
                        profile.gatewayId(),
                        secretName,
                        environmentVariable)
                .orElse(null);
    }

    private static ProxyGatewayRuntimeStatus unavailableStatus(
            ProxyGatewayProfile profile,
            Throwable exception) {
        var cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return new ProxyGatewayRuntimeStatus(
                profile.gatewayId(),
                false,
                false,
                0,
                0,
                0,
                List.of(),
                java.util.Map.of(),
                false,
                null,
                0,
                0,
                null,
                "Proxy gateway status unavailable: " + cause.getClass().getSimpleName());
    }
}
