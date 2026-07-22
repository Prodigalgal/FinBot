package io.omnnu.finbot.infrastructure.quant.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.domain.quant.ArtifactKind;
import io.omnnu.finbot.domain.quant.MetricUnit;
import io.omnnu.finbot.domain.quant.QuantMetric;
import io.omnnu.finbot.domain.quant.QuantResearchEvent;
import io.omnnu.finbot.domain.quant.QuantResearchEventId;
import io.omnnu.finbot.domain.quant.QuantResearchRequest;
import io.omnnu.finbot.domain.quant.ResearchAcceptedEvent;
import io.omnnu.finbot.domain.quant.ResearchArtifact;
import io.omnnu.finbot.domain.quant.ResearchArtifactEvent;
import io.omnnu.finbot.domain.quant.ResearchCompletedEvent;
import io.omnnu.finbot.domain.quant.ResearchErrorCode;
import io.omnnu.finbot.domain.quant.ResearchFailedEvent;
import io.omnnu.finbot.domain.quant.ResearchParameter;
import io.omnnu.finbot.domain.quant.ResearchProgressEvent;
import io.omnnu.finbot.domain.quant.ResearchRunId;
import io.omnnu.finbot.domain.quant.ResearchStage;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class QuantResearchHttpCodec {
    private static final Set<String> ACCEPTED_FIELDS = eventFields("engineVersion", "inputFingerprint");
    private static final Set<String> PROGRESS_FIELDS = eventFields(
            "stage", "progressBasisPoints", "safeSummary");
    private static final Set<String> ARTIFACT_EVENT_FIELDS = eventFields("artifact");
    private static final Set<String> COMPLETED_FIELDS = eventFields(
            "metrics", "artifacts", "observationCount", "resultFingerprint");
    private static final Set<String> FAILED_FIELDS = eventFields("code", "safeMessage", "retryable");
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "kind", "uri", "sha256Hex", "mediaType", "byteSize");
    private static final Set<String> METRIC_FIELDS = Set.of("name", "value", "unit");

    private final ObjectMapper objectMapper;

    public QuantResearchHttpCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encodeRequest(QuantResearchRequest request) {
        Objects.requireNonNull(request, "request");
        var root = objectMapper.createObjectNode();
        root.put("researchRunId", request.researchRunId().value());
        root.put("workflowRunId", request.workflowRunId().value());
        root.put("requestedAt", request.requestedAt().toString());
        var specification = root.putObject("specification");
        var value = request.specification();
        specification.put("kind", value.kind().name());
        var instruments = specification.putArray("instruments");
        value.instruments().forEach(instrument -> {
            var node = instruments.addObject();
            node.put("exchange", instrument.exchange().name());
            node.put("environment", instrument.environment().name());
            node.put("symbol", instrument.symbol().value());
            node.put("marketType", instrument.marketType().name());
            node.put("quoteCurrency", instrument.quoteCurrency());
        });
        var timeRange = specification.putObject("timeRange");
        timeRange.put("startInclusive", value.timeRange().startInclusive().toString());
        timeRange.put("endExclusive", value.timeRange().endExclusive().toString());
        writeArtifact(specification.putObject("marketData"), value.marketData());
        specification.put("strategyId", value.strategyId());
        specification.put("strategyVersion", value.strategyVersion());
        var parameters = specification.putArray("parameters");
        value.parameters().forEach(parameter -> writeParameter(parameters.addObject(), parameter));
        specification.put("deterministicSeed", value.deterministicSeed());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode quant research request", exception);
        }
    }

    public QuantResearchEvent decodeEvent(String payload) {
        try {
            var root = requireObject(objectMapper.readTree(payload), "event");
            var eventType = requiredText(root, "eventType");
            var eventId = new QuantResearchEventId(requiredText(root, "eventId"));
            var runId = new ResearchRunId(requiredText(root, "researchRunId"));
            var sequence = requiredLong(root, "sequence");
            var occurredAt = Instant.parse(requiredText(root, "occurredAt"));
            return switch (eventType) {
                case "research.accepted" -> {
                    requireExactFields(root, ACCEPTED_FIELDS, eventType);
                    yield new ResearchAcceptedEvent(
                            eventId,
                            runId,
                            sequence,
                            occurredAt,
                            requiredText(root, "engineVersion"),
                            requiredText(root, "inputFingerprint"));
                }
                case "research.progress" -> {
                    requireExactFields(root, PROGRESS_FIELDS, eventType);
                    yield new ResearchProgressEvent(
                            eventId,
                            runId,
                            sequence,
                            occurredAt,
                            enumValue(ResearchStage.class, root, "stage"),
                            requiredInt(root, "progressBasisPoints"),
                            requiredText(root, "safeSummary"));
                }
                case "research.artifact" -> {
                    requireExactFields(root, ARTIFACT_EVENT_FIELDS, eventType);
                    yield new ResearchArtifactEvent(
                            eventId,
                            runId,
                            sequence,
                            occurredAt,
                            readArtifact(root.get("artifact")));
                }
                case "research.completed" -> {
                    requireExactFields(root, COMPLETED_FIELDS, eventType);
                    yield new ResearchCompletedEvent(
                            eventId,
                            runId,
                            sequence,
                            occurredAt,
                            readMetrics(root.get("metrics")),
                            readArtifacts(root.get("artifacts")),
                            requiredLong(root, "observationCount"),
                            requiredText(root, "resultFingerprint"));
                }
                case "research.failed" -> {
                    requireExactFields(root, FAILED_FIELDS, eventType);
                    yield new ResearchFailedEvent(
                            eventId,
                            runId,
                            sequence,
                            occurredAt,
                            enumValue(ResearchErrorCode.class, root, "code"),
                            requiredText(root, "safeMessage"),
                            requiredBoolean(root, "retryable"));
                }
                default -> throw new IllegalArgumentException("Unknown quant research event type: " + eventType);
            };
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Quant research event is not valid JSON", exception);
        }
    }

    private static void writeParameter(ObjectNode node, ResearchParameter parameter) {
        node.put("name", parameter.name());
        node.put("valueType", parameter.valueType());
        switch (parameter) {
            case ResearchParameter.BooleanValue value -> node.put("value", value.value());
            case ResearchParameter.IntegerValue value -> node.put("value", value.value());
            case ResearchParameter.FloatingValue value -> node.put("value", value.value());
            case ResearchParameter.DecimalParameter value -> node.put("value", value.value().toPlainString());
            case ResearchParameter.TextValue value -> node.put("value", value.value());
        }
    }

    private static void writeArtifact(ObjectNode node, ResearchArtifact artifact) {
        node.put("kind", artifact.kind().name());
        node.put("uri", artifact.uri().toString());
        node.put("sha256Hex", artifact.sha256Hex());
        node.put("mediaType", artifact.mediaType());
        node.put("byteSize", artifact.byteSize());
    }

    private static ResearchArtifact readArtifact(JsonNode value) {
        var node = requireObject(value, "artifact");
        requireExactFields(node, ARTIFACT_FIELDS, "artifact");
        return new ResearchArtifact(
                enumValue(ArtifactKind.class, node, "kind"),
                URI.create(requiredText(node, "uri")),
                requiredText(node, "sha256Hex"),
                requiredText(node, "mediaType"),
                requiredLong(node, "byteSize"));
    }

    private static java.util.List<ResearchArtifact> readArtifacts(JsonNode value) {
        var array = requireArray(value, "artifacts");
        var artifacts = new ArrayList<ResearchArtifact>(array.size());
        array.forEach(node -> artifacts.add(readArtifact(node)));
        return java.util.List.copyOf(artifacts);
    }

    private static java.util.List<QuantMetric> readMetrics(JsonNode value) {
        var array = requireArray(value, "metrics");
        var metrics = new ArrayList<QuantMetric>(array.size());
        array.forEach(valueNode -> {
            var node = requireObject(valueNode, "metric");
            requireExactFields(node, METRIC_FIELDS, "metric");
            metrics.add(new QuantMetric(
                    requiredText(node, "name"),
                    requiredDouble(node, "value"),
                    enumValue(MetricUnit.class, node, "unit")));
        });
        return java.util.List.copyOf(metrics);
    }

    private static ObjectNode requireObject(JsonNode value, String field) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return object;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode requireArray(JsonNode value, String field) {
        if (!(value instanceof com.fasterxml.jackson.databind.node.ArrayNode array)) {
            throw new IllegalArgumentException(field + " must be a JSON array");
        }
        return array;
    }

    private static String requiredText(ObjectNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static long requiredLong(ObjectNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be a 64-bit integer");
        }
        return value.longValue();
    }

    private static int requiredInt(ObjectNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static double requiredDouble(ObjectNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return value.doubleValue();
    }

    private static boolean requiredBoolean(ObjectNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, ObjectNode node, String field) {
        try {
            return Enum.valueOf(type, requiredText(node, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " has an unsupported value", exception);
        }
    }

    private static Set<String> eventFields(String... payloadFields) {
        var fields = new HashSet<>(Set.of(
                "eventId", "researchRunId", "sequence", "occurredAt", "eventType"));
        fields.addAll(java.util.List.of(payloadFields));
        return Set.copyOf(fields);
    }

    private static void requireExactFields(ObjectNode node, Set<String> expected, String type) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(type + " fields do not match contract: " + actual);
        }
    }
}
