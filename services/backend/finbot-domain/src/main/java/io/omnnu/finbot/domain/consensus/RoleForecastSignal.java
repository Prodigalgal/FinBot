package io.omnnu.finbot.domain.consensus;

import io.omnnu.finbot.domain.research.ForecastSignal;
import java.util.Objects;

public record RoleForecastSignal(LogicalRoleKey logicalRoleKey, ForecastSignal forecast) {
    public RoleForecastSignal {
        Objects.requireNonNull(logicalRoleKey, "logicalRoleKey");
        Objects.requireNonNull(forecast, "forecast");
    }
}
