package io.omnnu.finbot.configuration.properties;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finbot.auth")
public record AuthenticationProperties(
        String adminUsername,
        String adminPassword,
        Duration challengeTtl,
        int proofOfWorkDifficulty,
        int maximumChallengeFailures,
        Duration sessionTtl,
        Duration touchInterval,
        boolean secureCookie) {
    public AuthenticationProperties {
        adminUsername = requireText(adminUsername, "finbot.auth.admin-username");
        adminPassword = requireText(adminPassword, "finbot.auth.admin-password");
        Objects.requireNonNull(challengeTtl, "challengeTtl");
        Objects.requireNonNull(sessionTtl, "sessionTtl");
        Objects.requireNonNull(touchInterval, "touchInterval");
    }

    private static String requireText(String value, String propertyName) {
        var normalized = Objects.requireNonNull(value, propertyName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(propertyName + " must be supplied through the environment");
        }
        return normalized;
    }
}
