package io.omnnu.finbot.application.identity;

import io.omnnu.finbot.domain.identity.AuthChallengeId;
import java.time.Instant;
import java.util.Optional;

public interface AuthenticationStore {
    void saveChallenge(AuthChallenge challenge);

    Optional<AuthChallenge> findChallenge(AuthChallengeId challengeId);

    void recordChallengeFailure(AuthChallengeId challengeId);

    boolean consumeChallenge(AuthChallengeId challengeId, Instant consumedAt);

    void saveSession(AdminSession session, String tokenDigest);

    Optional<AdminSession> findActiveSession(String tokenDigest, Instant now);

    void touchSession(String tokenDigest, Instant seenAt);

    void revokeSession(String tokenDigest, Instant revokedAt);
}
