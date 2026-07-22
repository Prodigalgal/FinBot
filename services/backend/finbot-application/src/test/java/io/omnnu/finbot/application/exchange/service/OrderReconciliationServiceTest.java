package io.omnnu.finbot.application.exchange.service;

import io.omnnu.finbot.application.exchange.dto.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.dto.OmsReconciliationCandidate;
import io.omnnu.finbot.application.exchange.dto.PaperOrderExecutionResult;
import io.omnnu.finbot.application.exchange.port.in.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.exchange.port.out.OrderReconciliationStore;
import io.omnnu.finbot.application.exchange.service.OrderReconciliationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.oms.OrderStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class OrderReconciliationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");
    private static final ExchangeAccountId ACCOUNT_ID = new ExchangeAccountId("account_bybit_demo_default");

    @Test
    void recoversUnknownSubmissionsAppliesLegalTransitionsAndCountsDiscrepancies() {
        var recoverable = new OrderId("order_recoverable");
        var valid = candidate("order_valid", OrderStatus.SUBMITTED, OrderStatus.FILLED);
        var illegal = candidate("order_illegal", OrderStatus.FILLED, OrderStatus.SUBMITTED);
        var store = new RecordingReconciliationStore(List.of(recoverable), List.of(valid, illegal));
        PaperOrderExecutionUseCase execution = ignored -> CompletableFuture.completedFuture(List.of(
                new PaperOrderExecutionResult(
                        recoverable,
                        ExchangeSubmissionStatus.ACKNOWLEDGED,
                        "exchange-recovered",
                        "Recovered")));
        var service = service(store, execution);

        var result = service.reconcile(ACCOUNT_ID).toCompletableFuture().join();

        assertEquals(1, result.recoveredSubmissionCount());
        assertEquals(1, result.reconciledOrderCount());
        assertEquals(1, result.discrepancyCount());
        assertEquals(List.of(valid), store.appliedCandidates);
        assertEquals(1, store.completedDiscrepancyCount);
    }

    @Test
    void countsUnknownRecoveryResultsAsDiscrepancies() {
        var recoverable = new OrderId("order_still_unknown");
        var store = new RecordingReconciliationStore(List.of(recoverable), List.of());
        PaperOrderExecutionUseCase execution = ignored -> CompletableFuture.completedFuture(List.of(
                new PaperOrderExecutionResult(
                        recoverable,
                        ExchangeSubmissionStatus.UNKNOWN,
                        null,
                        "Still unknown")));

        var result = service(store, execution).reconcile(ACCOUNT_ID).toCompletableFuture().join();

        assertEquals(1, result.discrepancyCount());
        assertEquals(1, store.completedDiscrepancyCount);
    }

    @Test
    void recordsFailedReconciliationWhenRecoveryFails() {
        var store = new RecordingReconciliationStore(List.of(new OrderId("order_failure")), List.of());
        PaperOrderExecutionUseCase execution = ignored -> CompletableFuture.failedFuture(
                new IllegalStateException("simulated exchange failure"));

        assertThrows(
                RuntimeException.class,
                () -> service(store, execution).reconcile(ACCOUNT_ID).toCompletableFuture().join());

        assertTrue(store.failed);
        assertEquals("ORDER_RECONCILIATION_FAILED", store.failureCode);
    }

    private static OrderReconciliationService service(
            OrderReconciliationStore store,
            PaperOrderExecutionUseCase execution) {
        return new OrderReconciliationService(
                store,
                execution,
                prefix -> prefix + "test",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);
    }

    private static OmsReconciliationCandidate candidate(
            String orderId,
            OrderStatus current,
            OrderStatus exchange) {
        return new OmsReconciliationCandidate(
                new OrderId(orderId),
                current,
                exchange,
                "exchange-100",
                new BigDecimal("0.001"),
                new BigDecimal("60000"),
                NOW.minusSeconds(60));
    }

    private static final class RecordingReconciliationStore implements OrderReconciliationStore {
        private final List<OrderId> recoverable;
        private final List<OmsReconciliationCandidate> candidates;
        private final java.util.ArrayList<OmsReconciliationCandidate> appliedCandidates = new java.util.ArrayList<>();
        private int completedDiscrepancyCount = -1;
        private boolean failed;
        private String failureCode;

        private RecordingReconciliationStore(
                List<OrderId> recoverable,
                List<OmsReconciliationCandidate> candidates) {
            this.recoverable = recoverable;
            this.candidates = candidates;
        }

        @Override
        public void start(String reconciliationId, ExchangeAccountId accountId, Instant startedAt) {
        }

        @Override
        public List<OrderId> recoverableOrders(ExchangeAccountId accountId, int limit) {
            return recoverable;
        }

        @Override
        public List<OmsReconciliationCandidate> candidates(ExchangeAccountId accountId, int limit) {
            return candidates;
        }

        @Override
        public boolean apply(OmsReconciliationCandidate candidate, Instant reconciledAt) {
            appliedCandidates.add(candidate);
            return true;
        }

        @Override
        public void complete(String reconciliationId, int discrepancyCount, Instant completedAt) {
            completedDiscrepancyCount = discrepancyCount;
        }

        @Override
        public void fail(
                String reconciliationId,
                String errorCode,
                String safeMessage,
                Instant failedAt) {
            failed = true;
            failureCode = errorCode;
        }
    }
}
