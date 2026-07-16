package io.omnnu.finbot.application.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.configuration.ConfigurationConflictException;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretSource;
import io.omnnu.finbot.application.configuration.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ProxyGatewayControlServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");

    @Test
    void reloadCombinesHotSecretAndEnvironmentFallback() {
        var profile = profile(true, 3);
        var repository = new FakeRepository(profile, true);
        var gateway = new CapturingGateway();
        RuntimeSecretStore secrets = new EmptyMutatingStore() {
            @Override
            public Optional<String> resolve(
                    RuntimeSecretScope scope,
                    String targetId,
                    String secretName,
                    String fallbackEnvironmentVariable) {
                return Optional.of("SUBSCRIPTION_URL".equals(secretName)
                        ? "https://hot.example/subscription"
                        : "hysteria2://fallback-node.example:443");
            }
        };
        var service = service(repository, secrets, gateway);

        var result = service.reload(profile.gatewayId()).toCompletableFuture().join();

        assertEquals("RELOAD_ACCEPTED", result.status());
        assertEquals("https://hot.example/subscription", gateway.configuration.subscriptionUrl());
        assertEquals("hysteria2://fallback-node.example:443", gateway.configuration.inlineNodes());
        assertEquals(List.of("JP", "SG"), gateway.configuration.preferredNames());
    }

    @Test
    void rejectsReloadForDisabledGatewayAndStaleMetadataUpdate() {
        var disabled = profile(false, 3);
        var service = service(
                new FakeRepository(disabled, false),
                new EmptyMutatingStore(),
                new CapturingGateway());

        assertThrows(IllegalArgumentException.class, () -> service.reload(disabled.gatewayId()));
        assertThrows(ConfigurationConflictException.class, () -> service.update(
                new UpdateProxyGatewayProfileCommand(
                        disabled.gatewayId(),
                        List.of(" JP ", "JP"),
                        8,
                        300,
                        false,
                        true,
                        2)));
    }

    @Test
    void normalizesMetadataAndAdvancesOptimisticVersion() {
        var repository = new FakeRepository(profile(true, 3), true);
        var service = service(repository, new EmptyMutatingStore(), new CapturingGateway());

        var updated = service.update(new UpdateProxyGatewayProfileCommand(
                repository.profile.gatewayId(),
                List.of(" JP ", "JP", "SG"),
                8,
                300,
                false,
                false,
                3));

        assertEquals(List.of("JP", "SG"), updated.preferredNames());
        assertEquals(4, updated.version());
        assertFalse(updated.enabled());
    }

    @Test
    void reloadAllContainsMisconfiguredGatewayFailure() {
        var service = service(
                new FakeRepository(profile(true, 3), true),
                new EmptyMutatingStore(),
                new CapturingGateway());

        var reloads = service.reloadAll();

        assertEquals(1, reloads.size());
        assertTrue(reloads.getFirst().toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void exposesLiveGatewayStatusWithoutReturningNodeSecrets() {
        var profile = profile(true, 3);
        var gateway = new CapturingGateway();
        var service = service(new FakeRepository(profile, true), new EmptyMutatingStore(), gateway);

        var status = service.status(profile.gatewayId()).toCompletableFuture().join();

        assertTrue(status.serviceReady());
        assertFalse(status.egressReady());
        assertEquals(4, status.nodeCount());
        assertEquals(Map.of("CONNECTION_ERROR", 4), status.probeFailureCounts());
    }

    private static ProxyGatewayControlService service(
            ProxyGatewayProfileRepository repository,
            RuntimeSecretStore secrets,
            ProxyGatewayControlGateway gateway) {
        return new ProxyGatewayControlService(
                repository,
                secrets,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProxyGatewayProfile profile(boolean enabled, long version) {
        return new ProxyGatewayProfile(
                "proxygateway_exchange",
                "交易所代理池",
                URI.create("http://finbot-exchange-proxy:8081"),
                "SUBSCRIPTION_ENV",
                "INLINE_NODES_ENV",
                List.of("JP", "SG"),
                16,
                1800,
                false,
                enabled,
                version,
                NOW);
    }

    private static final class FakeRepository implements ProxyGatewayProfileRepository {
        private ProxyGatewayProfile profile;
        private final boolean updateSucceeds;

        private FakeRepository(ProxyGatewayProfile profile, boolean updateSucceeds) {
            this.profile = profile;
            this.updateSucceeds = updateSucceeds;
        }

        @Override
        public List<ProxyGatewayProfile> list() {
            return List.of(profile);
        }

        @Override
        public Optional<ProxyGatewayProfile> find(String gatewayId) {
            return profile.gatewayId().equals(gatewayId) ? Optional.of(profile) : Optional.empty();
        }

        @Override
        public Optional<ProxyGatewayProfile> update(
                ProxyGatewayProfile updated,
                long expectedVersion,
                Instant updatedAt) {
            if (!updateSucceeds || profile.version() != expectedVersion) {
                return Optional.empty();
            }
            profile = updated;
            return Optional.of(updated);
        }
    }

    private static final class CapturingGateway implements ProxyGatewayControlGateway {
        private ProxyGatewayRuntimeConfiguration configuration;

        @Override
        public java.util.concurrent.CompletionStage<Void> apply(
                ProxyGatewayProfile profile,
                ProxyGatewayRuntimeConfiguration configuration) {
            this.configuration = configuration;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<ProxyGatewayRuntimeStatus> status(
                ProxyGatewayProfile profile) {
            return CompletableFuture.completedFuture(new ProxyGatewayRuntimeStatus(
                    profile.gatewayId(),
                    true,
                    false,
                    4,
                    0,
                    4,
                    List.of(),
                    Map.of("CONNECTION_ERROR", 4),
                    true,
                    "api.example.com",
                    2,
                    3,
                    NOW,
                    "Proxy target validation found no healthy nodes"));
        }
    }

    private static class EmptyMutatingStore implements RuntimeSecretStore {
        @Override
        public Optional<String> resolve(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return Optional.empty();
        }

        @Override
        public RuntimeSecretStatus status(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return new RuntimeSecretStatus(
                    scope,
                    targetId,
                    secretName,
                    RuntimeSecretSource.UNCONFIGURED,
                    false,
                    null,
                    0,
                    null);
        }

        @Override
        public Optional<RuntimeSecretStatus> put(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String value,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }

        @Override
        public Optional<RuntimeSecretStatus> clear(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }
    }
}
