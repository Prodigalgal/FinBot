package io.omnnu.finbot.application.ledger.port.out;

import io.omnnu.finbot.application.ledger.dto.AccountSnapshotFact;
import io.omnnu.finbot.application.ledger.dto.BalanceFact;
import io.omnnu.finbot.application.ledger.dto.FillFact;
import io.omnnu.finbot.application.ledger.dto.OrderFact;
import io.omnnu.finbot.application.ledger.dto.PositionSnapshotFact;
import io.omnnu.finbot.application.ledger.dto.RealizedPnlFact;

public interface TradingLedgerWriter {
    boolean appendAccountSnapshot(AccountSnapshotFact snapshot);

    boolean appendBalance(BalanceFact balance);

    boolean appendOrder(OrderFact order);

    boolean appendFill(FillFact fill);

    boolean appendPosition(PositionSnapshotFact position);

    boolean appendRealizedPnl(RealizedPnlFact pnl);
}
