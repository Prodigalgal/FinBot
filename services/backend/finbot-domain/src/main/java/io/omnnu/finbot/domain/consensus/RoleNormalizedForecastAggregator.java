package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.research.DirectionProbabilityDistribution;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.research.ForecastSignal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class RoleNormalizedForecastAggregator {
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public ForecastSignal resolve(
            List<RoleForecastSignal> inputs,
            BigDecimal marketReferencePrice,
            boolean socialChoiceSelected) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(marketReferencePrice, "marketReferencePrice");
        if (!socialChoiceSelected) {
            return uncertain("社会选择未形成唯一严格胜者，预测保持不确定。", List.of());
        }
        var probabilistic = inputs.stream()
                .filter(input -> input.forecast().directionProbabilities() != null)
                .toList();
        if (probabilistic.isEmpty()) {
            return uncertain("有效席位均未提交完整方向概率分布。", evidence(inputs));
        }
        var byRole = new LinkedHashMap<LogicalRoleKey, List<RoleForecastSignal>>();
        probabilistic.forEach(input -> byRole
                .computeIfAbsent(input.logicalRoleKey(), ignored -> new ArrayList<>())
                .add(input));
        var roleProbabilities = byRole.values().stream()
                .map(roleInputs -> DirectionProbabilityDistribution.mean(roleInputs.stream()
                        .map(RoleForecastSignal::forecast)
                        .map(ForecastSignal::directionProbabilities)
                        .toList()))
                .toList();
        var probabilities = DirectionProbabilityDistribution.mean(roleProbabilities);
        var leadingDirection = probabilities.uniqueLeader();
        if (leadingDirection.isEmpty()) {
            return uncertain("逻辑角色等权概率聚合后并列，预测保持不确定。", evidence(probabilistic), probabilities);
        }
        var direction = leadingDirection.orElseThrow();
        var roleForecasts = byRole.values().stream()
                .map(roleInputs -> roleInputs.stream()
                        .map(RoleForecastSignal::forecast)
                        .filter(forecast -> forecast.direction() == direction)
                        .toList())
                .filter(forecasts -> !forecasts.isEmpty())
                .toList();
        if (roleForecasts.isEmpty()) {
            return uncertain("最高概率方向没有可聚合的价格预测。", evidence(probabilistic), probabilities);
        }
        var expectedLow = crossRoleMedian(roleForecasts, ForecastSignal::expectedLow);
        var expectedHigh = crossRoleMedian(roleForecasts, ForecastSignal::expectedHigh);
        var invalidationPrice = crossRoleOptionalMedian(roleForecasts, ForecastSignal::invalidationPrice);
        var confidence = probabilities.probability(direction);
        var selectedInputs = probabilistic.stream()
                .filter(input -> input.forecast().direction() == direction)
                .toList();
        return new ForecastSignal(
                direction,
                marketReferencePrice,
                expectedLow,
                expectedHigh,
                invalidationPrice,
                confidence,
                "按逻辑角色等权聚合后，" + direction + " 概率最高（"
                        + confidence.stripTrailingZeros().toPlainString() + "）。",
                evidence(selectedInputs),
                probabilities);
    }

    private static BigDecimal crossRoleMedian(
            List<List<ForecastSignal>> byRole,
            java.util.function.Function<ForecastSignal, BigDecimal> selector) {
        return median(byRole.stream()
                .map(forecasts -> median(forecasts.stream().map(selector).toList()))
                .toList());
    }

    private static BigDecimal crossRoleOptionalMedian(
            List<List<ForecastSignal>> byRole,
            java.util.function.Function<ForecastSignal, BigDecimal> selector) {
        var roleMedians = byRole.stream()
                .map(forecasts -> forecasts.stream().map(selector).filter(Objects::nonNull).toList())
                .filter(values -> !values.isEmpty())
                .map(RoleNormalizedForecastAggregator::median)
                .toList();
        return roleMedians.isEmpty() ? null : median(roleMedians);
    }

    private static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("median requires at least one value");
        }
        var sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        var middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.TWO, MATH_CONTEXT);
    }

    private static List<String> evidence(List<RoleForecastSignal> inputs) {
        var references = new LinkedHashSet<String>();
        inputs.forEach(input -> references.addAll(input.forecast().evidenceReferences()));
        return references.stream().limit(128).toList();
    }

    private static ForecastSignal uncertain(String thesis, List<String> evidenceReferences) {
        return uncertain(thesis, evidenceReferences, null);
    }

    private static ForecastSignal uncertain(
            String thesis,
            List<String> evidenceReferences,
            DirectionProbabilityDistribution probabilities) {
        return new ForecastSignal(
                ForecastDirection.UNCERTAIN,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                thesis,
                evidenceReferences,
                probabilities);
    }
}
