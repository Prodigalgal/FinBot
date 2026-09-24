package io.omnnu.finbot.domain.debate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record PrincipalReviewDecision(
        PrincipalReviewAction action,
        String summary,
        String auditRationale,
        BigDecimal tightenedConfidence,
        BigDecimal tightenedMaxLeverage,
        BigDecimal tightenedStopDistance,
        List<String> auditedClaims,
        List<String> counterexamples,
        List<String> riskWarnings) {
    public PrincipalReviewDecision {
        Objects.requireNonNull(action, "action");
        summary = Objects.requireNonNull(summary, "summary").strip();
        auditRationale = Objects.requireNonNull(auditRationale, "auditRationale").strip();
        if (summary.isEmpty()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        if (auditRationale.isEmpty()) {
            throw new IllegalArgumentException("auditRationale must not be blank");
        }
        auditedClaims = auditedClaims == null ? List.of() : List.copyOf(auditedClaims);
        counterexamples = counterexamples == null ? List.of() : List.copyOf(counterexamples);
        riskWarnings = riskWarnings == null ? List.of() : List.copyOf(riskWarnings);
        if (action == PrincipalReviewAction.REJECT && counterexamples.isEmpty() && riskWarnings.isEmpty()) {
            throw new IllegalArgumentException("REJECT decision must specify counterexamples or risk warnings");
        }
        if (tightenedConfidence != null && (tightenedConfidence.compareTo(BigDecimal.ZERO) < 0 || tightenedConfidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("tightenedConfidence must be between 0 and 1");
        }
        if (tightenedMaxLeverage != null && tightenedMaxLeverage.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("tightenedMaxLeverage must be >= 1");
        }
        if (tightenedStopDistance != null && tightenedStopDistance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("tightenedStopDistance must be positive");
        }
    }
}
