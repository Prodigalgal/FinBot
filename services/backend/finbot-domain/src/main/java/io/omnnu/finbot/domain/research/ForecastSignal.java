package io.omnnu.finbot.domain.research;

import io.omnnu.finbot.domain.shared.DecimalValue;
import io.omnnu.finbot.domain.shared.DomainText;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record ForecastSignal(
        ForecastDirection direction,
        BigDecimal referencePrice,
        BigDecimal expectedLow,
        BigDecimal expectedHigh,
        BigDecimal invalidationPrice,
        BigDecimal confidence,
        String thesis,
        List<String> evidenceReferences) {
    public ForecastSignal {
        direction = Objects.requireNonNull(direction, "direction");
        confidence = DecimalValue.nonNegative(confidence, "confidence");
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("forecast confidence must not exceed one");
        }
        thesis = DomainText.required(thesis, "thesis", 8_000);
        evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
        if (evidenceReferences.size() > 128 || evidenceReferences.stream()
                .anyMatch(value -> value == null || value.isBlank() || value.length() > 4_000)) {
            throw new IllegalArgumentException("forecast evidence references are invalid");
        }
        if (direction == ForecastDirection.UNCERTAIN) {
            if (referencePrice != null || expectedLow != null || expectedHigh != null || invalidationPrice != null) {
                throw new IllegalArgumentException("uncertain forecast must not invent price levels");
            }
        } else {
            referencePrice = DecimalValue.positive(referencePrice, "referencePrice");
            expectedLow = DecimalValue.positive(expectedLow, "expectedLow");
            expectedHigh = DecimalValue.positive(expectedHigh, "expectedHigh");
            if (expectedLow.compareTo(expectedHigh) > 0) {
                throw new IllegalArgumentException("forecast expectedLow must not exceed expectedHigh");
            }
            if (invalidationPrice != null) {
                invalidationPrice = DecimalValue.positive(invalidationPrice, "invalidationPrice");
            }
            if (evidenceReferences.isEmpty()) {
                throw new IllegalArgumentException("directional forecast requires evidence references");
            }
            if (direction == ForecastDirection.UP && expectedHigh.compareTo(referencePrice) <= 0) {
                throw new IllegalArgumentException("up forecast must include upside above the reference price");
            }
            if (direction == ForecastDirection.DOWN && expectedLow.compareTo(referencePrice) >= 0) {
                throw new IllegalArgumentException("down forecast must include downside below the reference price");
            }
            if (direction == ForecastDirection.SIDEWAYS
                    && (referencePrice.compareTo(expectedLow) < 0
                            || referencePrice.compareTo(expectedHigh) > 0)) {
                throw new IllegalArgumentException("sideways forecast range must contain the reference price");
            }
        }
    }
}
