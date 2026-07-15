package io.omnnu.finbot.infrastructure.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.workflow.StructuredAiOutputParser;
import io.omnnu.finbot.domain.workflow.AgentClaim;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.research.ForecastSignal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class JacksonStructuredAiOutputParser implements StructuredAiOutputParser {
    private static final Set<String> AGENT_FIELDS = Set.of(
            "summary", "argument", "confidence", "claims", "evidence_refs",
            "challenges", "revision_notes");
    private static final Set<String> CHAIR_FIELDS = Set.of(
            "debate_summary", "major_disagreements", "missing_evidence", "verdicts",
            "confidence", "summary", "argument", "claims", "evidence_refs",
            "challenges", "revision_notes", "forecast");
    private static final Set<String> FORECAST_FIELDS = Set.of(
            "direction", "reference_price", "expected_low", "expected_high",
            "invalidation_price", "confidence", "thesis", "evidence_refs");

    private final ObjectMapper objectMapper;

    public JacksonStructuredAiOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public AgentMessageContent parseAgent(String output) {
        var root = parseObject(output);
        requireAllowedFields(root, AGENT_FIELDS);
        return new AgentMessageContent(
                requiredText(root, "summary"),
                requiredText(root, "argument"),
                optionalDecimal(root, "confidence"),
                claims(root.path("claims")),
                strings(root.path("evidence_refs")),
                strings(root.path("challenges")),
                strings(root.path("revision_notes")));
    }

    @Override
    public AgentMessageContent parseChair(String output) {
        var root = parseObject(output);
        requireAllowedFields(root, CHAIR_FIELDS);
        var summary = root.has("summary")
                ? requiredText(root, "summary")
                : joined(root.path("debate_summary"), "主席未返回辩论摘要");
        var argument = root.has("argument")
                ? requiredText(root, "argument")
                : verdictArgument(root.path("verdicts"));
        var parsedClaims = root.has("claims") ? claims(root.path("claims")) : verdictClaims(root.path("verdicts"));
        var challenges = root.has("challenges")
                ? strings(root.path("challenges"))
                : strings(root.path("major_disagreements"));
        var revisions = root.has("revision_notes")
                ? strings(root.path("revision_notes"))
                : strings(root.path("missing_evidence"));
        return new AgentMessageContent(
                summary,
                argument,
                optionalDecimal(root, "confidence"),
                parsedClaims,
                strings(root.path("evidence_refs")),
                challenges,
                revisions,
                forecast(root.path("forecast")));
    }

    private static ForecastSignal forecast(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("AI forecast must be an object or null");
        }
        requireAllowedFields((ObjectNode) node, FORECAST_FIELDS);
        var direction = ForecastDirection.valueOf(requiredText((ObjectNode) node, "direction"));
        var uncertain = direction == ForecastDirection.UNCERTAIN;
        return new ForecastSignal(
                direction,
                uncertain ? requireNullDecimal(node, "reference_price") : requiredDecimal(node, "reference_price"),
                uncertain ? requireNullDecimal(node, "expected_low") : requiredDecimal(node, "expected_low"),
                uncertain ? requireNullDecimal(node, "expected_high") : requiredDecimal(node, "expected_high"),
                optionalDecimal((ObjectNode) node, "invalidation_price"),
                requiredDecimal(node, "confidence"),
                requiredText((ObjectNode) node, "thesis"),
                strings(node.path("evidence_refs")));
    }

    private static BigDecimal requiredDecimal(JsonNode node, String fieldName) {
        var value = node.get(fieldName);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("AI forecast decimal is required: " + fieldName);
        }
        return value.decimalValue();
    }

    private static BigDecimal requireNullDecimal(JsonNode node, String fieldName) {
        var value = node.get(fieldName);
        if (value != null && !value.isNull()) {
            throw new IllegalArgumentException("Uncertain AI forecast field must be null: " + fieldName);
        }
        return null;
    }

    private ObjectNode parseObject(String output) {
        var normalized = Objects.requireNonNull(output, "output").strip();
        if (normalized.startsWith("```")) {
            var firstNewline = normalized.indexOf('\n');
            var closing = normalized.lastIndexOf("```");
            if (firstNewline >= 0 && closing > firstNewline) {
                normalized = normalized.substring(firstNewline + 1, closing).strip();
            }
        }
        try {
            var node = objectMapper.readTree(normalized);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new IllegalArgumentException("AI output root must be an object");
            }
            return objectNode;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI output is not valid JSON", exception);
        }
    }

    private static void requireAllowedFields(ObjectNode node, Set<String> allowed) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual)) {
            throw new IllegalArgumentException("AI output contains unsupported fields");
        }
    }

    private static String requiredText(ObjectNode node, String fieldName) {
        var value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("AI output field is required: " + fieldName);
        }
        return value.textValue();
    }

    private static BigDecimal optionalDecimal(ObjectNode node, String fieldName) {
        var value = node.get(fieldName);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return node.textValue().isBlank() ? List.of() : List.of(node.textValue());
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("AI output string collection must be an array");
        }
        var values = new ArrayList<String>();
        node.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException("AI output string collection contains an invalid value");
            }
            values.add(value.textValue());
        });
        return List.copyOf(values);
    }

    private static List<AgentClaim> claims(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("AI claims must be an array");
        }
        var claims = new ArrayList<AgentClaim>();
        node.forEach(value -> {
            if (value.isTextual()) {
                claims.add(new AgentClaim(value.textValue(), List.of()));
            } else if (value.isObject()) {
                var statement = value.path("statement");
                if (!statement.isTextual() || statement.textValue().isBlank()) {
                    throw new IllegalArgumentException("AI claim statement is required");
                }
                claims.add(new AgentClaim(
                        statement.textValue(),
                        strings(value.path("evidence_refs"))));
            } else {
                throw new IllegalArgumentException("AI claim has an invalid shape");
            }
        });
        return List.copyOf(claims);
    }

    private static String joined(JsonNode node, String fallback) {
        var values = strings(node);
        return values.isEmpty() ? fallback : String.join("；", values);
    }

    private static String verdictArgument(JsonNode verdicts) {
        if (!verdicts.isArray() || verdicts.isEmpty()) {
            return "主席未形成可执行结论，结果保留为继续观察。";
        }
        var values = new ArrayList<String>();
        verdicts.forEach(verdict -> values.add(verdict.isTextual()
                ? verdict.textValue()
                : verdict.toString()));
        return String.join("；", values);
    }

    private static List<AgentClaim> verdictClaims(JsonNode verdicts) {
        if (!verdicts.isArray()) {
            return List.of();
        }
        var claims = new ArrayList<AgentClaim>();
        verdicts.forEach(verdict -> {
            if (verdict.isTextual()) {
                claims.add(new AgentClaim(verdict.textValue(), List.of()));
            } else if (verdict.isObject()) {
                var summary = verdict.path("summary").asText(verdict.toString());
                claims.add(new AgentClaim(summary, strings(verdict.path("evidence_refs"))));
            }
        });
        return List.copyOf(claims);
    }
}
