package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.time.Instant;
import java.util.List;

public record ExchangeAccountSyncResult(
        ExchangeAccountId accountId,
        int factCount,
        Instant watermark,
        boolean complete,
        List<String> warnings) {
    public ExchangeAccountSyncResult {
        warnings = List.copyOf(warnings);
    }
}
