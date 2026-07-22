package io.omnnu.finbot.api.identity.dto;

import io.omnnu.finbot.application.identity.dto.CreateAuthChallengeResult;
import java.time.Instant;

public record AuthChallengeResponse(
        String challengeId,
        String nonce,
        String proofOfWorkAlgorithm,
        int proofOfWorkDifficulty,
        String mathExpression,
        Instant expiresAt) {
    public static AuthChallengeResponse from(CreateAuthChallengeResult result) {
        return new AuthChallengeResponse(
                result.challengeId().value(),
                result.nonce(),
                "SHA-256_HEX_PREFIX",
                result.proofOfWorkDifficulty(),
                result.mathExpression(),
                result.expiresAt());
    }
}
