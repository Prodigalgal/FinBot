package io.omnnu.finbot.infrastructure.research.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.omnnu.finbot.domain.research.OpportunityHypothesis;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class OpportunityHypothesisCodec {
    private static final Set<String> FIELDS = Set.of("title", "causal_chain", "required_conditions", "catalyst_window",
            "priced_in_observation", "alternative_scenario", "trigger_condition", "invalidation_condition", "next_check",
            "missing_data", "horizon_hours", "exposure_group");
    private OpportunityHypothesisCodec() { }
    public static OpportunityHypothesis decode(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        requireFields(node, FIELDS);
        var steps = node.path("causal_chain");
        if (!steps.isArray() || steps.size() > 8) throw new IllegalArgumentException("Invalid causal_chain");
        var chain = new ArrayList<OpportunityHypothesis.CausalStep>();
        for (var step : steps) {
            requireFields(step, Set.of("kind", "statement", "evidence_refs"));
            chain.add(new OpportunityHypothesis.CausalStep(OpportunityHypothesis.EvidenceKind.valueOf(text(step, "kind")),
                    text(step, "statement"), texts(step, "evidence_refs")));
        }
        if (!node.path("horizon_hours").isIntegralNumber() || !node.path("horizon_hours").canConvertToInt())
            throw new IllegalArgumentException("Invalid horizon_hours");
        return new OpportunityHypothesis(text(node, "title"), chain, texts(node, "required_conditions"),
                text(node, "catalyst_window"), text(node, "priced_in_observation"), text(node, "alternative_scenario"),
                text(node, "trigger_condition"), text(node, "invalidation_condition"), text(node, "next_check"),
                texts(node, "missing_data"), node.path("horizon_hours").intValue(), text(node, "exposure_group"));
    }
    private static void requireFields(JsonNode node, Set<String> fields) {
        if (!node.isObject()) throw new IllegalArgumentException("Hypothesis must be an object");
        node.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) throw new IllegalArgumentException("Unknown hypothesis field: " + field);
        });
        for (var field : fields) if (!node.has(field)) throw new IllegalArgumentException("Missing hypothesis field: " + field);
    }
    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException("Hypothesis " + field + " must be text");
        return value.textValue();
    }
    private static List<String> texts(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isArray() || value.size() > 20) throw new IllegalArgumentException("Invalid hypothesis " + field);
        var texts = new ArrayList<String>();
        for (var element : value) {
            if (!element.isTextual()) throw new IllegalArgumentException("Hypothesis list must contain text");
            texts.add(element.textValue());
        }
        return List.copyOf(texts);
    }
}
