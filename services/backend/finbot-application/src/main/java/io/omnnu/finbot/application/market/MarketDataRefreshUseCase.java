package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface MarketDataRefreshUseCase {
    CompletionStage<MarketDataRefreshResult> refresh(InstrumentId instrumentId);
}
