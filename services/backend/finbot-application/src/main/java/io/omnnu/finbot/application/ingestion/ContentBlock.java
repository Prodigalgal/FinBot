package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.shared.DomainText;
import java.util.Map;
import java.util.Objects;

public record ContentBlock(
        String blockId,
        String kind,
        String text,
        int ordinal,
        Map<String, String> attributes) {
    public ContentBlock {
        blockId = DomainText.required(blockId, "blockId", 80);
        kind = DomainText.required(kind, "kind", 40);
        text = Objects.requireNonNull(text, "text");
        if (text.length() > 20_000) {
            throw new IllegalArgumentException("Content block text is too long");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
