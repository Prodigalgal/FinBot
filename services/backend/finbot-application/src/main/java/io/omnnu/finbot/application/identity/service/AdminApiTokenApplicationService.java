package io.omnnu.finbot.application.identity.service;

import io.omnnu.finbot.application.identity.dto.CreateAdminApiTokenCommand;
import io.omnnu.finbot.application.identity.dto.CreatedAdminApiToken;
import io.omnnu.finbot.application.identity.exception.AdminApiTokenConflictException;
import io.omnnu.finbot.application.identity.port.in.AdminApiTokenUseCase;
import io.omnnu.finbot.application.identity.port.out.AdminApiTokenStore;
import io.omnnu.finbot.application.identity.port.out.AuthenticationCryptography;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public final class AdminApiTokenApplicationService implements AdminApiTokenUseCase {
    static final String TOKEN_PREFIX = "finbot_pat_";
    private static final int TOKEN_BYTES = 32;
    private static final int MAXIMUM_ACTIVE_TOKENS = 50;
    private static final int MAXIMUM_EXPIRY_DAYS = 3_650;
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final Pattern RAW_TOKEN = Pattern.compile("finbot_pat_[A-Za-z0-9_-]{43}");
    private static final Pattern TOKEN_DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final SortableIdGenerator idGenerator;
    private final AdminApiTokenStore store;
    private final AuthenticationCryptography cryptography;
    private final Clock clock;

    public AdminApiTokenApplicationService(
            SortableIdGenerator idGenerator,
            AdminApiTokenStore store,
            AuthenticationCryptography cryptography,
            Clock clock) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.store = Objects.requireNonNull(store, "store");
        this.cryptography = Objects.requireNonNull(cryptography, "cryptography");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<AdminApiToken> listTokens() {
        return store.listTokens();
    }

    @Override
    public CreatedAdminApiToken createToken(CreateAdminApiTokenCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        if (store.countActiveTokens(now) >= MAXIMUM_ACTIVE_TOKENS) {
            throw new AdminApiTokenConflictException(
                    "Active API token limit reached; revoke an existing token first");
        }
        var displayName = required(command.displayName(), "displayName", 120);
        var username = required(command.username(), "username", 80);
        var expiresAt = expiresAt(command.expiresInDays(), now);
        var rawToken = TOKEN_PREFIX + cryptography.randomToken(TOKEN_BYTES);
        if (!RAW_TOKEN.matcher(rawToken).matches()) {
            throw new IllegalStateException("Authentication cryptography returned an invalid API token");
        }
        var digest = cryptography.digest(rawToken);
        if (digest == null || !TOKEN_DIGEST.matcher(digest).matches()) {
            throw new IllegalStateException("Authentication cryptography returned an invalid API token digest");
        }
        var token = new AdminApiToken(
                new AdminApiTokenId(idGenerator.next("apitoken_")),
                displayName,
                digest.substring(0, 16),
                username,
                expiresAt,
                null,
                null,
                now,
                now,
                0);
        store.createToken(token, digest);
        return new CreatedAdminApiToken(token, rawToken);
    }

    @Override
    public Optional<AdminApiToken> authenticate(String rawToken) {
        if (rawToken == null || !RAW_TOKEN.matcher(rawToken).matches()) {
            return Optional.empty();
        }
        var digest = cryptography.digest(rawToken);
        var now = clock.instant();
        var token = store.findActiveToken(digest, now);
        token.filter(value -> value.lastUsedAt() == null
                        || !value.lastUsedAt().plus(TOUCH_INTERVAL).isAfter(now))
                .ifPresent(ignored -> store.touchToken(digest, now));
        return token;
    }

    @Override
    public AdminApiToken revokeToken(AdminApiTokenId tokenId, long expectedVersion) {
        Objects.requireNonNull(tokenId, "tokenId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        return store.revokeToken(tokenId, expectedVersion, clock.instant())
                .orElseThrow(() -> new AdminApiTokenConflictException(
                        "API token changed, is already revoked, or does not exist"));
    }

    private static Instant expiresAt(Integer expiresInDays, Instant now) {
        if (expiresInDays == null) {
            return null;
        }
        if (expiresInDays < 1 || expiresInDays > MAXIMUM_EXPIRY_DAYS) {
            throw new IllegalArgumentException("expiresInDays must be between 1 and 3650");
        }
        return now.plus(Duration.ofDays(expiresInDays.longValue()));
    }

    private static String required(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
