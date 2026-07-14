package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.domain.oms.OrderId;
import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface PaperOrderExecutionUseCase {
    CompletionStage<List<PaperOrderExecutionResult>> submitAll(List<OrderId> orderIds);
}
