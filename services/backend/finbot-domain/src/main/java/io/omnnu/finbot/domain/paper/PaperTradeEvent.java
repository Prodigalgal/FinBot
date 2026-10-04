package io.omnnu.finbot.domain.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record PaperTradeEvent(String eventId, String tradeId, Type type, Instant occurredAt,
                              BigDecimal price, BigDecimal cashDeltaUsdt, BigDecimal feeUsdt,
                              String model, String sourceEndpoint) {
    public enum Type { RESERVED, ENTRY_FILL, EXIT_FILL, FUNDING, CANCELLED, EXPIRED }

    public PaperTradeEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(cashDeltaUsdt, "cashDeltaUsdt");
        Objects.requireNonNull(feeUsdt, "feeUsdt");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(sourceEndpoint, "sourceEndpoint");
        if (feeUsdt.signum() < 0 || (price != null && price.signum() <= 0)) {
            throw new IllegalArgumentException("Invalid paper event values");
        }
    }
}
