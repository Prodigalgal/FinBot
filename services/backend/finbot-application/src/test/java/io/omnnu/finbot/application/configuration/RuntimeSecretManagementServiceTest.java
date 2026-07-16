package io.omnnu.finbot.application.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeSecretManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T08:00:00Z");

    @Test
    void storesNormalizedProviderCredentialAgainstServerOwnedFallback() {
        var store = new CapturingStore(true);
        var service = service(store);

        var status = service.put(new UpdateRuntimeSecretCommand(
                RuntimeSecretScope.AI_PROVIDER,
                " provider_primary ",
                " api_key ",
                "  secret-value  ",
                0));

        assertEquals("provider_primary", store.targetId);
        assertEquals("API_KEY", store.secretName);
        assertEquals("secret-value", store.value);
        assertEquals("PROVIDER_API_KEY_ENV", store.fallbackEnvironmentVariable);
        assertEquals(RuntimeSecretSource.DATABASE_OVERRIDE, status.source());
    }

    @Test
    void rejectsUnsupportedScopeSecretAndUnsafeProxyUrl() {
        var service = service(new CapturingStore(true));

        assertThrows(IllegalArgumentException.class, () -> service.put(
                new UpdateRuntimeSecretCommand(
                        RuntimeSecretScope.AI_PROVIDER,
                        "provider_primary",
                        "API_SECRET",
                        "secret-value",
                        0)));
        assertThrows(IllegalArgumentException.class, () -> service.put(
                new UpdateRuntimeSecretCommand(
                        RuntimeSecretScope.PROXY_ROUTE,
                        "BYBIT",
                        "PROXY_URL",
                        "socks5://127.0.0.1:1080",
                        0)));
    }

    @Test
    void reportsOptimisticConflictWithoutOverwritingNewerSecret() {
        var service = service(new CapturingStore(false));

        assertThrows(ConfigurationConflictException.class, () -> service.clear(
                new ClearRuntimeSecretCommand(
                        RuntimeSecretScope.AI_PROVIDER,
                        "provider_primary",
                        "API_KEY",
                        4)));
    }

    private static RuntimeSecretManagementService service(RuntimeSecretStore store) {
        RuntimeSecretTargetRepository targets = (scope, targetId, secretName) ->
                "provider_primary".equals(targetId) || "BYBIT".equals(targetId)
                        ? Optional.of(new RuntimeSecretTarget("PROVIDER_API_KEY_ENV"))
                        : Optional.empty();
        return new RuntimeSecretManagementService(
                store,
                targets,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class CapturingStore implements RuntimeSecretStore {
        private final boolean mutationSucceeds;
        private String targetId;
        private String secretName;
        private String value;
        private String fallbackEnvironmentVariable;

        private CapturingStore(boolean mutationSucceeds) {
            this.mutationSucceeds = mutationSucceeds;
        }

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
            return status(scope, targetId, secretName, 0);
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
            this.targetId = targetId;
            this.secretName = secretName;
            this.value = value;
            this.fallbackEnvironmentVariable = fallbackEnvironmentVariable;
            return mutationSucceeds
                    ? Optional.of(status(scope, targetId, secretName, expectedVersion + 1))
                    : Optional.empty();
        }

        @Override
        public Optional<RuntimeSecretStatus> clear(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            return mutationSucceeds
                    ? Optional.of(status(scope, targetId, secretName, expectedVersion + 1))
                    : Optional.empty();
        }

        private static RuntimeSecretStatus status(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                long version) {
            return new RuntimeSecretStatus(
                    scope,
                    targetId,
                    secretName,
                    RuntimeSecretSource.DATABASE_OVERRIDE,
                    true,
                    "0123456789abcdef",
                    version,
                    NOW);
        }
    }
}
