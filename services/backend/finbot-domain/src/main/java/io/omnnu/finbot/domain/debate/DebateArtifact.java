package io.omnnu.finbot.domain.debate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record DebateArtifact(
        DebateArtifactId artifactId,
        DebateTaskId taskId,
        DebatePhaseId phaseId,
        DebateArtifactStatus status,
        String contentHash,
        String content,
        Instant sealedAt,
        Instant revealedAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public DebateArtifact {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(phaseId, "phaseId");
        Objects.requireNonNull(status, "status");
        contentHash = Objects.requireNonNull(contentHash, "contentHash").strip();
        content = Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sealedAt, "sealedAt");
        if (!SHA_256.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 value");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (status == DebateArtifactStatus.REVEALED && revealedAt == null) {
            throw new IllegalArgumentException("A revealed artifact must have revealedAt");
        }
        if (status == DebateArtifactStatus.SEALED && revealedAt != null) {
            throw new IllegalArgumentException("A sealed artifact must not have revealedAt");
        }
    }

    public static String contentHashFor(String content) {
        Objects.requireNonNull(content, "content");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
