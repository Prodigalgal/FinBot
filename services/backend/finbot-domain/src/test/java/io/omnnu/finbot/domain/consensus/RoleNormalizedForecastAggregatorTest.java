package io.omnnu.finbot.domain.consensus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.omnnu.finbot.domain.research.DirectionProbabilityDistribution;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.research.ForecastSignal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoleNormalizedForecastAggregatorTest {
    private final RoleNormalizedForecastAggregator aggregator = new RoleNormalizedForecastAggregator();

    @Test
    void normalizesMultipleSeatsWithinOneLogicalRole() {
        var result = aggregator.resolve(
                List.of(
                        input("bull", ForecastDirection.UP, "0.9", "90", "120"),
                        input("bull", ForecastDirection.UP, "0.8", "92", "122"),
                        input("bull", ForecastDirection.UP, "0.7", "94", "124"),
                        input("risk", ForecastDirection.DOWN, "0.8", "80", "105"),
                        input("macro", ForecastDirection.DOWN, "0.8", "82", "106")),
                new BigDecimal("100"),
                true);

        assertEquals(ForecastDirection.DOWN, result.direction());
        assertEquals(new BigDecimal("81"), result.expectedLow());
        assertEquals(new BigDecimal("105.5"), result.expectedHigh());
        assertEquals(new BigDecimal("0.5666666666666667"), result.confidence());
        assertEquals(new BigDecimal("0.5666666666666667"), result.directionProbabilities().down());
    }

    @Test
    void failsClosedWhenSocialChoiceHasNoWinner() {
        var result = aggregator.resolve(
                List.of(input("bull", ForecastDirection.UP, "0.9", "90", "120")),
                new BigDecimal("100"),
                false);

        assertEquals(ForecastDirection.UNCERTAIN, result.direction());
        assertEquals(BigDecimal.ZERO, result.confidence());
    }

    private static RoleForecastSignal input(
            String role,
            ForecastDirection direction,
            String confidence,
            String expectedLow,
            String expectedHigh) {
        return new RoleForecastSignal(
                new LogicalRoleKey(role),
                new ForecastSignal(
                        direction,
                        new BigDecimal("100"),
                        new BigDecimal(expectedLow),
                        new BigDecimal(expectedHigh),
                        null,
                        new BigDecimal(confidence),
                        "test thesis",
                        List.of("evidence:test"),
                        probabilities(direction, new BigDecimal(confidence))));
    }

    private static DirectionProbabilityDistribution probabilities(
            ForecastDirection direction,
            BigDecimal confidence) {
        var remainder = BigDecimal.ONE.subtract(confidence).divide(BigDecimal.TWO);
        return switch (direction) {
            case UP -> new DirectionProbabilityDistribution(confidence, remainder, remainder);
            case SIDEWAYS -> new DirectionProbabilityDistribution(remainder, confidence, remainder);
            case DOWN -> new DirectionProbabilityDistribution(remainder, remainder, confidence);
            case UNCERTAIN -> new DirectionProbabilityDistribution(
                    new BigDecimal("0.34"), new BigDecimal("0.33"), new BigDecimal("0.33"));
        };
    }
}
