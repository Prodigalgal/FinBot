package io.omnnu.finbot.domain.oms;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class OrderStateMachine {
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = transitions();

    private OrderStateMachine() {
    }

    public static void requireTransition(OrderStatus from, OrderStatus to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("Illegal OMS transition: " + from + " -> " + to);
        }
    }

    private static Map<OrderStatus, Set<OrderStatus>> transitions() {
        var transitions = new EnumMap<OrderStatus, Set<OrderStatus>>(OrderStatus.class);
        transitions.put(OrderStatus.PLANNED, EnumSet.of(
                OrderStatus.SUBMITTING,
                OrderStatus.REJECTED,
                OrderStatus.CANCELLED));
        transitions.put(OrderStatus.SUBMITTING, EnumSet.of(
                OrderStatus.SUBMITTED,
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED,
                OrderStatus.CANCELLED,
                OrderStatus.REJECTED,
                OrderStatus.EXPIRED));
        transitions.put(OrderStatus.SUBMITTED, EnumSet.of(
                OrderStatus.PARTIALLY_FILLED,
                OrderStatus.FILLED,
                OrderStatus.CANCELLED,
                OrderStatus.REJECTED,
                OrderStatus.EXPIRED));
        transitions.put(OrderStatus.PARTIALLY_FILLED, EnumSet.of(
                OrderStatus.FILLED,
                OrderStatus.CANCELLED,
                OrderStatus.EXPIRED));
        transitions.put(OrderStatus.FILLED, EnumSet.of(OrderStatus.RECONCILED));
        transitions.put(OrderStatus.CANCELLED, EnumSet.of(OrderStatus.RECONCILED));
        transitions.put(OrderStatus.REJECTED, EnumSet.of(OrderStatus.RECONCILED));
        transitions.put(OrderStatus.EXPIRED, EnumSet.of(OrderStatus.RECONCILED));
        transitions.put(OrderStatus.RECONCILED, Set.of());
        return Map.copyOf(transitions);
    }
}
