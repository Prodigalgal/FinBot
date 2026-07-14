package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.application.ledger.AccountSnapshotFact;
import io.omnnu.finbot.application.ledger.BalanceFact;
import io.omnnu.finbot.application.ledger.FillFact;
import io.omnnu.finbot.application.ledger.OrderFact;
import io.omnnu.finbot.application.ledger.PositionSnapshotFact;
import io.omnnu.finbot.application.ledger.RealizedPnlFact;
import java.time.Instant;
import java.util.List;

public record ExchangeAccountSyncBatch(
        AccountSnapshotFact accountSnapshot,
        List<BalanceFact> balances,
        List<PositionSnapshotFact> positions,
        List<OrderFact> orders,
        List<FillFact> fills,
        List<RealizedPnlFact> realizedPnl,
        Instant nextWatermark,
        boolean complete,
        List<String> warnings) {
    public ExchangeAccountSyncBatch {
        balances = List.copyOf(balances);
        positions = List.copyOf(positions);
        orders = List.copyOf(orders);
        fills = List.copyOf(fills);
        realizedPnl = List.copyOf(realizedPnl);
        warnings = List.copyOf(warnings);
    }
}
