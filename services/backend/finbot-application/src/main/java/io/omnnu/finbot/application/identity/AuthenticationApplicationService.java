package io.omnnu.finbot.application.identity;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.domain.identity.AdminSessionId;
import io.omnnu.finbot.domain.identity.AuthChallengeId;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

public final class AuthenticationApplicationService implements AuthenticationUseCase {
    private static final int TOKEN_BYTES = 32;

    private final SortableIdGenerator idGenerator;
    private final AuthenticationStore store;
    private final AdminCredentialVerifier credentialVerifier;
    private final AuthenticationCryptography cryptography;
    private final AuthenticationPolicy policy;
    private final Clock clock;
    private final RandomGenerator random;

    public AuthenticationApplicationService(
            SortableIdGenerator idGenerator,
            AuthenticationStore store,
            AdminCredentialVerifier credentialVerifier,
            AuthenticationCryptography cryptography,
            AuthenticationPolicy policy,
            Clock clock,
            RandomGenerator random) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.store = Objects.requireNonNull(store, "store");
        this.credentialVerifier = Objects.requireNonNull(credentialVerifier, "credentialVerifier");
        this.cryptography = Objects.requireNonNull(cryptography, "cryptography");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public CreateAuthChallengeResult createChallenge() {
        var now = clock.instant();
        var challengeId = new AuthChallengeId(idGenerator.next("challenge_"));
        var nonce = cryptography.randomToken(TOKEN_BYTES);
        var left = random.nextInt(10, 80);
        var right = random.nextInt(2, 30);
        var subtract = random.nextBoolean();
        if (subtract && right > left) {
            var swap = left;
            left = right;
            right = swap;
        }
        var answer = subtract ? left - right : left + right;
        var expression = left + (subtract ? " - " : " + ") + right + " = ?";
        var expiresAt = now.plus(policy.challengeTtl());
        var challenge = new AuthChallenge(
                challengeId,
                nonce,
                answerDigest(challengeId, answer),
                policy.proofOfWorkDifficulty(),
                expiresAt,
                null,
                0,
                now);
        store.saveChallenge(challenge);
        return new CreateAuthChallengeResult(
                challengeId,
                nonce,
                policy.proofOfWorkDifficulty(),
                expression,
                expiresAt);
    }

    @Override
    public LoginResult login(LoginCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        var challenge = store.findChallenge(command.challengeId()).orElseThrow(AuthenticationRejectedException::new);
        var valid = challenge.usableAt(now, policy.maximumChallengeFailures())
                & credentialVerifier.verify(command.username(), command.password())
                & cryptography.constantTimeEquals(
                        challenge.answerDigest(),
                        answerDigest(command.challengeId(), command.mathAnswer()))
                & cryptography.verifyProofOfWork(
                        challenge.nonce(),
                        command.proofOfWorkSolution(),
                        challenge.proofOfWorkDifficulty());
        if (!valid) {
            store.recordChallengeFailure(command.challengeId());
            throw new AuthenticationRejectedException();
        }
        if (!store.consumeChallenge(command.challengeId(), now)) {
            throw new AuthenticationRejectedException();
        }

        var rawToken = cryptography.randomToken(TOKEN_BYTES);
        var sessionId = new AdminSessionId(idGenerator.next("session_"));
        var expiresAt = now.plus(policy.sessionTtl());
        var session = new AdminSession(sessionId, command.username(), expiresAt, now, null, now);
        store.saveSession(session, cryptography.digest(rawToken));
        return new LoginResult(sessionId, rawToken, command.username(), expiresAt);
    }

    @Override
    public Optional<AdminSession> validateSession(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return Optional.empty();
        }
        var digest = cryptography.digest(rawSessionToken);
        var now = clock.instant();
        var session = store.findActiveSession(digest, now);
        session.filter(value -> value.lastSeenAt().plus(policy.touchInterval()).isBefore(now))
                .ifPresent(ignored -> store.touchSession(digest, now));
        return session;
    }

    @Override
    public void logout(String rawSessionToken) {
        if (rawSessionToken != null && !rawSessionToken.isBlank()) {
            store.revokeSession(cryptography.digest(rawSessionToken), clock.instant());
        }
    }

    private String answerDigest(AuthChallengeId challengeId, int answer) {
        return cryptography.digest(challengeId.value() + ":" + answer);
    }
}
