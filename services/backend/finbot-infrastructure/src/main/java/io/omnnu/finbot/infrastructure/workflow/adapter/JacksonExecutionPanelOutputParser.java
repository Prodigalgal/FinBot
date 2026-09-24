package io.omnnu.finbot.infrastructure.workflow.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.trading.dto.TradeDecisionDraft;
import io.omnnu.finbot.application.workflow.dto.ParsedConsensusBallot;
import io.omnnu.finbot.application.workflow.port.out.ExecutionPanelOutputParser;
import io.omnnu.finbot.domain.consensus.AnonymousCandidateId;
import io.omnnu.finbot.domain.consensus.AnonymousPreferenceBallot;
import io.omnnu.finbot.domain.consensus.BallotOrientation;
import io.omnnu.finbot.domain.consensus.LogicalRoleKey;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DecisionAction;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.NonDirectionalAction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class JacksonExecutionPanelOutputParser implements ExecutionPanelOutputParser {
    private static final Set<String> EXECUTION_FIELDS = Set.of(
            "action", "symbol", "confidence", "entry_reference", "target_price",
            "invalidation_price", "rationale", "evidence_refs", "summary", "argument");
    private static final Set<String> BALLOT_FIELDS = Set.of("preference_tiers");

    private final ObjectMapper objectMapper;

    public JacksonExecutionPanelOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ParsedExecutionDraft parseProposal(String output) {
        return parseDraft(output);
    }

    @Override
    public String parseCritique(String output) {
        var root = parseObject(output);
        requireAllowedFields(root, EXECUTION_FIELDS);
        return canonicalJson(root);
    }

    @Override
    public ParsedExecutionDraft parseRevision(String output) {
        return parseDraft(output);
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

    private ParsedExecutionDraft parseDraft(String output) {
        var root = parseObject(output);
        requireAllowedFields(root, EXECUTION_FIELDS);
        var actionText = requiredText(root, "action").toUpperCase(Locale.ROOT);
        DecisionAction action = switch (actionText) {
            case "BUY" -> DirectionalAction.BUY;
            case "SELL" -> DirectionalAction.SELL;
            case "WATCH" -> NonDirectionalAction.WATCH;
            case "HOLD" -> NonDirectionalAction.HOLD;
            default -> throw new IllegalArgumentException("Unsupported trade action: " + actionText);
        };

        var symbolText = root.has("symbol") && !root.path("symbol").isNull()
                ? root.path("symbol").asText().strip()
                : "UNSPECIFIED";
        var symbol = new InstrumentSymbol(symbolText.isEmpty() ? "UNSPECIFIED" : symbolText);
        var confidence = new Confidence(requiredDecimal(root, "confidence"));

        var directional = action instanceof DirectionalAction;
        var entry = directional ? new Price(requiredDecimal(root, "entry_reference")) : null;
        var target = directional ? new Price(requiredDecimal(root, "target_price")) : null;
        var invalidation = directional ? new Price(requiredDecimal(root, "invalidation_price")) : null;

        var rationale = strings(root.path("rationale"));
        if (rationale.isEmpty()) {
            if (root.has("argument") && !root.path("argument").isNull() && !root.path("argument").asText().isBlank()) {
                rationale = List.of(root.path("argument").asText().strip());
            } else if (root.has("summary") && !root.path("summary").isNull() && !root.path("summary").asText().isBlank()) {
                rationale = List.of(root.path("summary").asText().strip());
            } else {
                rationale = List.of("执行决策共识方案");
            }
        }

        var evidenceRefs = strings(root.path("evidence_refs"));

        var draft = new TradeDecisionDraft(
                action,
                symbol,
                confidence,
                entry,
                target,
                invalidation,
                rationale,
                evidenceRefs);
        return new ParsedExecutionDraft(canonicalJson(root), draft);
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
            var parsed = objectMapper.readTree(normalized);
            if (!(parsed instanceof ObjectNode objectNode)) {
                throw new IllegalArgumentException("AI trade output root must be an object");
            }
            return objectNode;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid execution panel JSON payload", exception);
        }
    }

    private static void requireAllowedFields(ObjectNode root, Set<String> allowedFields) {
        var unexpected = new HashSet<String>();
        root.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                unexpected.add(field);
            }
        });
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("Unexpected fields in AI output: " + unexpected);
        }
    }

    private static String requiredText(ObjectNode root, String field) {
        var node = root.path(field);
        if (!node.isTextual() || node.textValue().strip().isEmpty()) {
            throw new IllegalArgumentException("Field " + field + " must be a non-blank string");
        }
        return node.textValue().strip();
    }

    private static BigDecimal requiredDecimal(ObjectNode root, String field) {
        var node = root.path(field);
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.textValue().strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Field " + field + " is not a valid number", ex);
            }
        }
        throw new IllegalArgumentException("Field " + field + " must be a decimal");
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Field must be an array of strings");
        }
        var list = new ArrayList<String>();
        node.forEach(item -> {
            if (item.isTextual() && !item.textValue().isBlank()) {
                list.add(item.textValue().strip());
            }
        });
        return List.copyOf(list);
    }

    private String canonicalJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI output could not be normalized", exception);
        }
    }
}
