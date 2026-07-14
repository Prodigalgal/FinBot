package io.omnnu.finbot.application.ledger;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.List;

public interface TradingLedgerQueryUseCase {
    TradingAccountsOverview accounts(TradingTimeRange range);

    List<PositionView> currentPositions(ExchangeAccountId accountId);

    TradingActivityPage activity(TradingActivityCriteria criteria);
}
