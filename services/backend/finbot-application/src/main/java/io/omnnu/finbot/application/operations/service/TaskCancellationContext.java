package io.omnnu.finbot.application.operations.service;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class TaskCancellationContext {
    private static final InheritableThreadLocal<TaskCancellationToken> CURRENT = new InheritableThreadLocal<>();

    private TaskCancellationContext() {
    }

    public static <T> T call(TaskCancellationToken token, Supplier<T> operation) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(operation, "operation");
        var previous = CURRENT.get();
        CURRENT.set(token);
        try {
            token.throwIfCancelled();
            return operation.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static Optional<TaskCancellationToken> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void throwIfCancelled() {
        current().ifPresent(TaskCancellationToken::throwIfCancelled);
    }

    public static TaskCancellationToken.Registration interruptCurrentThreadOnCancellation() {
        var thread = Thread.currentThread();
        return current()
                .map(token -> token.register(thread::interrupt))
                .orElse(TaskCancellationToken.Registration.NO_OP);
    }
}
