package io.omnnu.finbot.application.identity;

import io.omnnu.finbot.domain.identity.AuthChallengeId;
import java.time.Instant;
import java.util.Objects;

public record AuthChallenge(
        AuthChallengeId challengeId,
        String nonce,
        String answerDigest,
        int proofOfWorkDifficulty,
        Instant expiresAt,
        Instant consumedAt,
        int failureCount,
        Instant createdAt) {
    public AuthChallenge {
        Objects.requireNonNull(challengeId, "challengeId");
        nonce = requireText(nonce, "nonce");
        answerDigest = requireText(answerDigest, "answerDigest");
        if (proofOfWorkDifficulty < 1 || proofOfWorkDifficulty > 8) {
            throw new IllegalArgumentException("proofOfWorkDifficulty must be between 1 and 8");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        if (failureCount < 0 || failureCount > 10) {
            throw new IllegalArgumentException("failureCount must be between 0 and 10");
        }
    }

    public boolean usableAt(Instant now, int maximumFailures) {
        return consumedAt == null && now.isBefore(expiresAt) && failureCount < maximumFailures;
    }

    private static String requireText(String value, String fieldName) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
