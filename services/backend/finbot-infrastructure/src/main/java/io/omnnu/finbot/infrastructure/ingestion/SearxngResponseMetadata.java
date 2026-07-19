package io.omnnu.finbot.infrastructure.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Objects;

final class SearxngResponseMetadata {
    private static final int MAXIMUM_ENGINES = 16;
    private static final int MAXIMUM_METADATA_TEXT = 160;

    private SearxngResponseMetadata() {
    }

    static String unresponsiveEngines(JsonNode value) {
        if (!value.isArray()) {
            return "";
        }
        var errors = new ArrayList<String>();
        for (var item : value) {
            if (errors.size() >= MAXIMUM_ENGINES) {
                break;
            }
            if (item.isArray() && item.size() >= 2) {
                var engine = safeText(item.get(0).asText());
                var reason = safeText(item.get(1).asText());
                if (!engine.isBlank() || !reason.isBlank()) {
                    errors.add(engine + ":" + reason);
                }
            }
        }
        return String.join("|", errors);
    }

    static String resultEngines(JsonNode result) {
        var engines = new ArrayList<String>();
        var value = result.path("engines");
        if (value.isArray()) {
            for (var engine : value) {
                var normalized = safeText(engine.asText());
                if (!normalized.isBlank() && engines.size() < MAXIMUM_ENGINES) {
                    engines.add(normalized);
                }
            }
        } else {
            var engine = safeText(result.path("engine").asText());
            if (!engine.isBlank()) {
                engines.add(engine);
            }
        }
        return String.join(",", engines);
    }

    private static String safeText(String value) {
        var normalized = Objects.requireNonNullElse(value, "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .strip();
        return normalized.length() <= MAXIMUM_METADATA_TEXT
                ? normalized
                : normalized.substring(0, MAXIMUM_METADATA_TEXT);
    }
}
