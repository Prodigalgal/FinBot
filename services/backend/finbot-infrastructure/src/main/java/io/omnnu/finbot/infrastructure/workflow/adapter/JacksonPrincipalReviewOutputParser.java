package io.omnnu.finbot.infrastructure.workflow.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.application.workflow.port.out.PrincipalReviewOutputParser;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.debate.PrincipalReviewAction;
import io.omnnu.finbot.domain.debate.PrincipalReviewDecision;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class JacksonPrincipalReviewOutputParser implements PrincipalReviewOutputParser {
    private static final Set<String> PRINCIPAL_REVIEW_FIELDS = Set.of(
            "action", "summary", "argument", "audit_rationale",
            "tightened_confidence", "tightened_max_leverage", "tightened_stop_distance",
            "audited_claims", "counterexamples", "risk_warnings");
    private static final Set<String> BALLOT_FIELDS = Set.of("preference_tiers");

    private final ObjectMapper objectMapper;

    public JacksonPrincipalReviewOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ParsedPrincipalReview parseProposal(String output) {
        return parseDecisionArtifact(output);
    }

    @Override
    public String parseCritique(String output) {
        var root = parseObject(output);
        requireAllowedFields(root, PRINCIPAL_REVIEW_FIELDS);
        return canonicalJson(root);
    }

    @Override
    public ParsedPrincipalReview parseRevision(String output) {
        return parseDecisionArtifact(output);
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

    private ParsedPrincipalReview parseDecisionArtifact(String output) {
        var root = parseObject(output);
        requireAllowedFields(root, PRINCIPAL_REVIEW_FIELDS);

        var actionRaw = requiredText(root, "action").toUpperCase(Locale.ROOT);
        PrincipalReviewAction action;
        try {
            action = PrincipalReviewAction.valueOf(actionRaw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid principal review action: " + actionRaw, ex);
        }

        var summary = requiredText(root, "summary");
        var auditRationale = root.has("argument")
                ? requiredText(root, "argument")
                : requiredText(root, "audit_rationale");

        var tightenedConfidence = optionalDecimal(root, "tightened_confidence");
        var tightenedMaxLeverage = optionalDecimal(root, "tightened_max_leverage");
        var tightenedStopDistance = optionalDecimal(root, "tightened_stop_distance");

        var auditedClaims = strings(root.path("audited_claims"));
        var counterexamples = strings(root.path("counterexamples"));
        var riskWarnings = strings(root.path("risk_warnings"));

        var decision = new PrincipalReviewDecision(
                action,
                summary,
                auditRationale,
                tightenedConfidence,
                tightenedMaxLeverage,
                tightenedStopDistance,
                auditedClaims,
                counterexamples,
                riskWarnings);

        return new ParsedPrincipalReview(canonicalJson(root), decision);
    }

    private JsonNode parseObject(String output) {
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("AI output must not be blank");
        }
        var cleaned = cleanJson(output);
        try {
            var node = objectMapper.readTree(cleaned);
            if (!node.isObject()) {
                throw new IllegalArgumentException("AI output must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("AI output is not valid JSON", ex);
        }
    }

    private static String cleanJson(String output) {
        var text = output.strip();
        if (text.startsWith("```")) {
            var firstLineBreak = text.indexOf('\n');
            var lastCodeFence = text.lastIndexOf("```");
            if (firstLineBreak != -1 && lastCodeFence > firstLineBreak) {
                text = text.substring(firstLineBreak + 1, lastCodeFence).strip();
            }
        }
        return text;
    }

    private static void requireAllowedFields(JsonNode root, Set<String> allowedFields) {
        var unexpected = new HashSet<String>();
        root.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                unexpected.add(field);
            }
        });
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("AI output contains unexpected fields: " + unexpected);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        var node = root.path(field);
        if (!node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalArgumentException("AI output field is required: " + field);
        }
        return node.textValue().strip();
    }

    private static BigDecimal optionalDecimal(JsonNode root, String field) {
        var node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual() && !node.textValue().isBlank()) {
            return new BigDecimal(node.textValue().strip());
        }
        throw new IllegalArgumentException("AI output field must be numeric: " + field);
    }

    private static List<String> strings(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
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

    private String canonicalJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot serialize JSON node", ex);
        }
    }
}
