package io.omnnu.finbot.domain.oms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrderStateMachineTest {
    @Test
    void acceptsValidLifecycle() {
        assertDoesNotThrow(() -> OrderStateMachine.requireTransition(OrderStatus.PLANNED, OrderStatus.SUBMITTING));
        assertDoesNotThrow(() -> OrderStateMachine.requireTransition(OrderStatus.SUBMITTING, OrderStatus.SUBMITTED));
        assertDoesNotThrow(() -> OrderStateMachine.requireTransition(OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED));
        assertDoesNotThrow(() -> OrderStateMachine.requireTransition(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED));
        assertDoesNotThrow(() -> OrderStateMachine.requireTransition(OrderStatus.FILLED, OrderStatus.RECONCILED));
    }

    @Test
    void rejectsSkippingSubmission() {
        assertThrows(
                IllegalStateException.class,
                () -> OrderStateMachine.requireTransition(OrderStatus.PLANNED, OrderStatus.FILLED));
    }
}
