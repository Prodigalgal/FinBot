package io.omnnu.finbot.application.ingestion.exception;

import io.omnnu.finbot.application.ingestion.dto.CrawlerAccessChallenge;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class SourceCollectionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean blocked;
    private final Integer statusCode;
    private final String challengeKind;

    public SourceCollectionException(String errorCode, String safeMessage, boolean blocked) {
        this(errorCode, safeMessage, blocked, null, null);
    }

    public SourceCollectionException(
            String errorCode,
            String safeMessage,
            boolean blocked,
            Integer statusCode) {
        this(errorCode, safeMessage, blocked, statusCode, null);
    }

    public SourceCollectionException(
            String errorCode,
            String safeMessage,
            boolean blocked,
            Integer statusCode,
            String challengeKind) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.blocked = blocked;
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            throw new IllegalArgumentException("statusCode is invalid");
        }
        this.statusCode = statusCode;
        this.challengeKind = normalizeChallengeKind(challengeKind);
    }

    public static SourceCollectionException forAccessFailure(
            String errorPrefix,
            String safeName,
            int statusCode,
            Optional<CrawlerAccessChallenge> challenge) {
        Objects.requireNonNull(errorPrefix, "errorPrefix");
        Objects.requireNonNull(safeName, "safeName");
        Objects.requireNonNull(challenge, "challenge");
        if (challenge.isPresent()) {
            var detected = challenge.get();
            return new SourceCollectionException(
                    errorPrefix + "_" + detected.errorCodeSuffix(),
                    detected.safeMessage(safeName),
                    detected.blocked(),
                    statusCode,
                    detected.kind().name());
        }
        var blocked = statusCode == 401 || statusCode == 403 || statusCode == 429;
        return new SourceCollectionException(
                errorPrefix + "_HTTP_" + statusCode,
                safeName + " returned HTTP " + statusCode,
                blocked,
                statusCode,
                null);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean blocked() {
        return blocked;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public Optional<String> challengeKind() {
        return challengeKind == null ? Optional.empty() : Optional.of(challengeKind);
    }

    public String observationLabel() {
        return challengeKind == null
                ? "none"
                : "challenge/" + challengeKind.toLowerCase(Locale.ROOT);
    }

    private static String normalizeChallengeKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("challengeKind is invalid");
        }
        return normalized;
    }
}
