package io.omnnu.finbot.domain.research;

import io.omnnu.finbot.domain.shared.DecimalValue;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DirectionProbabilityDistribution(
        BigDecimal up,
        BigDecimal sideways,
        BigDecimal down) {
    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.0001");
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public DirectionProbabilityDistribution {
        up = probability(up, "up");
        sideways = probability(sideways, "sideways");
        down = probability(down, "down");
        var deviation = up.add(sideways).add(down).subtract(BigDecimal.ONE).abs();
        if (deviation.compareTo(SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException("direction probabilities must sum to one");
        }
    }

    public BigDecimal probability(ForecastDirection direction) {
        return switch (Objects.requireNonNull(direction, "direction")) {
            case UP -> up;
            case SIDEWAYS -> sideways;
            case DOWN -> down;
            case UNCERTAIN -> throw new IllegalArgumentException("uncertain is not a probability bucket");
        };
    }

    public Optional<ForecastDirection> uniqueLeader() {
        var values = new EnumMap<ForecastDirection, BigDecimal>(ForecastDirection.class);
        values.put(ForecastDirection.UP, up);
        values.put(ForecastDirection.SIDEWAYS, sideways);
        values.put(ForecastDirection.DOWN, down);
        var maximum = values.values().stream().max(BigDecimal::compareTo).orElseThrow();
        var leaders = values.entrySet().stream()
                .filter(entry -> entry.getValue().compareTo(maximum) == 0)
                .map(java.util.Map.Entry::getKey)
                .toList();
        return leaders.size() == 1 ? Optional.of(leaders.getFirst()) : Optional.empty();
    }

    public static DirectionProbabilityDistribution mean(List<DirectionProbabilityDistribution> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("direction probability mean requires at least one value");
        }
        var divisor = BigDecimal.valueOf(values.size());
        return new DirectionProbabilityDistribution(
                values.stream().map(DirectionProbabilityDistribution::up)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).divide(divisor, MATH_CONTEXT),
                values.stream().map(DirectionProbabilityDistribution::sideways)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).divide(divisor, MATH_CONTEXT),
                values.stream().map(DirectionProbabilityDistribution::down)
                        .reduce(BigDecimal.ZERO, BigDecimal::add).divide(divisor, MATH_CONTEXT));
    }

    private static BigDecimal probability(BigDecimal value, String fieldName) {
        var normalized = DecimalValue.nonNegative(value, fieldName);
        if (normalized.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(fieldName + " probability must not exceed one");
        }
        return normalized;
    }
}
