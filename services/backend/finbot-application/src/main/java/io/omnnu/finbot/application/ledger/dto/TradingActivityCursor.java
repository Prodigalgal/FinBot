package io.omnnu.finbot.application.ledger.dto;

import io.omnnu.finbot.application.ledger.dto.LedgerValidation;

import java.time.Instant;
import java.util.Objects;

public record TradingActivityCursor(Instant occurredAt, String activityId) {
    public TradingActivityCursor {
        Objects.requireNonNull(occurredAt, "occurredAt");
        activityId = LedgerValidation.required(activityId, "activityId", 80);
    }
}
