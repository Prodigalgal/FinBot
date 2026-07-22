package io.omnnu.finbot.application.ledger.port.in;

import io.omnnu.finbot.application.ledger.dto.PositionView;
import io.omnnu.finbot.application.ledger.dto.TradingAccountsOverview;
import io.omnnu.finbot.application.ledger.dto.TradingActivityCriteria;
import io.omnnu.finbot.application.ledger.dto.TradingActivityPage;
import io.omnnu.finbot.application.ledger.dto.TradingTimeRange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.List;

public interface TradingLedgerQueryUseCase {
    TradingAccountsOverview accounts(TradingTimeRange range);

    List<PositionView> currentPositions(ExchangeAccountId accountId);

    TradingActivityPage activity(TradingActivityCriteria criteria);
}
