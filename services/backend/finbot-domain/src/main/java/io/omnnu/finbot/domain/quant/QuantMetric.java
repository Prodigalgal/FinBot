package io.omnnu.finbot.domain.quant;

import io.omnnu.finbot.domain.shared.DomainText;
import java.util.Objects;

public record QuantMetric(String name, double value, MetricUnit unit) {
    public QuantMetric {
        name = DomainText.required(name, "metric name", 120);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("metric value must be finite");
        }
        Objects.requireNonNull(unit, "unit");
    }
}
