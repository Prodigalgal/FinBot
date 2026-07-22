package io.omnnu.finbot.application.identity.dto;

import io.omnnu.finbot.domain.identity.AuthChallengeId;
import java.time.Instant;

public record CreateAuthChallengeResult(
        AuthChallengeId challengeId,
        String nonce,
        int proofOfWorkDifficulty,
        String mathExpression,
        Instant expiresAt) {
}
