package io.omnnu.finbot.application.identity.service;

import io.omnnu.finbot.application.identity.dto.CreateAdminApiTokenCommand;
import io.omnnu.finbot.application.identity.port.out.AdminApiTokenStore;
import io.omnnu.finbot.application.identity.port.out.AuthenticationCryptography;
import io.omnnu.finbot.application.identity.service.AdminApiTokenApplicationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import io.omnnu.finbot.domain.identity.AdminApiTokenStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminApiTokenApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");
    private static final String TOKEN_BODY = "A".repeat(43);

    @Test
    void createsOneTimeTokenAuthenticatesTouchesAndRevokesIt() {
        var store = new InMemoryTokenStore();
        var service = service(store);

        var created = service.createToken(new CreateAdminApiTokenCommand(
                "CI automation",
                90,
                "admin"));

        assertEquals("finbot_pat_" + TOKEN_BODY, created.rawToken());
        assertEquals(NOW.plusSeconds(90L * 86_400L), created.token().expiresAt());
        assertEquals(AdminApiTokenStatus.ACTIVE, created.token().statusAt(NOW));
        assertFalse(store.digestById.get(created.token().tokenId()).contains(created.rawToken()));
        assertEquals(64, store.digestById.get(created.token().tokenId()).length());

        var authenticated = service.authenticate(created.rawToken()).orElseThrow();
        assertEquals(created.token().tokenId(), authenticated.tokenId());
        assertEquals(1, store.touchCount);
        assertTrue(service.authenticate("not-a-finbot-token").isEmpty());

        var revoked = service.revokeToken(created.token().tokenId(), 0);
        assertEquals(AdminApiTokenStatus.REVOKED, revoked.statusAt(NOW));
        assertEquals(1, revoked.version());
        assertTrue(service.authenticate(created.rawToken()).isEmpty());
    }

    @Test
    void supportsExplicitNonExpiringToken() {
        var created = service(new InMemoryTokenStore()).createToken(new CreateAdminApiTokenCommand(
                "Long lived integration",
                null,
                "admin"));

        assertNull(created.token().expiresAt());
    }

    private static AdminApiTokenApplicationService service(InMemoryTokenStore store) {
        AuthenticationCryptography cryptography = new AuthenticationCryptography() {
            @Override
            public String randomToken(int byteCount) {
                assertEquals(32, byteCount);
                return TOKEN_BODY;
            }

            @Override
            public String digest(String value) {
                try {
                    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
                } catch (NoSuchAlgorithmException exception) {
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public boolean constantTimeEquals(String left, String right) {
                return left.equals(right);
            }

            @Override
            public boolean verifyProofOfWork(String nonce, String solution, int difficulty) {
                return false;
            }
        };
        return new AdminApiTokenApplicationService(
                prefix -> prefix + "test0001",
                store,
                cryptography,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class InMemoryTokenStore implements AdminApiTokenStore {
        private final Map<AdminApiTokenId, AdminApiToken> tokens = new LinkedHashMap<>();
        private final Map<AdminApiTokenId, String> digestById = new LinkedHashMap<>();
        private int touchCount;

        @Override
        public List<AdminApiToken> listTokens() {
            return new ArrayList<>(tokens.values());
        }

        @Override
        public long countActiveTokens(Instant now) {
            return tokens.values().stream().filter(token -> token.activeAt(now)).count();
        }

        @Override
        public void createToken(AdminApiToken token, String tokenDigest) {
            tokens.put(token.tokenId(), token);
            digestById.put(token.tokenId(), tokenDigest);
        }

        @Override
        public Optional<AdminApiToken> findActiveToken(String tokenDigest, Instant now) {
            return digestById.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(tokenDigest))
                    .map(entry -> tokens.get(entry.getKey()))
                    .filter(token -> token.activeAt(now))
                    .findFirst();
        }

        @Override
        public void touchToken(String tokenDigest, Instant usedAt) {
            touchCount++;
            var current = findActiveToken(tokenDigest, usedAt).orElseThrow();
            tokens.put(current.tokenId(), new AdminApiToken(
                    current.tokenId(),
                    current.displayName(),
                    current.fingerprint(),
                    current.username(),
                    current.expiresAt(),
                    usedAt,
                    current.revokedAt(),
                    current.createdAt(),
                    usedAt,
                    current.version()));
        }

        @Override
        public Optional<AdminApiToken> revokeToken(
                AdminApiTokenId tokenId,
                long expectedVersion,
                Instant revokedAt) {
            var current = tokens.get(tokenId);
            if (current == null || current.version() != expectedVersion || current.revokedAt() != null) {
                return Optional.empty();
            }
            var revoked = new AdminApiToken(
                    current.tokenId(),
                    current.displayName(),
                    current.fingerprint(),
                    current.username(),
                    current.expiresAt(),
                    current.lastUsedAt(),
                    revokedAt,
                    current.createdAt(),
                    revokedAt,
                    current.version() + 1);
            tokens.put(tokenId, revoked);
            return Optional.of(revoked);
        }
    }
}
