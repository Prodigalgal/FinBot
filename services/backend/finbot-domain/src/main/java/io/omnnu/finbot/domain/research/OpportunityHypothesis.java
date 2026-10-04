package io.omnnu.finbot.domain.research;

import io.omnnu.finbot.domain.shared.DomainText;
import java.util.List;
import java.util.Objects;

public record OpportunityHypothesis(String title, List<CausalStep> causalChain, List<String> requiredConditions,
        String catalystWindow, String pricedInObservation, String alternativeScenario, String triggerCondition,
        String invalidationCondition, String nextCheck, List<String> missingData, int horizonHours, String exposureGroup) {
    public enum EvidenceKind { OBSERVATION, ESTIMATE, PROXY, INFERENCE }
    public record CausalStep(EvidenceKind kind, String statement, List<String> evidenceReferences) {
        public CausalStep {
            Objects.requireNonNull(kind, "kind");
            statement = DomainText.required(statement, "statement", 2000);
            evidenceReferences = texts(evidenceReferences, "evidenceReferences", 0, 20);
            if (kind != EvidenceKind.INFERENCE && evidenceReferences.isEmpty())
                throw new IllegalArgumentException("Observed, estimated and proxy steps require evidence references");
        }
    }
    public OpportunityHypothesis {
        title = DomainText.required(title, "title", 200);
        causalChain = List.copyOf(causalChain);
        if (causalChain.size() < 2 || causalChain.size() > 8) throw new IllegalArgumentException("Causal chain requires 2 to 8 steps");
        requiredConditions = texts(requiredConditions, "requiredConditions", 1, 12);
        catalystWindow = DomainText.required(catalystWindow, "catalystWindow", 2000);
        pricedInObservation = DomainText.required(pricedInObservation, "pricedInObservation", 2000);
        alternativeScenario = DomainText.required(alternativeScenario, "alternativeScenario", 2000);
        triggerCondition = DomainText.required(triggerCondition, "triggerCondition", 2000);
        invalidationCondition = DomainText.required(invalidationCondition, "invalidationCondition", 2000);
        nextCheck = DomainText.required(nextCheck, "nextCheck", 2000);
        missingData = texts(missingData, "missingData", 0, 20);
        exposureGroup = DomainText.required(exposureGroup, "exposureGroup", 100);
        if (horizonHours < 1 || horizonHours > 2160) throw new IllegalArgumentException("Hypothesis horizon must be 1 hour to 90 days");
    }
    private static List<String> texts(List<String> values, String name, int minimum, int maximum) {
        var checked = List.copyOf(values);
        if (checked.size() < minimum || checked.size() > maximum) throw new IllegalArgumentException("Invalid " + name + " count");
        return checked.stream().map(value -> DomainText.required(value, name, 2000)).toList();
    }
}
