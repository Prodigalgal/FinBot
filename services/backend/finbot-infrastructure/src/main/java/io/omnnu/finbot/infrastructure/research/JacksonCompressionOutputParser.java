package io.omnnu.finbot.infrastructure.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.research.CompressionContent;
import io.omnnu.finbot.application.research.CompressionOutputParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class JacksonCompressionOutputParser implements CompressionOutputParser {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "summary", "key_points", "risks", "missing_evidence", "citations");

    private final ObjectMapper objectMapper;

    public JacksonCompressionOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public CompressionContent parse(String output) {
        var root = object(output);
        var actual = new HashSet<String>();
        root.fieldNames().forEachRemaining(actual::add);
        if (!ALLOWED_FIELDS.containsAll(actual)) {
            throw new IllegalArgumentException("Compression output contains unsupported fields");
        }
        return new CompressionContent(
                requiredText(root, "summary"),
                strings(root.path("key_points")),
                strings(root.path("risks")),
                strings(root.path("missing_evidence")),
                strings(root.path("citations")));
    }

    private ObjectNode object(String output) {
        var normalized = Objects.requireNonNull(output, "output").strip();
        if (normalized.startsWith("```")) {
            var firstLine = normalized.indexOf('\n');
            var closing = normalized.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                normalized = normalized.substring(firstLine + 1, closing).strip();
            }
        }
        try {
            var node = objectMapper.readTree(normalized);
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
            throw new IllegalArgumentException("Compression output root must be an object");
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Compression output is not valid JSON", exception);
        }
    }

    private static String requiredText(ObjectNode node, String fieldName) {
        var value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Compression output field is required: " + fieldName);
        }
        return value.textValue();
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Compression output collection must be an array");
        }
        var result = new ArrayList<String>();
        node.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("Compression output collection contains an invalid value");
            }
            result.add(value.textValue());
        });
        return List.copyOf(result);
    }
}
