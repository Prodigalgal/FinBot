package io.omnnu.finbot.application.operations.dto;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import java.util.Objects;

public record MarketDataTaskPayload(InstrumentId instrumentId) implements BackgroundTaskPayload {
    public MarketDataTaskPayload {
        Objects.requireNonNull(instrumentId, "instrumentId");
    }
}
