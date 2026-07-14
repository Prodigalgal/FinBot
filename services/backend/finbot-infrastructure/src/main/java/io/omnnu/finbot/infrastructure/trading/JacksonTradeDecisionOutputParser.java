package io.omnnu.finbot.infrastructure.trading;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.trading.ParsedTradeDecision;
import io.omnnu.finbot.application.trading.ParsedTradeReflection;
import io.omnnu.finbot.application.trading.TradeDecisionDraft;
import io.omnnu.finbot.application.trading.TradeDecisionOutputParser;
import io.omnnu.finbot.application.trading.TradeReflection;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.trading.Confidence;
import io.omnnu.finbot.domain.trading.DecisionAction;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.NonDirectionalAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class JacksonTradeDecisionOutputParser implements TradeDecisionOutputParser {
    private static final String UNSPECIFIED_SYMBOL = "UNSPECIFIED";
    private static final Set<String> DECISION_FIELDS = Set.of(
            "action", "symbol", "confidence", "entry_reference", "target_price",
            "invalidation_price", "rationale", "evidence_refs");
    private static final Set<String> REFLECTION_FIELDS = Set.of("verdict", "reasons", "decision");

    private final ObjectMapper objectMapper;

    public JacksonTradeDecisionOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ParsedTradeDecision parseDraft(String output) {
        var root = parseObject(output);
        return new ParsedTradeDecision(decision(root), canonical(root));
    }

    @Override
    public ParsedTradeReflection parseReflection(String output) {
        var root = parseObject(output);
        requireExactFields(root, REFLECTION_FIELDS, "reflection");
        var verdict = requiredText(root, "verdict");
        var reasons = strings(root, "reasons");
        return switch (verdict) {
            case "APPROVE" -> {
                if (!(root.get("decision") instanceof ObjectNode decisionNode)) {
                    throw new IllegalArgumentException("Approved reflection requires a decision object");
                }
                yield new ParsedTradeReflection(
                        new TradeReflection(true, reasons, decision(decisionNode)),
                        canonical(root));
            }
            case "REJECT" -> {
                if (!root.path("decision").isNull()) {
                    throw new IllegalArgumentException("Rejected reflection decision must be null");
                }
                yield new ParsedTradeReflection(
                        new TradeReflection(false, reasons, null),
                        canonical(root));
            }
            default -> throw new IllegalArgumentException("Reflection verdict must be APPROVE or REJECT");
        };
    }

    private TradeDecisionDraft decision(ObjectNode node) {
        requireExactFields(node, DECISION_FIELDS, "decision");
        var action = action(requiredText(node, "action"));
        var directional = action instanceof DirectionalAction;
        return new TradeDecisionDraft(
                action,
                symbol(node, directional),
                new Confidence(requiredDecimal(node, "confidence")),
                directional ? new Price(requiredDecimal(node, "entry_reference")) : requireNull(node, "entry_reference"),
                directional ? new Price(requiredDecimal(node, "target_price")) : requireNull(node, "target_price"),
                directional ? new Price(requiredDecimal(node, "invalidation_price")) : requireNull(node, "invalidation_price"),
                strings(node, "rationale"),
                strings(node, "evidence_refs"));
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
            throw new IllegalArgumentException("AI trade output is not valid JSON", exception);
        }
    }

    private String canonical(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode canonical AI trade output", exception);
        }
    }

    private static DecisionAction action(String value) {
        return switch (value) {
            case "BUY" -> DirectionalAction.BUY;
            case "SELL" -> DirectionalAction.SELL;
            case "HOLD" -> NonDirectionalAction.HOLD;
            case "WATCH" -> NonDirectionalAction.WATCH;
            default -> throw new IllegalArgumentException("Unsupported trade action");
        };
    }

    private static java.math.BigDecimal requiredDecimal(ObjectNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " must be a finite JSON decimal number");
        }
        return value.decimalValue();
    }

    private static Price requireNull(ObjectNode node, String field) {
        if (!node.path(field).isNull()) {
            throw new IllegalArgumentException(field + " must be null for HOLD or WATCH");
        }
        return null;
    }

    private static String requiredText(ObjectNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return value.textValue();
    }

    private static InstrumentSymbol symbol(ObjectNode node, boolean directional) {
        var value = node.get("symbol");
        if (!directional && (value == null || value.isNull()
                || (value.isTextual() && value.textValue().isBlank()))) {
            node.put("symbol", UNSPECIFIED_SYMBOL);
            return new InstrumentSymbol(UNSPECIFIED_SYMBOL);
        }
        return new InstrumentSymbol(requiredText(node, "symbol"));
    }

    private static List<String> strings(ObjectNode object, String field) {
        var node = object.get(field);
        if (node != null && node.isTextual()) {
            var value = node.textValue().strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(field + " contains an invalid value");
            }
            object.putArray(field).add(value);
            node = object.get(field);
        }
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty string array");
        }
        var values = new ArrayList<String>();
        node.forEach(value -> {
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalArgumentException(field + " contains an invalid value");
            }
            values.add(value.textValue());
        });
        return List.copyOf(values);
    }

    private static void requireExactFields(ObjectNode node, Set<String> expected, String label) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(label + " fields do not match the execution contract");
        }
    }
}
