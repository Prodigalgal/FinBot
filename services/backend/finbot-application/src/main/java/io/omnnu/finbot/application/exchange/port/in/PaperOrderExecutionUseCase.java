package io.omnnu.finbot.application.exchange.port.in;

import io.omnnu.finbot.application.exchange.dto.PaperOrderExecutionResult;

import io.omnnu.finbot.domain.oms.OrderId;
import java.util.List;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface PaperOrderExecutionUseCase {
    CompletionStage<List<PaperOrderExecutionResult>> submitAll(List<OrderId> orderIds);
}
