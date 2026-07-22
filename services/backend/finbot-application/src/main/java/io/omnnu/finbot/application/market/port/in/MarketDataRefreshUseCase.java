package io.omnnu.finbot.application.market.port.in;

import io.omnnu.finbot.application.market.dto.MarketDataRefreshResult;

import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface MarketDataRefreshUseCase {
    CompletionStage<MarketDataRefreshResult> refresh(InstrumentId instrumentId);

    default CompletionStage<MarketDataRefreshResult> refresh(
            InstrumentId instrumentId,
            int intervalSeconds) {
        return refresh(instrumentId);
    }

    default CompletionStage<MarketDataRefreshResult> refresh(
            InstrumentId instrumentId,
            int intervalSeconds,
            ExchangeEnvironment environment) {
        if (environment != ExchangeEnvironment.LIVE) {
            return java.util.concurrent.CompletableFuture.failedStage(
                    new UnsupportedOperationException("Paper market refresh is not supported"));
        }
        return refresh(instrumentId, intervalSeconds);
    }
}
