package io.omnnu.finbot.application.operations.dto;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.Objects;

public record AccountTaskPayload(ExchangeAccountId accountId) implements BackgroundTaskPayload {
    public AccountTaskPayload {
        Objects.requireNonNull(accountId, "accountId");
    }
}
