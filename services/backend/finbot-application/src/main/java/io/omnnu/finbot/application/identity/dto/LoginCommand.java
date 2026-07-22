package io.omnnu.finbot.application.identity.dto;

import io.omnnu.finbot.domain.identity.AuthChallengeId;
import java.util.Objects;

public record LoginCommand(
        String username,
        String password,
        AuthChallengeId challengeId,
        String proofOfWorkSolution,
        int mathAnswer) {
    public LoginCommand {
        username = requireText(username, "username", 80);
        password = requireText(password, "password", 500);
        Objects.requireNonNull(challengeId, "challengeId");
        proofOfWorkSolution = requireText(proofOfWorkSolution, "proofOfWorkSolution", 200);
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
