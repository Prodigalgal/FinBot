package io.omnnu.finbot.application.exchange.service;

import io.omnnu.finbot.application.exchange.dto.ExchangeSubmissionResult;
import io.omnnu.finbot.application.exchange.dto.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.dto.ExecutableOrder;
import io.omnnu.finbot.application.exchange.port.out.OmsExecutionStore;
import io.omnnu.finbot.application.exchange.port.out.PaperExchangeGateway;
import io.omnnu.finbot.application.exchange.service.PaperOrderExecutionService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.application.operations.service.TaskCancellationToken;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PaperOrderExecutionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T03:00:00Z");

    @Test
    void deduplicatesOrderIdsAndRecoversExistingExchangeOrderWithoutSubmitting() {
        var order = order("order_existing");
        var store = new RecordingExecutionStore(order, order);
        var gateway = new RecordingGateway();
        gateway.findResult = Optional.of(acknowledged("exchange-100"));
        var service = service(store, gateway);

        var results = service.submitAll(List.of(order.orderId(), order.orderId()))
                .toCompletableFuture()
                .join();

        assertEquals(1, results.size());
        assertEquals(1, store.claimCount);
        assertEquals(1, gateway.findCount);
        assertEquals(0, gateway.submitCount);
        assertEquals(ExchangeSubmissionStatus.ACKNOWLEDGED, store.recordedResults.getFirst().status());
    }

    @Test
    void recordsUnknownTransportOutcomeThenRecoversByClientOrderIdOnRetry() {
        var order = order("order_unknown_retry");
        var store = new RecordingExecutionStore(order, order);
        var gateway = new RecordingGateway();
        gateway.transportFailure = true;
        var service = service(store, gateway);

        var unknown = service.submitAll(List.of(order.orderId())).toCompletableFuture().join().getFirst();

        assertEquals(ExchangeSubmissionStatus.UNKNOWN, unknown.status());
        assertEquals("EXCHANGE_TRANSPORT_FAILURE", store.recordedResults.getFirst().errorCode());
        gateway.transportFailure = false;
        gateway.findResult = Optional.of(acknowledged("exchange-recovered"));

        var recovered = service.submitAll(List.of(order.orderId())).toCompletableFuture().join().getFirst();

        assertEquals(ExchangeSubmissionStatus.ACKNOWLEDGED, recovered.status());
        assertEquals("exchange-recovered", recovered.exchangeOrderId());
        assertEquals(0, gateway.submitCount);
        assertEquals(2, store.recordedResults.size());
    }

    @Test
    void returnsUnknownWithoutCallingExchangeWhenOrderCannotBeClaimed() {
        var store = new RecordingExecutionStore();
        var gateway = new RecordingGateway();
        var service = service(store, gateway);

        var result = service.submitAll(List.of(new OrderId("order_unclaimable")))
                .toCompletableFuture()
                .join()
                .getFirst();

        assertEquals(ExchangeSubmissionStatus.UNKNOWN, result.status());
        assertTrue(result.safeMessage().contains("not currently claimable"));
        assertEquals(0, gateway.findCount);
        assertEquals(0, gateway.submitCount);
    }

    @Test
    void taskCancellationInterruptsExchangeIoAndPreventsResultCommit() throws Exception {
        var order = order("order_cancelled_io");
        var store = new RecordingExecutionStore(order);
        var gatewayStarted = new CountDownLatch(1);
        var gatewayInterrupted = new CountDownLatch(1);
        var gateway = new PaperExchangeGateway() {
            @Override
            public Optional<ExchangeSubmissionResult> findByClientOrderId(ExecutableOrder ignored) {
                gatewayStarted.countDown();
                try {
                    Thread.sleep(Duration.ofMinutes(5));
                    return Optional.empty();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    gatewayInterrupted.countDown();
                    throw new CancellationException("exchange request interrupted");
                }
            }

            @Override
            public ExchangeSubmissionResult submit(ExecutableOrder ignored) {
                throw new AssertionError("Cancelled lookup must not submit an order");
            }
        };
        var token = new TaskCancellationToken();
        try (var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
            var service = new PaperOrderExecutionService(
                    store,
                    gateway,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    executor,
                    "paper-order-cancel-test");
            var result = TaskCancellationContext.call(
                    token,
                    () -> service.submitAll(List.of(order.orderId())).toCompletableFuture());
            assertTrue(gatewayStarted.await(1, TimeUnit.SECONDS));

            token.cancel();

            assertTrue(gatewayInterrupted.await(1, TimeUnit.SECONDS));
            assertThrows(CompletionException.class, result::join);
            assertTrue(store.recordedResults.isEmpty());
        }
    }

    private static PaperOrderExecutionService service(
            OmsExecutionStore store,
            PaperExchangeGateway gateway) {
        return new PaperOrderExecutionService(
                store,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run,
                "paper-order-test");
    }

    private static ExecutableOrder order(String orderId) {
        return new ExecutableOrder(
                new OrderId(orderId),
                1,
                ExchangeVenue.BYBIT,
                ExchangeEnvironment.DEMO,
                new ExchangeAccountId("account_bybit_demo_default"),
                new InstrumentId("instrument_bybit_btc_usdt"),
                new InstrumentSymbol("BTCUSDT"),
                DirectionalAction.BUY,
                new BigDecimal("0.001"),
                new BigDecimal("10"),
                "finbot-client-order",
                NOW.plusSeconds(120));
    }

    private static ExchangeSubmissionResult acknowledged(String exchangeOrderId) {
        return new ExchangeSubmissionResult(
                ExchangeSubmissionStatus.ACKNOWLEDGED,
                exchangeOrderId,
                200,
                "{}",
                null,
                "Acknowledged");
    }

    private static final class RecordingExecutionStore implements OmsExecutionStore {
        private final ArrayDeque<ExecutableOrder> claims = new ArrayDeque<>();
        private final List<ExchangeSubmissionResult> recordedResults = new ArrayList<>();
        private int claimCount;

        private RecordingExecutionStore(ExecutableOrder... orders) {
            claims.addAll(List.of(orders));
        }

        @Override
        public Optional<ExecutableOrder> claim(
                OrderId orderId,
                String workerId,
                Instant claimedAt,
                java.time.Duration leaseDuration) {
            claimCount++;
            return Optional.ofNullable(claims.pollFirst());
        }

        @Override
        public void recordResult(
                ExecutableOrder order,
                ExchangeSubmissionResult result,
                Instant completedAt) {
            recordedResults.add(result);
        }
    }

    private static final class RecordingGateway implements PaperExchangeGateway {
        private Optional<ExchangeSubmissionResult> findResult = Optional.empty();
        private boolean transportFailure;
        private int findCount;
        private int submitCount;

        @Override
        public Optional<ExchangeSubmissionResult> findByClientOrderId(ExecutableOrder order) {
            findCount++;
            if (transportFailure) {
                throw new IllegalStateException("simulated timeout");
            }
            return findResult;
        }

        @Override
        public ExchangeSubmissionResult submit(ExecutableOrder order) {
            submitCount++;
            return acknowledged("exchange-submitted");
        }
    }
}
