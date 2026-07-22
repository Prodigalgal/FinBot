package io.omnnu.finbot.application.operations.service;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class TaskCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong registrationSequence = new AtomicLong();
    private final ConcurrentHashMap<Long, Runnable> callbacks = new ConcurrentHashMap<>();

    public boolean cancelled() {
        return cancelled.get();
    }

    public Registration register(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (cancelled()) {
            callback.run();
            return Registration.NO_OP;
        }
        var registrationId = registrationSequence.incrementAndGet();
        callbacks.put(registrationId, callback);
        if (cancelled() && callbacks.remove(registrationId, callback)) {
            callback.run();
            return Registration.NO_OP;
        }
        return () -> callbacks.remove(registrationId, callback);
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        callbacks.forEach((registrationId, callback) -> {
            if (callbacks.remove(registrationId, callback)) {
                callback.run();
            }
        });
    }

    public void throwIfCancelled() {
        if (cancelled()) {
            throw new CancellationException("Background task execution was cancelled");
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        Registration NO_OP = () -> {
        };

        @Override
        void close();
    }
}
