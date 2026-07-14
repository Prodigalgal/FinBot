package io.omnnu.finbot.application.ledger;

public interface TradingLedgerWriter {
    boolean appendAccountSnapshot(AccountSnapshotFact snapshot);

    boolean appendBalance(BalanceFact balance);

    boolean appendOrder(OrderFact order);

    boolean appendFill(FillFact fill);

    boolean appendPosition(PositionSnapshotFact position);

    boolean appendRealizedPnl(RealizedPnlFact pnl);
}
