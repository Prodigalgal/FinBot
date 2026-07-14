package io.omnnu.finbot.application.ledger;

import java.util.List;

public record TradingActivityPage(
        List<TradingActivity> activities,
        TradingActivityCursor nextCursor) {
    public TradingActivityPage {
        activities = List.copyOf(activities);
    }
}
