package io.omnnu.finbot.application.exchange.service;

import io.omnnu.finbot.application.exchange.dto.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.dto.OrderReconciliationResult;
import io.omnnu.finbot.application.exchange.port.in.OrderReconciliationUseCase;
import io.omnnu.finbot.application.exchange.port.in.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.exchange.port.out.OrderReconciliationStore;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.oms.OrderStateMachine;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class OrderReconciliationService implements OrderReconciliationUseCase {
    private static final int MAXIMUM_ORDERS = 500;

    private final OrderReconciliationStore store;
    private final PaperOrderExecutionUseCase execution;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;
    private final Executor executor;

    public OrderReconciliationService(
            OrderReconciliationStore store,
            PaperOrderExecutionUseCase execution,
            SortableIdGenerator idGenerator,
            Clock clock,
            Executor executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<OrderReconciliationResult> reconcile(ExchangeAccountId accountId) {
        return CompletableFuture.supplyAsync(() -> reconcileNow(accountId), executor);
    }

    private OrderReconciliationResult reconcileNow(ExchangeAccountId accountId) {
        var reconciliationId = idGenerator.next("reconcile_");
        store.start(reconciliationId, accountId, clock.instant());
        try {
            var recoverable = store.recoverableOrders(accountId, MAXIMUM_ORDERS);
            var recoveryResults = execution.submitAll(recoverable).toCompletableFuture().join();
            var candidates = store.candidates(accountId, MAXIMUM_ORDERS);
            var reconciled = 0;
            var discrepancies = 0;
            for (var candidate : candidates) {
                if (candidate.currentStatus() == candidate.exchangeStatus()) {
                    continue;
                }
                try {
                    OrderStateMachine.requireTransition(
                            candidate.currentStatus(),
                            candidate.exchangeStatus());
                    if (store.apply(candidate, clock.instant())) {
                        reconciled++;
                    }
                } catch (IllegalStateException exception) {
                    discrepancies++;
                }
            }
            discrepancies += (int) recoveryResults.stream()
                    .filter(result -> result.status() == ExchangeSubmissionStatus.UNKNOWN)
                    .count();
            store.complete(reconciliationId, discrepancies, clock.instant());
            return new OrderReconciliationResult(
                    reconciliationId,
                    accountId,
                    recoveryResults.size(),
                    reconciled,
                    discrepancies);
        } catch (RuntimeException exception) {
            store.fail(
                    reconciliationId,
                    "ORDER_RECONCILIATION_FAILED",
                    "Order reconciliation failed: " + exception.getClass().getSimpleName(),
                    clock.instant());
            throw exception;
        }
    }
}
