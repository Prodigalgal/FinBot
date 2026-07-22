package io.omnnu.finbot.domain.research;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForecastSignalTest {
    @Test
    void acceptsDirectionalAndUncertainForecastShapes() {
        assertDoesNotThrow(() -> new ForecastSignal(
                ForecastDirection.UP,
                new BigDecimal("100"),
                new BigDecimal("96"),
                new BigDecimal("112"),
                new BigDecimal("94"),
                new BigDecimal("0.72"),
                "Momentum and liquidity support an upside scenario.",
                List.of("evidence_market_snapshot")));
        assertDoesNotThrow(() -> new ForecastSignal(
                ForecastDirection.UNCERTAIN,
                null,
                null,
                null,
                null,
                new BigDecimal("0.25"),
                "Evidence is insufficient to establish a directional range.",
                List.of()));
    }

    @Test
    void rejectsInvalidDirectionalRangesAndMissingEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastSignal(
                ForecastDirection.UP,
                new BigDecimal("100"),
                new BigDecimal("90"),
                new BigDecimal("99"),
                null,
                new BigDecimal("0.60"),
                "The claimed upside never exceeds the reference price.",
                List.of("evidence_market_snapshot")));
        assertThrows(IllegalArgumentException.class, () -> new ForecastSignal(
                ForecastDirection.DOWN,
                new BigDecimal("100"),
                new BigDecimal("90"),
                new BigDecimal("105"),
                null,
                new BigDecimal("0.60"),
                "Directional output must cite at least one evidence reference.",
                List.of()));
    }

    @Test
    void rejectsConfidenceAboveOneAndInventedUncertainPrices() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastSignal(
                ForecastDirection.SIDEWAYS,
                new BigDecimal("100"),
                new BigDecimal("95"),
                new BigDecimal("105"),
                null,
                new BigDecimal("1.01"),
                "Confidence must remain within the normalized probability range.",
                List.of("evidence_market_snapshot")));
        assertThrows(IllegalArgumentException.class, () -> new ForecastSignal(
                ForecastDirection.UNCERTAIN,
                new BigDecimal("100"),
                null,
                null,
                null,
                new BigDecimal("0.20"),
                "An abstention cannot invent actionable price levels.",
                List.of()));
    }

    @Test
    void validatesCompleteDirectionProbabilityDistribution() {
        assertDoesNotThrow(() -> new ForecastSignal(
                ForecastDirection.UP,
                new BigDecimal("100"),
                new BigDecimal("96"),
                new BigDecimal("112"),
                new BigDecimal("94"),
                new BigDecimal("0.60"),
                "Probability distribution supports the selected upside direction.",
                List.of("evidence_market_snapshot"),
                new DirectionProbabilityDistribution(
                        new BigDecimal("0.60"), new BigDecimal("0.25"), new BigDecimal("0.15"))));
        assertThrows(IllegalArgumentException.class, () -> new DirectionProbabilityDistribution(
                new BigDecimal("0.60"), new BigDecimal("0.30"), new BigDecimal("0.20")));
        assertThrows(IllegalArgumentException.class, () -> new ForecastSignal(
                ForecastDirection.DOWN,
                new BigDecimal("100"),
                new BigDecimal("90"),
                new BigDecimal("105"),
                null,
                new BigDecimal("0.20"),
                "Direction cannot contradict the highest probability bucket.",
                List.of("evidence_market_snapshot"),
                new DirectionProbabilityDistribution(
                        new BigDecimal("0.60"), new BigDecimal("0.20"), new BigDecimal("0.20"))));
    }
}
