package io.omnnu.finbot.application.exchange.port.out;

import io.omnnu.finbot.application.exchange.dto.OmsReconciliationCandidate;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.oms.OrderId;
import java.time.Instant;
import java.util.List;

public interface OrderReconciliationStore {
    void start(String reconciliationId, ExchangeAccountId accountId, Instant startedAt);

    List<OrderId> recoverableOrders(ExchangeAccountId accountId, int limit);

    List<OmsReconciliationCandidate> candidates(ExchangeAccountId accountId, int limit);

    boolean apply(OmsReconciliationCandidate candidate, Instant reconciledAt);

    void complete(String reconciliationId, int discrepancyCount, Instant completedAt);

    void fail(String reconciliationId, String errorCode, String safeMessage, Instant failedAt);
}
