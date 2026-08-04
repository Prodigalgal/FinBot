package io.omnnu.finbot.infrastructure.configuration.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.omnnu.finbot.application.configuration.dto.CapabilitySource;
import io.omnnu.finbot.application.configuration.dto.CapabilitySupport;
import io.omnnu.finbot.application.configuration.dto.DiscoveredModelCapability;
import io.omnnu.finbot.application.configuration.dto.ModelAvailability;
import io.omnnu.finbot.application.configuration.dto.ModelCapabilitySources;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class OpenAiModelCapabilityParser {
    private static final BigInteger LONG_MAXIMUM = BigInteger.valueOf(Long.MAX_VALUE);

    private OpenAiModelCapabilityParser() {
    }

    static DiscoveredModelCapability parse(JsonNode item, ModelCatalogWarningCollector warnings) {
        if (item.isTextual()) {
            return validModelName(item.asText(), warnings);
        }
        if (!item.isObject()) {
            warnings.add("MODEL_CATALOG_ITEM_INVALID", null, "模型目录项必须是字符串或对象");
            return null;
        }
        var modelName = item.path("id").isTextual() ? item.path("id").asText() : "";
        var unknown = validModelName(modelName, warnings);
        if (unknown == null) {
            return null;
        }
        if (item.has("capabilities") && !item.path("capabilities").isObject()) {
            warnings.add(
                    "MODEL_CAPABILITY_FIELD_INVALID",
                    unknown.modelName(),
                    "capabilities 必须是对象，已忽略该扩展字段");
        }

        var availability = availability(item, unknown.modelName(), warnings);
        var supportedParameters = supportedParameters(item, unknown.modelName(), warnings);
        var protocols = protocols(item, unknown.modelName(), warnings);
        var reasoning = reasoning(item, unknown.modelName(), warnings);
        var tokenLimitStyles = tokenLimitStyles(item, unknown.modelName(), warnings, supportedParameters);
        var streaming = support(
                item,
                unknown.modelName(),
                warnings,
                supportedParameters,
                Set.of("stream", "streaming"),
                "streaming",
                "supports_streaming",
                "supportsStreaming");
        var tools = support(
                item,
                unknown.modelName(),
                warnings,
                supportedParameters,
                Set.of("tools", "tool_choice", "tool_calls"),
                "tools",
                "supports_tools",
                "supportsTools");
        var multimodal = multimodal(item, unknown.modelName(), warnings);
        var maximumContextTokens = positiveLong(
                item,
                unknown.modelName(),
                warnings,
                "maximumContextTokens",
                "max_context_tokens",
                "maximum_context_tokens",
                "context_window",
                "context_length");
        var maximumInputTokens = positiveLong(
                item,
                unknown.modelName(),
                warnings,
                "maximumInputTokens",
                "max_input_tokens",
                "maximum_input_tokens");
        var maximumOutputTokens = positiveLong(
                item,
                unknown.modelName(),
                warnings,
                "maximumOutputTokens",
                "max_output_tokens",
                "maximum_output_tokens");

        return new DiscoveredModelCapability(
                unknown.modelName(),
                availability.value(),
                protocols.value(),
                reasoning.value(),
                tokenLimitStyles.value(),
                streaming.value(),
                tools.value(),
                multimodal.value(),
                maximumContextTokens.value(),
                maximumInputTokens.value(),
                maximumOutputTokens.value(),
                new ModelCapabilitySources(
                        availability.source(),
                        protocols.source(),
                        reasoning.source(),
                        tokenLimitStyles.source(),
                        streaming.source(),
                        tools.source(),
                        multimodal.source(),
                        maximumContextTokens.source(),
                        maximumInputTokens.source(),
                        maximumOutputTokens.source()));
    }

    private static DiscoveredModelCapability validModelName(String value, ModelCatalogWarningCollector warnings) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 160) {
            warnings.add("MODEL_CATALOG_ITEM_INVALID", null, "模型目录项缺少有效的 id");
            return null;
        }
        return DiscoveredModelCapability.unknown(normalized);
    }

    private static Observed<ModelAvailability> availability(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        var node = field(item, "availability", "available", "status");
        if (absent(node)) {
            return Observed.unknown(ModelAvailability.UNKNOWN);
        }
        if (node.isBoolean()) {
            return Observed.declared(node.asBoolean()
                    ? ModelAvailability.AVAILABLE
                    : ModelAvailability.UNAVAILABLE);
        }
        if (node.isTextual()) {
            var value = normalized(node.asText());
            var availability = switch (value) {
                case "AVAILABLE", "READY", "ACTIVE", "HEALTHY", "ONLINE" -> ModelAvailability.AVAILABLE;
                case "DEGRADED", "LIMITED", "THROTTLED" -> ModelAvailability.DEGRADED;
                case "UNAVAILABLE", "DISABLED", "OFFLINE", "FAILED", "ERROR" -> ModelAvailability.UNAVAILABLE;
                default -> null;
            };
            if (availability != null) {
                return Observed.declared(availability);
            }
        }
        warnings.add(
                "MODEL_CAPABILITY_VALUE_UNKNOWN",
                modelName,
                "无法识别 availability，已按 UNKNOWN 处理");
        return Observed.unknown(ModelAvailability.UNKNOWN);
    }

    private static Observed<Set<AiProtocol>> protocols(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        var node = field(item, "supported_protocols", "supportedProtocols", "protocols");
        if (absent(node)) {
            var contracts = field(item, "supported_parameters", "supportedParameters");
            if (!absent(contracts) && contracts.isObject()) {
                var result = EnumSet.noneOf(AiProtocol.class);
                contracts.fieldNames().forEachRemaining(name -> {
                    var normalized = normalizedProtocol(name);
                    if (normalized.endsWith("CHATCOMPLETIONS") || "CHAT".equals(normalized)) {
                        result.add(AiProtocol.CHAT);
                    } else if (normalized.endsWith("RESPONSES") || "RESPONSE".equals(normalized)) {
                        result.add(AiProtocol.RESPONSES);
                    }
                });
                if (!result.isEmpty()) {
                    return Observed.declared(Set.copyOf(result));
                }
            }
            return Observed.unknown(Set.of());
        }
        var values = stringValues(node, modelName, "supported_protocols", warnings);
        var result = EnumSet.noneOf(AiProtocol.class);
        for (var value : values.value()) {
            var normalized = normalizedProtocol(value);
            if (normalized.endsWith("CHATCOMPLETIONS") || "CHAT".equals(normalized)) {
                result.add(AiProtocol.CHAT);
            } else if (normalized.endsWith("RESPONSES") || "RESPONSE".equals(normalized)) {
                result.add(AiProtocol.RESPONSES);
            } else {
                warnings.add(
                        "MODEL_CAPABILITY_VALUE_UNKNOWN",
                        modelName,
                        "无法识别 supported_protocols 值：" + safeValue(value));
            }
        }
        return result.isEmpty()
                ? Observed.unknown(Set.of())
                : Observed.declared(Set.copyOf(result));
    }

    private static Observed<ReasoningEffort> reasoning(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        var maximum = field(
                item,
                "maximum_reasoning_effort",
                "maximumReasoningEffort",
                "max_reasoning_effort",
                "maxReasoningEffort");
        if (!absent(maximum)) {
            var parsed = reasoningEffort(maximum);
            if (parsed != null) {
                return Observed.declared(parsed);
            }
            warnings.add(
                    "MODEL_CAPABILITY_VALUE_UNKNOWN",
                    modelName,
                    "无法识别 maximum_reasoning_effort，已按 UNKNOWN 处理");
            return Observed.unknown(null);
        }
        var efforts = stringValues(
                field(item, "reasoning_efforts", "reasoningEfforts"),
                modelName,
                "reasoning_efforts",
                warnings);
        if (efforts.source() == CapabilitySource.UNKNOWN) {
            var reasoning = field(item, "reasoning");
            if (!absent(reasoning) && reasoning.isObject()) {
                var supported = reasoning.path("supported");
                if (supported.isBoolean() && !supported.asBoolean()) {
                    return Observed.declared(ReasoningEffort.NONE);
                }
                efforts = stringValues(reasoning.path("levels"), modelName, "reasoning.levels", warnings);
            }
        }
        ReasoningEffort highest = null;
        for (var value : efforts.value()) {
            var parsed = reasoningEffort(value);
            if (parsed == null) {
                warnings.add(
                        "MODEL_CAPABILITY_VALUE_UNKNOWN",
                        modelName,
                        "无法识别 reasoning_efforts 值：" + safeValue(value));
            } else if (highest == null || parsed.ordinal() > highest.ordinal()) {
                highest = parsed;
            }
        }
        return highest == null ? Observed.unknown(null) : Observed.declared(highest);
    }

    private static ReasoningEffort reasoningEffort(JsonNode value) {
        return value.isTextual() ? reasoningEffort(value.asText()) : null;
    }

    private static ReasoningEffort reasoningEffort(String value) {
        var normalized = normalized(value).replace("-", "_").replace(" ", "_");
        normalized = switch (normalized) {
            case "X_HIGH" -> "XHIGH";
            case "PROVIDERDEFAULT" -> "PROVIDER_DEFAULT";
            default -> normalized;
        };
        try {
            return ReasoningEffort.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Observed<Set<TokenLimitParameterStyle>> tokenLimitStyles(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings,
            Observed<List<String>> supportedParameters) {
        var explicit = field(item, "token_limit_parameter_styles", "tokenLimitParameterStyles");
        if (!absent(explicit)) {
            if (explicit.isArray() && explicit.isEmpty()) {
                return Observed.declared(Set.of(TokenLimitParameterStyle.NONE));
            }
            var values = stringValues(explicit, modelName, "token_limit_parameter_styles", warnings);
            if (values.source() == CapabilitySource.UNKNOWN) {
                return Observed.unknown(Set.of());
            }
            var result = parseTokenLimitStyles(values.value(), modelName, warnings);
            return result.isEmpty()
                    ? Observed.unknown(Set.of())
                    : Observed.declared(Set.copyOf(result));
        }
        if (supportedParameters.source() == CapabilitySource.UNKNOWN) {
            return Observed.unknown(Set.of());
        }
        var result = parseTokenLimitStyles(supportedParameters.value(), modelName, warnings);
        return result.isEmpty()
                ? Observed.declared(Set.of(TokenLimitParameterStyle.NONE))
                : Observed.declared(Set.copyOf(result));
    }

    private static Set<TokenLimitParameterStyle> parseTokenLimitStyles(
            List<String> values,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        var result = EnumSet.noneOf(TokenLimitParameterStyle.class);
        for (var value : values) {
            var parsed = tokenLimitStyle(value);
            if (parsed != null) {
                result.add(parsed);
            } else if (normalizedParameter(value).contains("token")) {
                warnings.add(
                        "MODEL_CAPABILITY_VALUE_UNKNOWN",
                        modelName,
                        "无法识别 Token 上限参数：" + safeValue(value));
            }
        }
        return result;
    }

    private static TokenLimitParameterStyle tokenLimitStyle(String value) {
        return switch (normalized(value)) {
            case "PROTOCOL_DEFAULT" -> TokenLimitParameterStyle.PROTOCOL_DEFAULT;
            case "MAX_TOKENS" -> TokenLimitParameterStyle.MAX_TOKENS;
            case "MAX_COMPLETION_TOKENS" -> TokenLimitParameterStyle.MAX_COMPLETION_TOKENS;
            case "MAX_OUTPUT_TOKENS" -> TokenLimitParameterStyle.MAX_OUTPUT_TOKENS;
            case "NONE" -> TokenLimitParameterStyle.NONE;
            default -> null;
        };
    }

    private static Observed<CapabilitySupport> support(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings,
            Observed<List<String>> supportedParameters,
            Set<String> parameterNames,
            String... aliases) {
        var direct = field(item, aliases);
        if (!absent(direct)) {
            var parsed = supportValue(direct);
            if (parsed != null) {
                return Observed.declared(parsed);
            }
            warnings.add(
                    "MODEL_CAPABILITY_FIELD_INVALID",
                    modelName,
                    aliases[0] + " 必须是布尔值或支持状态");
            return Observed.unknown(CapabilitySupport.UNKNOWN);
        }
        var parameterSupported = supportedParameters.value().stream()
                .map(OpenAiModelCapabilityParser::normalizedParameter)
                .anyMatch(parameterNames::contains);
        return parameterSupported
                ? Observed.declared(CapabilitySupport.SUPPORTED)
                : Observed.unknown(CapabilitySupport.UNKNOWN);
    }

    private static CapabilitySupport supportValue(JsonNode node) {
        if (node.isObject() && node.path("supported").isBoolean()) {
            return node.path("supported").asBoolean()
                    ? CapabilitySupport.SUPPORTED
                    : CapabilitySupport.UNSUPPORTED;
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED;
        }
        if (!node.isTextual()) {
            return null;
        }
        return switch (normalized(node.asText())) {
            case "SUPPORTED", "AVAILABLE", "TRUE", "YES" -> CapabilitySupport.SUPPORTED;
            case "UNSUPPORTED", "UNAVAILABLE", "FALSE", "NO", "NONE" -> CapabilitySupport.UNSUPPORTED;
            case "UNKNOWN" -> CapabilitySupport.UNKNOWN;
            default -> null;
        };
    }

    private static Observed<CapabilitySupport> multimodal(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        var direct = field(item, "multimodal", "supports_multimodal", "supportsMultimodal");
        if (!absent(direct)) {
            if (direct.isObject()) {
                var declaredSupport = supportValue(direct);
                if (declaredSupport != null) {
                    return Observed.declared(declaredSupport);
                }
                var inputs = stringValues(
                        field(direct, "input", "inputs", "input_modalities", "inputModalities"),
                        modelName,
                        "multimodal.input",
                        warnings);
                var outputs = stringValues(
                        field(direct, "output", "outputs", "output_modalities", "outputModalities"),
                        modelName,
                        "multimodal.output",
                        warnings);
                var modalitySupport = modalitySupport(inputs, outputs);
                if (modalitySupport.source() != CapabilitySource.UNKNOWN) {
                    return modalitySupport;
                }
            }
            var parsed = supportValue(direct);
            if (parsed != null) {
                return Observed.declared(parsed);
            }
            warnings.add(
                    "MODEL_CAPABILITY_FIELD_INVALID",
                    modelName,
                    "multimodal 必须是布尔值或支持状态");
            return Observed.unknown(CapabilitySupport.UNKNOWN);
        }
        var inputs = stringValues(
                field(item, "input_modalities", "inputModalities"),
                modelName,
                "input_modalities",
                warnings);
        var outputs = stringValues(
                field(item, "output_modalities", "outputModalities"),
                modelName,
                "output_modalities",
                warnings);
        return modalitySupport(inputs, outputs);
    }

    private static Observed<CapabilitySupport> modalitySupport(
            Observed<List<String>> inputs,
            Observed<List<String>> outputs) {
        var inputKnown = inputs.source() != CapabilitySource.UNKNOWN;
        var outputKnown = outputs.source() != CapabilitySource.UNKNOWN;
        var nonText = java.util.stream.Stream.concat(inputs.value().stream(), outputs.value().stream())
                .map(OpenAiModelCapabilityParser::normalizedParameter)
                .anyMatch(value -> !"text".equals(value));
        if (nonText) {
            return Observed.declared(CapabilitySupport.SUPPORTED);
        }
        return inputKnown && outputKnown
                ? Observed.declared(CapabilitySupport.UNSUPPORTED)
                : Observed.unknown(CapabilitySupport.UNKNOWN);
    }

    private static Observed<Long> positiveLong(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings,
            String label,
            String... aliases) {
        var node = field(item, aliases);
        if (absent(node)) {
            return Observed.unknown(null);
        }
        try {
            var value = node.isIntegralNumber()
                    ? node.bigIntegerValue()
                    : new BigInteger(node.isTextual() ? node.asText().strip() : "");
            if (value.signum() > 0 && value.compareTo(LONG_MAXIMUM) <= 0) {
                return Observed.declared(value.longValueExact());
            }
        } catch (NumberFormatException | ArithmeticException exception) {
            // Converted into a structured catalog warning below.
        }
        warnings.add(
                "MODEL_CAPABILITY_FIELD_INVALID",
                modelName,
                label + " 必须是正整数");
        return Observed.unknown(null);
    }

    private static Observed<List<String>> stringValues(
            JsonNode node,
            String modelName,
            String fieldName,
            ModelCatalogWarningCollector warnings) {
        if (absent(node)) {
            return Observed.unknown(List.of());
        }
        if (node.isTextual()) {
            return Observed.declared(List.of(node.asText()));
        }
        if (!node.isArray()) {
            warnings.add(
                    "MODEL_CAPABILITY_FIELD_INVALID",
                    modelName,
                    fieldName + " 必须是字符串或字符串数组");
            return Observed.unknown(List.of());
        }
        var values = new ArrayList<String>();
        var invalid = false;
        for (var value : node) {
            if (value.isTextual()) {
                values.add(value.asText());
            } else {
                invalid = true;
            }
        }
        if (invalid) {
            warnings.add(
                    "MODEL_CAPABILITY_FIELD_INVALID",
                    modelName,
                    fieldName + " 包含非字符串项，已忽略");
        }
        return values.isEmpty()
                ? Observed.unknown(List.of())
                : Observed.declared(List.copyOf(values));
    }

    private static Observed<List<String>> supportedParameters(
            JsonNode item,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        var node = field(item, "supported_parameters", "supportedParameters");
        if (absent(node)) {
            return Observed.unknown(List.of());
        }
        if (!node.isObject()) {
            return stringValues(node, modelName, "supported_parameters", warnings);
        }
        var values = new ArrayList<String>();
        var invalid = false;
        var explicitlyEmpty = false;
        for (var entry : node.properties()) {
            if (entry.getValue().isArray() && entry.getValue().isEmpty()) {
                explicitlyEmpty = true;
                continue;
            }
            var decoded = stringValues(
                    entry.getValue(),
                    modelName,
                    "supported_parameters." + entry.getKey(),
                    warnings);
            if (decoded.source() == CapabilitySource.UNKNOWN && !entry.getValue().isArray()) {
                invalid = true;
            }
            values.addAll(decoded.value());
        }
        if (invalid) {
            warnings.add(
                    "MODEL_CAPABILITY_FIELD_INVALID",
                    modelName,
                    "supported_parameters 包含无效的协议参数列表，已忽略");
        }
        return !values.isEmpty() || explicitlyEmpty
                ? Observed.declared(List.copyOf(values))
                : Observed.unknown(List.of());
    }

    private static JsonNode field(JsonNode item, String... aliases) {
        for (var alias : aliases) {
            if (item.has(alias)) {
                return item.get(alias);
            }
        }
        var capabilities = item.path("capabilities");
        if (capabilities.isObject()) {
            for (var alias : aliases) {
                if (capabilities.has(alias)) {
                    return capabilities.get(alias);
                }
            }
        }
        return null;
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String normalizedParameter(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String normalizedProtocol(String value) {
        return normalized(value).replace("_", "").replace("-", "").replace("/", "");
    }

    private static String safeValue(String value) {
        var normalized = value == null ? "" : value.strip();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private record Observed<T>(T value, CapabilitySource source) {
        private static <T> Observed<T> unknown(T value) {
            return new Observed<>(value, CapabilitySource.UNKNOWN);
        }

        private static <T> Observed<T> declared(T value) {
            return new Observed<>(value, CapabilitySource.DECLARED);
        }
    }

}
