package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;

public record OrderReconciliationResult(
        String reconciliationId,
        ExchangeAccountId accountId,
        int recoveredSubmissionCount,
        int reconciledOrderCount,
        int discrepancyCount) {
}
