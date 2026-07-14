package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

public record ResearchArtifact(
        ArtifactKind kind,
        URI uri,
        String sha256Hex,
        String mediaType,
        long byteSize) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public ResearchArtifact {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(uri, "uri");
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("artifact uri must be absolute");
        }
        sha256Hex = Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (!SHA_256.matcher(sha256Hex).matches()) {
            throw new IllegalArgumentException("sha256Hex must be a lowercase SHA-256 digest");
        }
        mediaType = DomainText.required(mediaType, "mediaType", 120);
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize must not be negative");
        }
    }
}
