package io.omnnu.finbot.application.ingestion;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public record ContentEnvelope(
        int schemaVersion,
        URI requestedUrl,
        URI canonicalUrl,
        String contentType,
        List<ContentBlock> blocks,
        Map<String, String> metadata) {
    public ContentEnvelope {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        requestedUrl = Objects.requireNonNull(requestedUrl, "requestedUrl");
        contentType = Objects.requireNonNullElse(contentType, "application/octet-stream").strip();
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (blocks.isEmpty() || blocks.size() > 2_000) {
            throw new IllegalArgumentException("Content envelope block count is invalid");
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public String normalizedText() {
        return blocks.stream()
                .map(ContentBlock::text)
                .collect(Collectors.joining("\n"));
    }

    public List<String> blockIds() {
        return blocks.stream().map(ContentBlock::blockId).toList();
    }

    public static ContentEnvelope raw(
            URI requestedUrl,
            URI canonicalUrl,
            String contentType,
            String rawContent) {
        var blocks = new java.util.ArrayList<ContentBlock>();
        var content = Objects.requireNonNull(rawContent, "rawContent");
        for (var offset = 0; offset < Math.max(1, content.length()); offset += 20_000) {
            var end = Math.min(content.length(), offset + 20_000);
            var ordinal = blocks.size();
            blocks.add(new ContentBlock(
                    "b" + ordinal,
                    "RAW_CHUNK",
                    content.substring(Math.min(offset, end), end),
                    ordinal,
                    Map.of()));
        }
        return new ContentEnvelope(
                1,
                requestedUrl,
                canonicalUrl,
                contentType,
                blocks,
                Map.of("builder", "raw-v1"));
    }
}
