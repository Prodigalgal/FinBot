package io.omnnu.finbot.application.exchange.dto;

import io.omnnu.finbot.application.ledger.dto.AccountSnapshotFact;
import io.omnnu.finbot.application.ledger.dto.BalanceFact;
import io.omnnu.finbot.application.ledger.dto.FillFact;
import io.omnnu.finbot.application.ledger.dto.OrderFact;
import io.omnnu.finbot.application.ledger.dto.PositionSnapshotFact;
import io.omnnu.finbot.application.ledger.dto.RealizedPnlFact;
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
