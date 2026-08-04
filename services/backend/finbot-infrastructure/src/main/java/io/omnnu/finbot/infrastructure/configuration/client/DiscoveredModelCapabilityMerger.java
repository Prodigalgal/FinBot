package io.omnnu.finbot.infrastructure.configuration.client;

import io.omnnu.finbot.application.configuration.dto.CapabilitySource;
import io.omnnu.finbot.application.configuration.dto.CapabilitySupport;
import io.omnnu.finbot.application.configuration.dto.DiscoveredModelCapability;
import io.omnnu.finbot.application.configuration.dto.ModelAvailability;
import io.omnnu.finbot.application.configuration.dto.ModelCapabilitySources;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import java.util.HashSet;
import java.util.Set;

final class DiscoveredModelCapabilityMerger {
    private DiscoveredModelCapabilityMerger() {
    }

    static DiscoveredModelCapability merge(
            DiscoveredModelCapability first,
            DiscoveredModelCapability second,
            ModelCatalogWarningCollector warnings) {
        var modelName = first.modelName();
        var availability = mergeAvailability(
                first.availability(), second.availability(), modelName, warnings);
        var protocols = conservativeSet(
                first.supportedProtocols(), second.supportedProtocols(), modelName, "supportedProtocols", warnings);
        var reasoning = lowerReasoning(
                first.maximumReasoningEffort(), second.maximumReasoningEffort(), modelName, warnings);
        var tokenStyles = conservativeTokenStyles(
                first.tokenLimitParameterStyles(),
                second.tokenLimitParameterStyles(),
                modelName,
                warnings);
        var streaming = conservativeSupport(first.streaming(), second.streaming(), modelName, "streaming", warnings);
        var tools = conservativeSupport(first.tools(), second.tools(), modelName, "tools", warnings);
        var multimodal = conservativeSupport(first.multimodal(), second.multimodal(), modelName, "multimodal", warnings);
        var maximumContextTokens = lowerLimit(
                first.maximumContextTokens(), second.maximumContextTokens(), modelName, "maximumContextTokens", warnings);
        var maximumInputTokens = lowerLimit(
                first.maximumInputTokens(), second.maximumInputTokens(), modelName, "maximumInputTokens", warnings);
        var maximumOutputTokens = lowerLimit(
                first.maximumOutputTokens(), second.maximumOutputTokens(), modelName, "maximumOutputTokens", warnings);
        return new DiscoveredModelCapability(
                modelName,
                availability,
                protocols,
                reasoning,
                tokenStyles,
                streaming,
                tools,
                multimodal,
                maximumContextTokens,
                maximumInputTokens,
                maximumOutputTokens,
                new ModelCapabilitySources(
                        source(availability != ModelAvailability.UNKNOWN, first.sources().availability(), second.sources().availability()),
                        source(!protocols.isEmpty(), first.sources().supportedProtocols(), second.sources().supportedProtocols()),
                        source(reasoning != null, first.sources().maximumReasoningEffort(), second.sources().maximumReasoningEffort()),
                        source(!tokenStyles.isEmpty(), first.sources().tokenLimitParameterStyles(), second.sources().tokenLimitParameterStyles()),
                        source(streaming != CapabilitySupport.UNKNOWN, first.sources().streaming(), second.sources().streaming()),
                        source(tools != CapabilitySupport.UNKNOWN, first.sources().tools(), second.sources().tools()),
                        source(multimodal != CapabilitySupport.UNKNOWN, first.sources().multimodal(), second.sources().multimodal()),
                        source(maximumContextTokens != null, first.sources().maximumContextTokens(), second.sources().maximumContextTokens()),
                        source(maximumInputTokens != null, first.sources().maximumInputTokens(), second.sources().maximumInputTokens()),
                        source(maximumOutputTokens != null, first.sources().maximumOutputTokens(), second.sources().maximumOutputTokens())));
    }

    private static ModelAvailability mergeAvailability(
            ModelAvailability first,
            ModelAvailability second,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        if (first == ModelAvailability.UNKNOWN) {
            return second;
        }
        if (second == ModelAvailability.UNKNOWN) {
            return first;
        }
        if (first != second) {
            duplicateConflict(modelName, "availability", warnings);
        }
        return availabilitySeverity(first) >= availabilitySeverity(second) ? first : second;
    }

    private static int availabilitySeverity(ModelAvailability value) {
        return switch (value) {
            case UNKNOWN -> 0;
            case AVAILABLE -> 1;
            case DEGRADED -> 2;
            case UNAVAILABLE -> 3;
        };
    }

    private static <T> Set<T> conservativeSet(
            Set<T> first,
            Set<T> second,
            String modelName,
            String fieldName,
            ModelCatalogWarningCollector warnings) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        var intersection = new HashSet<>(first);
        intersection.retainAll(second);
        if (intersection.isEmpty()) {
            warnings.add(
                    "MODEL_CAPABILITY_DUPLICATE_CONFLICT",
                    modelName,
                    "重复模型的 " + fieldName + " 声明冲突，已降级为 UNKNOWN");
            return Set.of();
        }
        return Set.copyOf(intersection);
    }

    private static ReasoningEffort lowerReasoning(
            ReasoningEffort first,
            ReasoningEffort second,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        if (first != second) {
            duplicateConflict(modelName, "maximumReasoningEffort", warnings);
        }
        return first.ordinal() <= second.ordinal() ? first : second;
    }

    private static Set<TokenLimitParameterStyle> conservativeTokenStyles(
            Set<TokenLimitParameterStyle> first,
            Set<TokenLimitParameterStyle> second,
            String modelName,
            ModelCatalogWarningCollector warnings) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        var intersection = new HashSet<>(first);
        intersection.retainAll(second);
        if (!intersection.isEmpty()) {
            return Set.copyOf(intersection);
        }
        warnings.add(
                "MODEL_CAPABILITY_DUPLICATE_CONFLICT",
                modelName,
                "重复模型的 tokenLimitParameterStyles 声明冲突，已采用保守值");
        return first.contains(TokenLimitParameterStyle.NONE) || second.contains(TokenLimitParameterStyle.NONE)
                ? Set.of(TokenLimitParameterStyle.NONE)
                : Set.of();
    }

    private static CapabilitySupport conservativeSupport(
            CapabilitySupport first,
            CapabilitySupport second,
            String modelName,
            String fieldName,
            ModelCatalogWarningCollector warnings) {
        if (first == CapabilitySupport.UNKNOWN) {
            return second;
        }
        if (second == CapabilitySupport.UNKNOWN || first == second) {
            return first;
        }
        warnings.add(
                "MODEL_CAPABILITY_DUPLICATE_CONFLICT",
                modelName,
                "重复模型的 " + fieldName + " 声明冲突，已按不支持处理");
        return CapabilitySupport.UNSUPPORTED;
    }

    private static Long lowerLimit(
            Long first,
            Long second,
            String modelName,
            String fieldName,
            ModelCatalogWarningCollector warnings) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        if (!first.equals(second)) {
            duplicateConflict(modelName, fieldName, warnings);
        }
        return Math.min(first, second);
    }

    private static void duplicateConflict(
            String modelName,
            String fieldName,
            ModelCatalogWarningCollector warnings) {
        warnings.add(
                "MODEL_CAPABILITY_DUPLICATE_CONFLICT",
                modelName,
                "重复模型的 " + fieldName + " 声明冲突，已采用保守值");
    }

    private static CapabilitySource source(boolean known, CapabilitySource first, CapabilitySource second) {
        if (!known) {
            return CapabilitySource.UNKNOWN;
        }
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
