package io.omnnu.finbot.infrastructure.ingestion.client;

import io.omnnu.finbot.application.ingestion.exception.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.SourceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Independent per-source circuit and daily call budget for the optional Firecrawl channel. */
@Component
public final class FirecrawlChannelGuard {
    private final int maximumCallsPerSourcePerDay;
    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;
    private final ConcurrentHashMap<SourceId, State> states = new ConcurrentHashMap<>();

    public FirecrawlChannelGuard(
            @Value("${finbot.firecrawl.maximum-calls-per-source-per-day:100}")
                    int maximumCallsPerSourcePerDay,
            @Value("${finbot.firecrawl.circuit-failure-threshold:3}") int failureThreshold,
            @Value("${finbot.firecrawl.circuit-cooldown:PT15M}") Duration cooldown,
            Clock clock) {
        if (maximumCallsPerSourcePerDay < 1 || maximumCallsPerSourcePerDay > 100_000
                || failureThreshold < 1 || failureThreshold > 100) {
            throw new IllegalArgumentException("Firecrawl channel guard limits are invalid");
        }
        this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (cooldown.isNegative() || cooldown.isZero() || cooldown.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Firecrawl circuit cooldown is invalid");
        }
        this.maximumCallsPerSourcePerDay = maximumCallsPerSourcePerDay;
        this.failureThreshold = failureThreshold;
    }

    public <T> T execute(SourceId sourceId, Supplier<T> operation) {
        var state = state(sourceId);
        acquire(state);
        try {
            var result = Objects.requireNonNull(operation, "operation").get();
            succeeded(state);
            return result;
        } catch (RuntimeException exception) {
            failed(state);
            throw exception;
        }
    }

    public Snapshot snapshot(SourceId sourceId) {
        var state = state(sourceId);
        var now = clock.instant();
        synchronized (state) {
            resetDayIfRequired(state, now);
            var status = status(state, now);
            return new Snapshot(
                    status,
                    state.calls,
                    Math.max(0, maximumCallsPerSourcePerDay - state.calls),
                    maximumCallsPerSourcePerDay,
                    state.consecutiveFailures,
                    state.openUntil);
        }
    }

    private State state(SourceId sourceId) {
        return states.computeIfAbsent(
                Objects.requireNonNull(sourceId, "sourceId"),
                ignored -> new State(day(clock.instant())));
    }

    private void acquire(State state) {
        var now = clock.instant();
        synchronized (state) {
            resetDayIfRequired(state, now);
            if (state.openUntil != null && state.openUntil.isAfter(now)) {
                throw new SourceCollectionException(
                        "FIRECRAWL_CIRCUIT_OPEN",
                        "Firecrawl channel is cooling down after repeated failures",
                        true);
            }
            if (state.calls >= maximumCallsPerSourcePerDay) {
                throw new SourceCollectionException(
                        "FIRECRAWL_DAILY_BUDGET_EXHAUSTED",
                        "Firecrawl daily source call budget is exhausted",
                        true);
            }
            state.calls++;
        }
    }

    private void succeeded(State state) {
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.openUntil = null;
        }
    }

    private void failed(State state) {
        synchronized (state) {
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= failureThreshold) {
                state.openUntil = clock.instant().plus(cooldown);
            }
        }
    }

    private void resetDayIfRequired(State state, Instant now) {
        var currentDay = day(now);
        if (!currentDay.equals(state.day)) {
            state.day = currentDay;
            state.calls = 0;
        }
        if (state.openUntil != null && !state.openUntil.isAfter(now)) {
            state.openUntil = null;
            state.consecutiveFailures = 0;
        }
    }

    private String status(State state, Instant now) {
        if (state.openUntil != null && state.openUntil.isAfter(now)) {
            return "CIRCUIT_OPEN";
        }
        if (state.calls >= maximumCallsPerSourcePerDay) {
            return "BUDGET_EXHAUSTED";
        }
        return "READY";
    }

    private static LocalDate day(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static final class State {
        private LocalDate day;
        private int calls;
        private int consecutiveFailures;
        private Instant openUntil;

        private State(LocalDate day) {
            this.day = day;
        }
    }

    public record Snapshot(
            String status,
            int callsToday,
            int remainingCallsToday,
            int maximumCallsPerDay,
            int consecutiveFailures,
            Instant openUntil) {
        public Snapshot {
            status = Objects.requireNonNull(status, "status");
            if (callsToday < 0 || remainingCallsToday < 0 || maximumCallsPerDay < 1
                    || consecutiveFailures < 0
                    || callsToday + remainingCallsToday != maximumCallsPerDay) {
                throw new IllegalArgumentException("Firecrawl channel snapshot is invalid");
            }
        }
    }
}
