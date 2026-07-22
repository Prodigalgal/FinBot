package io.omnnu.finbot.infrastructure.ingestion.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.dto.CollectedPayload;
import io.omnnu.finbot.application.ingestion.dto.ContentBlock;
import io.omnnu.finbot.application.ingestion.dto.ContentEnvelope;
import io.omnnu.finbot.application.ingestion.port.out.ContentEnvelopeBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class JsonContentEnvelopeBuilder implements ContentEnvelopeBuilder {
    private final ObjectMapper objectMapper;

    JsonContentEnvelopeBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ContentEnvelope build(CollectedPayload payload) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(payload.rawContent());
        } catch (Exception exception) {
            throw new IllegalArgumentException("JSON response cannot be parsed", exception);
        }
        var blocks = new ArrayList<ContentBlock>();
        if (root != null && root.isObject()) {
            root.properties().forEach(entry -> add(blocks, entry.getKey(), entry.getValue()));
        } else if (root != null && root.isArray()) {
            for (var index = 0; index < root.size() && blocks.size() < 2_000; index++) {
                add(blocks, "items[" + index + "]", root.get(index));
            }
        } else if (root != null) {
            add(blocks, "$", root);
        }
        if (blocks.isEmpty()) {
            add(blocks, "$", objectMapper.getNodeFactory().textNode(payload.rawContent()));
        }
        return new ContentEnvelope(
                1,
                payload.requestedUrl(),
                payload.canonicalUrl(),
                payload.contentType(),
                blocks,
                Map.of("builder", "jackson-json-blocks-v1"));
    }

    private static void add(ArrayList<ContentBlock> blocks, String path, JsonNode value) {
        var text = value == null ? "null" : value.toString();
        if (text.length() > 20_000) {
            text = text.substring(0, 20_000);
        }
        var attributes = new HashMap<String, String>();
        attributes.put("json_path", path);
        blocks.add(new ContentBlock(
                "b" + blocks.size(),
                value != null && value.isArray() ? "ARRAY" : "JSON_FIELD",
                text,
                blocks.size(),
                attributes));
    }
}
