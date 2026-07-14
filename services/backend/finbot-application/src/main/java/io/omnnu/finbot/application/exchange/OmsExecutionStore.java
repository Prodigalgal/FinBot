package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.oms.OrderId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface OmsExecutionStore {
    Optional<ExecutableOrder> claim(
            OrderId orderId,
            String workerId,
            Instant claimedAt,
            Duration leaseDuration);

    void recordResult(ExecutableOrder order, ExchangeSubmissionResult result, Instant completedAt);
}
