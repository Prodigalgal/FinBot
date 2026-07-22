package io.omnnu.finbot.application.ledger.port.out;

import io.omnnu.finbot.application.ledger.dto.AccountLedgerProjection;
import io.omnnu.finbot.application.ledger.dto.PositionView;
import io.omnnu.finbot.application.ledger.dto.TradingActivityCriteria;
import io.omnnu.finbot.application.ledger.dto.TradingActivityPage;
import io.omnnu.finbot.application.ledger.dto.TradingTimeRange;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import java.util.List;

public interface TradingLedgerQueryRepository {
    List<AccountLedgerProjection> accountOverview(TradingTimeRange range);

    List<PositionView> currentPositions(ExchangeAccountId accountId);

    TradingActivityPage activity(TradingActivityCriteria criteria);
}
