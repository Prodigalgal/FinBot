package io.omnnu.finbot.infrastructure.workflow.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.workflow.port.out.StructuredAiOutputParser;
import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.application.workflow.dto.ParsedDebateArtifact;
import io.omnnu.finbot.application.workflow.port.out.SdbScaOutputParser;
import io.omnnu.finbot.application.workflow.port.out.SdbScaDocumentCodec;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.consensus.SchulzeDetailedResult;
import io.omnnu.finbot.domain.consensus.SchulzeOrientationSnapshot;
import io.omnnu.finbot.domain.workflow.AgentClaim;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.research.ForecastSignal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class JacksonStructuredAiOutputParser
        implements StructuredAiOutputParser, SdbScaOutputParser, SdbScaDocumentCodec {
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
    private static final Set<String> SDB_ARTIFACT_FIELDS = Set.of(
            "summary", "argument", "confidence", "claims", "evidence_refs",
            "challenges", "revision_notes", "forecast");
    private static final Set<String> BALLOT_FIELDS = Set.of("preference_tiers");

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

    @Override
    public ParsedDebateArtifact parseProposal(String output) {
        return parseSdbArtifact(output, true);
    }

    @Override
    public ParsedDebateArtifact parseCritique(String output) {
        return parseSdbArtifact(output, false);
    }

    @Override
    public ParsedDebateArtifact parseRevision(String output) {
        return parseSdbArtifact(output, true);
    }

    @Override
    public ParsedConsensusBallot parseBallot(
            String output,
            LogicalRoleKey logicalRoleKey,
            BallotOrientation orientation,
            List<AnonymousCandidateId> expectedCandidates) {
        var root = parseObject(output);
        requireAllowedFields(root, BALLOT_FIELDS);
        var tiersNode = root.path("preference_tiers");
        if (!tiersNode.isArray() || tiersNode.isEmpty()) {
            throw new IllegalArgumentException("AI ballot preference_tiers must be a non-empty array");
        }
        var tiers = new ArrayList<List<AnonymousCandidateId>>();
        tiersNode.forEach(tierNode -> {
            if (!tierNode.isArray() || tierNode.isEmpty()) {
                throw new IllegalArgumentException("AI ballot tier must be a non-empty array");
            }
            var tier = new ArrayList<AnonymousCandidateId>();
            tierNode.forEach(candidateNode -> {
                if (!candidateNode.isTextual()) {
                    throw new IllegalArgumentException("AI ballot candidate must be a string");
                }
                tier.add(new AnonymousCandidateId(candidateNode.textValue()));
            });
            tiers.add(List.copyOf(tier));
        });
        var preference = AnonymousPreferenceBallot.of(logicalRoleKey, orientation, tiers);
        if (!preference.candidates().equals(Set.copyOf(expectedCandidates))) {
            throw new IllegalArgumentException("AI ballot must rank every expected anonymous candidate once");
        }
        return new ParsedConsensusBallot(canonicalJson(root), preference);
    }

    @Override
    public String encodeCandidateRanking(List<AnonymousCandidateId> candidates) {
        return writeValue(candidates.stream().map(AnonymousCandidateId::value).toList());
    }

    @Override
    public String encodePairwiseMatrix(SchulzeDetailedResult result) {
        return encodeMatrices(
                result,
                snapshot -> snapshot.pairwiseMatrix().toArray());
    }

    @Override
    public String encodeStrongestPaths(SchulzeDetailedResult result) {
        return encodeMatrices(
                result,
                snapshot -> snapshot.strongestPathMatrix().toArray());
    }

    @Override
    public String encodeForecast(ForecastSignal forecast) {
        if (forecast == null) {
            return null;
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("direction", forecast.direction().name());
        value.put("reference_price", forecast.referencePrice());
        value.put("expected_low", forecast.expectedLow());
        value.put("expected_high", forecast.expectedHigh());
        value.put("invalidation_price", forecast.invalidationPrice());
        value.put("confidence", forecast.confidence());
        value.put("thesis", forecast.thesis());
        value.put("evidence_refs", forecast.evidenceReferences());
        return writeValue(value);
    }

    private ParsedDebateArtifact parseSdbArtifact(String output, boolean allowForecast) {
        var root = parseObject(output);
        requireAllowedFields(root, SDB_ARTIFACT_FIELDS);
        if (!allowForecast && root.has("forecast") && !root.path("forecast").isNull()) {
            throw new IllegalArgumentException("AI critique must not contain a forecast");
        }
        var content = new AgentMessageContent(
                requiredText(root, "summary"),
                requiredText(root, "argument"),
                optionalDecimal(root, "confidence"),
                claims(root.path("claims")),
                strings(root.path("evidence_refs")),
                strings(root.path("challenges")),
                strings(root.path("revision_notes")),
                allowForecast ? forecast(root.path("forecast")) : null);
        return new ParsedDebateArtifact(canonicalJson(root), content);
    }

    private String canonicalJson(JsonNode node) {
        return writeValue(node);
    }

    private String encodeMatrices(
            SchulzeDetailedResult result,
            java.util.function.Function<SchulzeOrientationSnapshot, int[][]> matrix) {
        Objects.requireNonNull(result, "result");
        var value = new LinkedHashMap<String, Object>();
        value.put("candidate_order", result.forward().candidateOrder().stream()
                .map(AnonymousCandidateId::value)
                .toList());
        value.put("forward_status", result.forward().status().name());
        value.put("reversed_status", result.reversed().status().name());
        value.put("forward", matrix.apply(result.forward()));
        value.put("reversed", matrix.apply(result.reversed()));
        return writeValue(value);
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI output could not be normalized", exception);
        }
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
