package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.research.ForecastSignal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        var directional = inputs.stream()
                .filter(input -> input.forecast().direction() != ForecastDirection.UNCERTAIN)
                .toList();
        if (directional.isEmpty()) {
            return uncertain("有效席位均未形成方向性预测。", List.of());
        }
        var byRole = new LinkedHashMap<LogicalRoleKey, List<ForecastSignal>>();
        directional.forEach(input -> byRole
                .computeIfAbsent(input.logicalRoleKey(), ignored -> new ArrayList<>())
                .add(input.forecast()));
        var roleDirections = new LinkedHashMap<LogicalRoleKey, ForecastDirection>();
        byRole.forEach((role, forecasts) -> uniqueRoleDirection(forecasts)
                .ifPresent(direction -> roleDirections.put(role, direction)));
        if (roleDirections.isEmpty()) {
            return uncertain("逻辑角色内部方向存在并列，无法形成角色级预测。", evidence(directional));
        }
        var directionCounts = new EnumMap<ForecastDirection, Integer>(ForecastDirection.class);
        roleDirections.values().forEach(direction -> directionCounts.merge(direction, 1, Integer::sum));
        var maximumVotes = directionCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        var winners = directionCounts.entrySet().stream()
                .filter(entry -> entry.getValue() == maximumVotes)
                .map(Map.Entry::getKey)
                .toList();
        if (winners.size() != 1) {
            return uncertain("逻辑角色方向投票并列，预测保持不确定。", evidence(directional));
        }
        var direction = winners.getFirst();
        var agreeingRoles = roleDirections.entrySet().stream()
                .filter(entry -> entry.getValue() == direction)
                .map(Map.Entry::getKey)
                .toList();
        var roleForecasts = agreeingRoles.stream()
                .map(role -> byRole.get(role).stream()
                        .filter(forecast -> forecast.direction() == direction)
                        .toList())
                .filter(forecasts -> !forecasts.isEmpty())
                .toList();
        if (roleForecasts.isEmpty()) {
            return uncertain("获胜方向没有可聚合的价格预测。", evidence(directional));
        }
        var expectedLow = crossRoleMedian(roleForecasts, ForecastSignal::expectedLow);
        var expectedHigh = crossRoleMedian(roleForecasts, ForecastSignal::expectedHigh);
        var invalidationPrice = crossRoleOptionalMedian(roleForecasts, ForecastSignal::invalidationPrice);
        var confidence = BigDecimal.valueOf(agreeingRoles.size())
                .divide(BigDecimal.valueOf(roleDirections.size()), MATH_CONTEXT);
        var selectedInputs = directional.stream()
                .filter(input -> agreeingRoles.contains(input.logicalRoleKey()))
                .filter(input -> input.forecast().direction() == direction)
                .toList();
        return new ForecastSignal(
                direction,
                marketReferencePrice,
                expectedLow,
                expectedHigh,
                invalidationPrice,
                confidence,
                "按逻辑角色等权聚合后，" + agreeingRoles.size() + " / "
                        + roleDirections.size() + " 个有效角色支持 " + direction + "。",
                evidence(selectedInputs));
    }

    private static java.util.Optional<ForecastDirection> uniqueRoleDirection(
            List<ForecastSignal> forecasts) {
        var scores = new EnumMap<ForecastDirection, BigDecimal>(ForecastDirection.class);
        for (var direction : List.of(
                ForecastDirection.UP,
                ForecastDirection.SIDEWAYS,
                ForecastDirection.DOWN)) {
            var score = forecasts.stream()
                    .filter(forecast -> forecast.direction() == direction)
                    .map(ForecastSignal::confidence)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            scores.put(direction, score);
        }
        var maximum = scores.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (maximum.signum() == 0) {
            return java.util.Optional.empty();
        }
        var winners = scores.entrySet().stream()
                .filter(entry -> entry.getValue().compareTo(maximum) == 0)
                .map(Map.Entry::getKey)
                .toList();
        return winners.size() == 1
                ? java.util.Optional.of(winners.getFirst())
                : java.util.Optional.empty();
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
        return new ForecastSignal(
                ForecastDirection.UNCERTAIN,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO,
                thesis,
                evidenceReferences);
    }
}
