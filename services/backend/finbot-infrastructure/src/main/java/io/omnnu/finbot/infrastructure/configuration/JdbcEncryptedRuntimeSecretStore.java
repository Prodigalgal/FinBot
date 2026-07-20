package io.omnnu.finbot.infrastructure.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretSource;
import io.omnnu.finbot.application.configuration.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcEncryptedRuntimeSecretStore implements RuntimeSecretStore {
    private static final long STORED_SECRET_CACHE_TTL_NANOS = java.time.Duration.ofSeconds(3).toNanos();
    private static final String AI_PROVIDER_KEYS_ENV = "FINBOT_AI_PROVIDER_KEYS_JSON";
    private static final String EXCHANGE_ACCOUNT_CREDENTIALS_ENV =
            "FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON";
    private static final String INFORMATION_SOURCE_KEYS_ENV =
            "FINBOT_INFORMATION_SOURCE_KEYS_JSON";
    private static final String PROXY_ROUTE_URLS_ENV = "FINBOT_PROXY_ROUTE_URLS_JSON";
    private static final String PROXY_GATEWAY_SECRETS_ENV =
            "FINBOT_PROXY_GATEWAY_SECRETS_JSON";

    private final JdbcClient jdbcClient;
    private final EnvironmentValueResolver environment;
    private final AesGcmRuntimeSecretCipher cipher;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<RuntimeSecretScope, CachedScope> storedSecretCache = new ConcurrentHashMap<>();

    public JdbcEncryptedRuntimeSecretStore(
            JdbcClient jdbcClient,
            EnvironmentValueResolver environment,
            AesGcmRuntimeSecretCipher cipher,
            ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolve(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable) {
        var stored = find(scope, targetId, secretName);
        if (stored.isPresent() && stored.get().ciphertext() != null) {
            var value = stored.get();
            return Optional.of(cipher.decrypt(
                    scope,
                    targetId,
                    secretName,
                    value.ciphertext(),
                    value.nonce(),
                    value.encryptionKeyVersion()));
        }
        return fallbackValue(scope, targetId, secretName, fallbackEnvironmentVariable);
    }

    @Override
    @Transactional(readOnly = true)
    public RuntimeSecretStatus status(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable) {
        return status(scope, targetId, secretName, fallbackEnvironmentVariable, find(scope, targetId, secretName));
    }

    @Override
    @Transactional
    public Optional<RuntimeSecretStatus> put(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String value,
            String fallbackEnvironmentVariable,
            long expectedVersion,
            Instant updatedAt) {
        var encrypted = cipher.encrypt(scope, targetId, secretName, value);
        var secretFingerprint = fingerprint(value);
        var changed = jdbcClient.sql("""
                insert into runtime_secret_override (
                  scope_type, target_id, secret_name, ciphertext, nonce,
                  fingerprint, encryption_key_version, version, updated_at
                )
                select :scopeType, :targetId, :secretName, :ciphertext, :nonce,
                       :fingerprint, :keyVersion, 1, :updatedAt
                where :expectedVersion = 0 or exists (
                  select 1 from runtime_secret_override current
                  where current.scope_type = :scopeType
                    and current.target_id = :targetId
                    and current.secret_name = :secretName
                    and current.version = :expectedVersion
                )
                on conflict (scope_type, target_id, secret_name) do update
                set ciphertext = excluded.ciphertext,
                    nonce = excluded.nonce,
                    fingerprint = excluded.fingerprint,
                    encryption_key_version = excluded.encryption_key_version,
                    version = runtime_secret_override.version + 1,
                    updated_at = excluded.updated_at
                where runtime_secret_override.version = :expectedVersion
                """)
                .param("scopeType", scope.name())
                .param("targetId", targetId)
                .param("secretName", secretName)
                .param("ciphertext", encrypted.ciphertext())
                .param("nonce", encrypted.nonce())
                .param("fingerprint", secretFingerprint)
                .param("keyVersion", encrypted.keyVersion())
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (changed != 1) {
            return Optional.empty();
        }
        storedSecretCache.remove(scope);
        audit(scope, targetId, secretName, "SET", secretFingerprint, expectedVersion + 1, updatedAt);
        return Optional.of(status(
                scope, targetId, secretName, fallbackEnvironmentVariable, find(scope, targetId, secretName)));
    }

    @Override
    @Transactional
    public Optional<RuntimeSecretStatus> clear(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                insert into runtime_secret_override (
                  scope_type, target_id, secret_name, ciphertext, nonce,
                  fingerprint, encryption_key_version, version, updated_at
                )
                select :scopeType, :targetId, :secretName, null, null, null, null, 1, :updatedAt
                where :expectedVersion = 0 or exists (
                  select 1 from runtime_secret_override current
                  where current.scope_type = :scopeType
                    and current.target_id = :targetId
                    and current.secret_name = :secretName
                    and current.version = :expectedVersion
                )
                on conflict (scope_type, target_id, secret_name) do update
                set ciphertext = null,
                    nonce = null,
                    fingerprint = null,
                    encryption_key_version = null,
                    version = runtime_secret_override.version + 1,
                    updated_at = excluded.updated_at
                where runtime_secret_override.version = :expectedVersion
                """)
                .param("scopeType", scope.name())
                .param("targetId", targetId)
                .param("secretName", secretName)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        if (changed != 1) {
            return Optional.empty();
        }
        storedSecretCache.remove(scope);
        audit(scope, targetId, secretName, "CLEAR", null, expectedVersion + 1, updatedAt);
        return Optional.of(status(
                scope, targetId, secretName, fallbackEnvironmentVariable, find(scope, targetId, secretName)));
    }

    private Optional<StoredSecret> find(RuntimeSecretScope scope, String targetId, String secretName) {
        var now = System.nanoTime();
        var cached = storedSecretCache.get(scope);
        if (cached == null || cached.expiresAtNanos() <= now) {
            cached = storedSecretCache.compute(scope, (key, current) ->
                    current != null && current.expiresAtNanos() > now ? current : loadScope(key, now));
        }
        return Optional.ofNullable(cached.values().get(new SecretKey(targetId, secretName)));
    }

    private CachedScope loadScope(RuntimeSecretScope scope, long loadedAtNanos) {
        var values = jdbcClient.sql("""
                select target_id, secret_name, ciphertext, nonce, fingerprint,
                       encryption_key_version, version, updated_at
                from runtime_secret_override
                where scope_type = :scopeType
                """)
                .param("scopeType", scope.name())
                .query((resultSet, rowNumber) -> Map.entry(
                        new SecretKey(resultSet.getString("target_id"), resultSet.getString("secret_name")),
                        stored(resultSet)))
                .list();
        var snapshot = new HashMap<SecretKey, StoredSecret>(values.size());
        values.forEach(entry -> snapshot.put(entry.getKey(), entry.getValue()));
        return new CachedScope(Map.copyOf(snapshot), loadedAtNanos + STORED_SECRET_CACHE_TTL_NANOS);
    }

    private RuntimeSecretStatus status(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String fallbackEnvironmentVariable,
            Optional<StoredSecret> stored) {
        if (stored.isPresent() && stored.get().ciphertext() != null) {
            var value = stored.get();
            return new RuntimeSecretStatus(
                    scope,
                    targetId,
                    secretName,
                    RuntimeSecretSource.DATABASE_OVERRIDE,
                    true,
                    value.fingerprint(),
                    value.version(),
                    value.updatedAt());
        }
        var fallback = fallbackValue(scope, targetId, secretName, fallbackEnvironmentVariable);
        return new RuntimeSecretStatus(
                scope,
                targetId,
                secretName,
                fallback.isPresent()
                        ? RuntimeSecretSource.ENVIRONMENT_FALLBACK
                        : RuntimeSecretSource.UNCONFIGURED,
                fallback.isPresent(),
                fallback.map(JdbcEncryptedRuntimeSecretStore::fingerprint).orElse(null),
                stored.map(StoredSecret::version).orElse(0L),
                stored.map(StoredSecret::updatedAt).orElse(null));
    }

    private Optional<String> fallbackValue(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String variable) {
        if (variable == null || variable.isBlank()) {
            return Optional.empty();
        }
        if (scope == RuntimeSecretScope.AI_PROVIDER
                && AI_PROVIDER_KEYS_ENV.equals(variable)) {
            return environment.resolve(AI_PROVIDER_KEYS_ENV)
                    .filter(value -> !value.isBlank())
                    .flatMap(value -> keyedValue(value, targetId, "AI provider key fallback"));
        }
        if (scope == RuntimeSecretScope.EXCHANGE_ACCOUNT
                && EXCHANGE_ACCOUNT_CREDENTIALS_ENV.equals(variable)) {
            return environment.resolve(EXCHANGE_ACCOUNT_CREDENTIALS_ENV)
                    .filter(value -> !value.isBlank())
                    .flatMap(value -> nestedValue(
                            value, targetId, secretName, "Exchange account credential fallback"));
        }
        if (scope == RuntimeSecretScope.INFORMATION_SOURCE
                && INFORMATION_SOURCE_KEYS_ENV.equals(variable)) {
            return environment.resolve(INFORMATION_SOURCE_KEYS_ENV)
                    .filter(value -> !value.isBlank())
                    .flatMap(value -> keyedValue(value, targetId, "Information source key fallback"));
        }
        if (scope == RuntimeSecretScope.PROXY_ROUTE
                && PROXY_ROUTE_URLS_ENV.equals(variable)) {
            return environment.resolve(PROXY_ROUTE_URLS_ENV)
                    .filter(value -> !value.isBlank())
                    .flatMap(value -> keyedValue(value, targetId, "Proxy route URL fallback"));
        }
        if (scope == RuntimeSecretScope.PROXY_GATEWAY
                && PROXY_GATEWAY_SECRETS_ENV.equals(variable)) {
            return environment.resolve(PROXY_GATEWAY_SECRETS_ENV)
                    .filter(value -> !value.isBlank())
                    .flatMap(value -> nestedValue(
                            value, targetId, secretName, "Proxy gateway secret fallback"));
        }
        return environment.resolve(variable).filter(value -> !value.isBlank());
    }

    private Optional<String> keyedValue(String value, String targetId, String label) {
        try {
            var root = objectMapper.readTree(value);
            if (!root.isObject()) {
                throw new IllegalStateException(label + " must be a JSON object");
            }
            var key = root.path(targetId);
            return key.isTextual() && !key.textValue().isBlank()
                    ? Optional.of(key.textValue().strip())
                    : Optional.empty();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(label + " is invalid JSON", exception);
        }
    }

    private Optional<String> nestedValue(
            String value,
            String targetId,
            String secretName,
            String label) {
        try {
            var root = objectMapper.readTree(value);
            if (!root.isObject()) {
                throw new IllegalStateException(label + " must be a JSON object");
            }
            var credential = root.path(targetId).path(secretName);
            return credential.isTextual() && !credential.textValue().isBlank()
                    ? Optional.of(credential.textValue().strip())
                    : Optional.empty();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(label + " is invalid JSON", exception);
        }
    }

    private void audit(
            RuntimeSecretScope scope,
            String targetId,
            String secretName,
            String action,
            String secretFingerprint,
            long version,
            Instant occurredAt) {
        jdbcClient.sql("""
                insert into runtime_secret_audit (
                  scope_type, target_id, secret_name, action, fingerprint, secret_version, occurred_at
                ) values (
                  :scopeType, :targetId, :secretName, :action, :fingerprint, :version, :occurredAt
                )
                """)
                .param("scopeType", scope.name())
                .param("targetId", targetId)
                .param("secretName", secretName)
                .param("action", action)
                .param("fingerprint", secretFingerprint)
                .param("version", version)
                .param("occurredAt", timestamp(occurredAt))
                .update();
    }

    private static StoredSecret stored(ResultSet resultSet) throws SQLException {
        var keyVersion = resultSet.getObject("encryption_key_version", Integer.class);
        return new StoredSecret(
                resultSet.getBytes("ciphertext"),
                resultSet.getBytes("nonce"),
                resultSet.getString("fingerprint"),
                keyVersion == null ? 0 : keyVersion,
                resultSet.getLong("version"),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record StoredSecret(
            byte[] ciphertext,
            byte[] nonce,
            String fingerprint,
            int encryptionKeyVersion,
            long version,
            Instant updatedAt) {
    }

    private record SecretKey(String targetId, String secretName) {
    }

    private record CachedScope(Map<SecretKey, StoredSecret> values, long expiresAtNanos) {
    }
}
