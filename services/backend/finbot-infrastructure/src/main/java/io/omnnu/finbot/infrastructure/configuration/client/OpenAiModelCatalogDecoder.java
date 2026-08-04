package io.omnnu.finbot.infrastructure.configuration.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.dto.DiscoveredModelCapability;
import io.omnnu.finbot.application.configuration.dto.ModelCatalogWarning;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class OpenAiModelCatalogDecoder {
    private static final int MAXIMUM_MODELS = 500;

    private final ObjectMapper objectMapper;

    OpenAiModelCatalogDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    DecodedModelCatalog decode(String body) throws IOException {
        var root = objectMapper.readTree(body);
        var warnings = new ModelCatalogWarningCollector();
        var capabilities = new LinkedHashMap<String, DiscoveredModelCapability>();
        collect(root.path("data"), "data", capabilities, warnings);
        collect(root.path("models"), "models", capabilities, warnings);
        var ordered = capabilities.values().stream()
                .sorted(Comparator.comparing(DiscoveredModelCapability::modelName))
                .toList();
        if (ordered.size() > MAXIMUM_MODELS) {
            warnings.addCritical(
                    "MODEL_CATALOG_TRUNCATED",
                    null,
                    "模型目录包含 " + ordered.size() + " 个不同模型，仅保留前 " + MAXIMUM_MODELS + " 个");
            ordered = ordered.subList(0, MAXIMUM_MODELS);
        }
        var immutableCapabilities = List.copyOf(ordered);
        return new DecodedModelCatalog(
                immutableCapabilities.stream().map(DiscoveredModelCapability::modelName).toList(),
                immutableCapabilities,
                warnings.result());
    }

    private static void collect(
            JsonNode array,
            String fieldName,
            Map<String, DiscoveredModelCapability> capabilities,
            ModelCatalogWarningCollector warnings) {
        if (array.isMissingNode() || array.isNull()) {
            return;
        }
        if (!array.isArray()) {
            warnings.add(
                    "MODEL_CATALOG_FIELD_INVALID",
                    null,
                    "模型目录字段 " + fieldName + " 必须是数组");
            return;
        }
        array.forEach(item -> {
            var parsed = OpenAiModelCapabilityParser.parse(item, warnings);
            if (parsed == null) {
                return;
            }
            capabilities.merge(
                    parsed.modelName(),
                    parsed,
                    (first, second) -> DiscoveredModelCapabilityMerger.merge(first, second, warnings));
        });
    }

    record DecodedModelCatalog(
            List<String> models,
            List<DiscoveredModelCapability> modelCapabilities,
            List<ModelCatalogWarning> warnings) {
        DecodedModelCatalog {
            models = List.copyOf(models);
            modelCapabilities = List.copyOf(modelCapabilities);
            warnings = List.copyOf(warnings);
        }
    }
}
