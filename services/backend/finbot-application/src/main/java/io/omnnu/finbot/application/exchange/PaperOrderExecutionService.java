package io.omnnu.finbot.application.exchange;

import io.omnnu.finbot.application.operations.TaskCancellationContext;
import io.omnnu.finbot.domain.oms.OrderId;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class PaperOrderExecutionService implements PaperOrderExecutionUseCase {
    private static final Duration SUBMISSION_LEASE = Duration.ofMinutes(2);

    private final OmsExecutionStore store;
    private final PaperExchangeGateway gateway;
    private final Clock clock;
    private final Executor executor;
    private final String workerId;

    public PaperOrderExecutionService(
            OmsExecutionStore store,
            PaperExchangeGateway gateway,
            Clock clock,
            Executor executor,
            String workerId) {
        this.store = Objects.requireNonNull(store, "store");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.workerId = requireText(workerId, "workerId");
    }

    @Override
    public CompletionStage<List<PaperOrderExecutionResult>> submitAll(List<OrderId> orderIds) {
        var uniqueOrderIds = List.copyOf(Objects.requireNonNull(orderIds, "orderIds")).stream()
                .distinct()
                .toList();
        var futures = uniqueOrderIds.stream()
                .map(orderId -> CompletableFuture.supplyAsync(() -> submit(orderId), executor))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    private PaperOrderExecutionResult submit(OrderId orderId) {
        TaskCancellationContext.throwIfCancelled();
        var claimedAt = clock.instant();
        var order = store.claim(orderId, workerId, claimedAt, SUBMISSION_LEASE)
                .orElse(null);
        if (order == null) {
            return new PaperOrderExecutionResult(
                    orderId,
                    ExchangeSubmissionStatus.UNKNOWN,
                    null,
                    "Order is not currently claimable");
        }
        ExchangeSubmissionResult result;
        var cancellationRegistration = TaskCancellationContext.interruptCurrentThreadOnCancellation();
        try {
            TaskCancellationContext.throwIfCancelled();
            result = gateway.findByClientOrderId(order).orElseGet(() -> gateway.submit(order));
        } catch (RuntimeException exception) {
            TaskCancellationContext.throwIfCancelled();
            result = new ExchangeSubmissionResult(
                    ExchangeSubmissionStatus.UNKNOWN,
                    null,
                    null,
                    null,
                    "EXCHANGE_TRANSPORT_FAILURE",
                    "Exchange request outcome is unknown: " + exception.getClass().getSimpleName());
        } finally {
            cancellationRegistration.close();
        }
        TaskCancellationContext.throwIfCancelled();
        store.recordResult(order, result, clock.instant());
        return new PaperOrderExecutionResult(
                orderId,
                result.status(),
                result.exchangeOrderId(),
                result.safeMessage());
    }

    private static String requireText(String value, String field) {
        var normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
