package io.omnnu.finbot.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.domain.identity.AdminSessionId;
import io.omnnu.finbot.domain.identity.AuthChallengeId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class AuthenticationApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-14T08:00:00Z");

    @Test
    void consumesChallengeOnceAndRevokesPersistentSession() {
        var store = new InMemoryAuthenticationStore();
        var service = service(store);
        var challenge = service.createChallenge();
        var answer = solve(challenge.mathExpression());
        var command = new LoginCommand(
                "admin",
                "correct-password",
                challenge.challengeId(),
                "valid-work",
                answer);

        var login = service.login(command);

        assertEquals("admin", login.username());
        assertTrue(service.validateSession(login.rawSessionToken()).isPresent());
        assertThrows(AuthenticationRejectedException.class, () -> service.login(command));

        service.logout(login.rawSessionToken());
        assertFalse(service.validateSession(login.rawSessionToken()).isPresent());
    }

    @Test
    void rejectsWrongMathAnswerAndRecordsFailure() {
        var store = new InMemoryAuthenticationStore();
        var service = service(store);
        var challenge = service.createChallenge();

        assertThrows(AuthenticationRejectedException.class, () -> service.login(new LoginCommand(
                "admin",
                "correct-password",
                challenge.challengeId(),
                "valid-work",
                Integer.MIN_VALUE)));

        assertEquals(1, store.findChallenge(challenge.challengeId()).orElseThrow().failureCount());
    }

    private static AuthenticationApplicationService service(InMemoryAuthenticationStore store) {
        var sequence = new AtomicInteger();
        SortableIdGenerator ids = prefix -> prefix + "01j0000" + sequence.incrementAndGet();
        AuthenticationCryptography cryptography = new DeterministicCryptography();
        AdminCredentialVerifier credentials = (username, password) ->
                "admin".equals(username) && "correct-password".equals(password);
        return new AuthenticationApplicationService(
                ids,
                store,
                credentials,
                cryptography,
                new AuthenticationPolicy(
                        Duration.ofMinutes(5),
                        3,
                        5,
                        Duration.ofHours(12),
                        Duration.ofMinutes(1)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                RandomGeneratorFactory.of("L64X128MixRandom").create(42));
    }

    private static int solve(String expression) {
        var parts = expression.replace(" = ?", "").split(" ");
        var left = Integer.parseInt(parts[0]);
        var right = Integer.parseInt(parts[2]);
        return "+".equals(parts[1]) ? left + right : left - right;
    }

    private static final class DeterministicCryptography implements AuthenticationCryptography {
        private int tokenSequence;

        @Override
        public String randomToken(int byteCount) {
            return "token-" + byteCount + "-" + ++tokenSequence;
        }

        @Override
        public String digest(String value) {
            return "digest:" + value;
        }

        @Override
        public boolean constantTimeEquals(String left, String right) {
            return left.equals(right);
        }

        @Override
        public boolean verifyProofOfWork(String nonce, String solution, int difficulty) {
            return "valid-work".equals(solution) && difficulty == 3;
        }
    }

    private static final class InMemoryAuthenticationStore implements AuthenticationStore {
        private final Map<AuthChallengeId, AuthChallenge> challenges = new HashMap<>();
        private final Map<String, AdminSession> sessions = new HashMap<>();

        @Override
        public void saveChallenge(AuthChallenge challenge) {
            challenges.put(challenge.challengeId(), challenge);
        }

        @Override
        public Optional<AuthChallenge> findChallenge(AuthChallengeId challengeId) {
            return Optional.ofNullable(challenges.get(challengeId));
        }

        @Override
        public void recordChallengeFailure(AuthChallengeId challengeId) {
            var current = challenges.get(challengeId);
            challenges.put(challengeId, new AuthChallenge(
                    current.challengeId(), current.nonce(), current.answerDigest(),
                    current.proofOfWorkDifficulty(), current.expiresAt(), current.consumedAt(),
                    current.failureCount() + 1, current.createdAt()));
        }

        @Override
        public boolean consumeChallenge(AuthChallengeId challengeId, Instant consumedAt) {
            var current = challenges.get(challengeId);
            if (current == null || current.consumedAt() != null || !consumedAt.isBefore(current.expiresAt())) {
                return false;
            }
            challenges.put(challengeId, new AuthChallenge(
                    current.challengeId(), current.nonce(), current.answerDigest(),
                    current.proofOfWorkDifficulty(), current.expiresAt(), consumedAt,
                    current.failureCount(), current.createdAt()));
            return true;
        }

        @Override
        public void saveSession(AdminSession session, String tokenDigest) {
            sessions.put(tokenDigest, session);
        }

        @Override
        public Optional<AdminSession> findActiveSession(String tokenDigest, Instant now) {
            return Optional.ofNullable(sessions.get(tokenDigest)).filter(session -> session.activeAt(now));
        }

        @Override
        public void touchSession(String tokenDigest, Instant seenAt) {
            var current = sessions.get(tokenDigest);
            if (current != null) {
                sessions.put(tokenDigest, new AdminSession(
                        current.sessionId(), current.username(), current.expiresAt(), seenAt,
                        current.revokedAt(), current.createdAt()));
            }
        }

        @Override
        public void revokeSession(String tokenDigest, Instant revokedAt) {
            var current = sessions.get(tokenDigest);
            if (current != null) {
                sessions.put(tokenDigest, new AdminSession(
                        new AdminSessionId(current.sessionId().value()), current.username(),
                        current.expiresAt(), current.lastSeenAt(), revokedAt, current.createdAt()));
            }
        }
    }
}
