package io.omnnu.finbot.operations.handler;

import io.omnnu.finbot.application.market.port.in.MarketDataRefreshUseCase;
import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.port.in.BackgroundTaskHandler;
import io.omnnu.finbot.application.operations.dto.MarketDataTaskPayload;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

@Component
public final class MarketDataSyncTaskHandler implements BackgroundTaskHandler {
    private final MarketDataRefreshUseCase marketDataRefresh;

    public MarketDataSyncTaskHandler(MarketDataRefreshUseCase marketDataRefresh) {
        this.marketDataRefresh = Objects.requireNonNull(marketDataRefresh, "marketDataRefresh");
    }

    @Override
    public BackgroundTaskType taskType() {
        return BackgroundTaskType.MARKET_DATA_SYNC;
    }

    @Override
    public CompletionStage<Void> handle(BackgroundTask task) {
        if (!(task.payload() instanceof MarketDataTaskPayload payload)) {
            throw new IllegalArgumentException("Market data task has an invalid payload");
        }
        return marketDataRefresh.refresh(payload.instrumentId()).thenApply(ignored -> null);
    }
}
