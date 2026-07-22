package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.application.operations.TaskCancellationContext;
import io.omnnu.finbot.application.operations.TaskCancellationToken;
import io.omnnu.finbot.application.workflow.WorkflowEventPublisher;
import io.omnnu.finbot.domain.workflow.AiTextChunkPublished;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class AiCompletionCollector implements Flow.Subscriber<AiCompletionEvent> {
    private static final int TARGET_AUDIT_CHUNK_CHARACTERS = 512;

    private final AiCompletionRequest request;
    private final AiInvocationAuditStore auditStore;
    private final WorkflowEventPublisher eventPublisher;
    private final Clock clock;
    private final CompletableFuture<AiStreamCompletion> result = new CompletableFuture<>();
    private final CompletableFuture<Void> streamStarted = new CompletableFuture<>();
    private final StringBuilder output = new StringBuilder();
    private final StringBuilder pendingChunk = new StringBuilder();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Flow.Subscription subscription;
    private long auditChunkSequence;
    private long inputTokens;
    private long outputTokens;

    AiCompletionCollector(
            AiCompletionRequest request,
            AiInvocationAuditStore auditStore,
            WorkflowEventPublisher eventPublisher,
            Clock clock) {
        this.request = Objects.requireNonNull(request, "request");
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        if (subscription != null) {
            value.cancel();
            return;
        }
        subscription = Objects.requireNonNull(value, "value");
        if (cancelled.get()) {
            value.cancel();
            return;
        }
        value.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(AiCompletionEvent event) {
        if (result.isDone()) {
            return;
        }
        try {
            switch (event) {
                case AiStreamStarted ignored -> streamStarted.complete(null);
                case AiTextDelta delta -> append(delta);
                case AiUsageReported usage -> {
                    inputTokens = usage.inputTokens();
                    outputTokens = usage.outputTokens();
                }
                case AiCompletionFinished finished -> finish(finished);
                case AiCompletionFailed failed -> fail(failed);
            }
        } catch (RuntimeException exception) {
            cancel();
            result.completeExceptionally(exception);
        }
    }

    @Override
    public void onError(Throwable error) {
        result.completeExceptionally(new WorkflowAiInvoker.AiInvocationRejectedException(
                "AI_STREAM_SUBSCRIPTION_FAILED",
                "AI stream subscription failed: " + error.getClass().getSimpleName(),
                true));
    }

    @Override
    public void onComplete() {
        if (!result.isDone()) {
            result.completeExceptionally(new WorkflowAiInvoker.AiInvocationRejectedException(
                    "AI_STREAM_TRUNCATED",
                    "AI stream ended without a terminal event",
                    true));
        }
    }

    AiStreamCompletion await(
            Instant startedAt,
            Duration timeout,
            Duration capacityWaitTimeout,
            Instant workflowDeadline) {
        TaskCancellationToken.Registration cancellationRegistration = TaskCancellationContext.current()
                .map(token -> token.register(this::cancelForTask))
                .orElse(TaskCancellationToken.Registration.NO_OP);
        try {
            TaskCancellationContext.throwIfCancelled();
            var capacityDeadline = minimum(workflowDeadline, startedAt.plus(capacityWaitTimeout));
            CompletableFuture.anyOf(streamStarted, result).get(
                    remainingMilliseconds(capacityDeadline),
                    TimeUnit.MILLISECONDS);
            var requestDeadline = minimum(workflowDeadline, clock.instant().plus(timeout));
            return result.get(remainingMilliseconds(requestDeadline), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel();
            throw new WorkflowAiInvoker.AiInvocationRejectedException(
                    "AI_INVOCATION_INTERRUPTED",
                    "AI invocation was interrupted",
                    true);
        } catch (TimeoutException exception) {
            cancel();
            if (!streamStarted.isDone()) {
                throw new WorkflowAiInvoker.AiInvocationRejectedException(
                        "AI_PROVIDER_CAPACITY_TIMEOUT",
                        "AI provider concurrency queue exceeded its wait timeout",
                        true);
            }
            throw new WorkflowAiInvoker.AiInvocationRejectedException(
                    "AI_INVOCATION_TIMEOUT",
                    "AI invocation exceeded its timeout",
                    true);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("AI stream failed", cause);
        } finally {
            cancellationRegistration.close();
        }
    }

    private void append(AiTextDelta delta) {
        output.append(delta.text());
        pendingChunk.append(delta.text());
        flush(false, delta.occurredAt());
    }

    private void finish(AiCompletionFinished finished) {
        flush(true, finished.occurredAt());
        result.complete(new AiStreamCompletion(
                output.toString(),
                inputTokens,
                outputTokens,
                finished.finishReason()));
    }

    private void fail(AiCompletionFailed failed) {
        flush(true, failed.occurredAt());
        result.completeExceptionally(new WorkflowAiInvoker.AiInvocationRejectedException(
                failed.errorCode(),
                failed.safeMessage(),
                failed.retryable()));
    }

    private void flush(boolean force, Instant occurredAt) {
        while (pendingChunk.length() >= TARGET_AUDIT_CHUNK_CHARACTERS
                || (force && !pendingChunk.isEmpty())) {
            var length = pendingChunk.length() >= TARGET_AUDIT_CHUNK_CHARACTERS
                    ? TARGET_AUDIT_CHUNK_CHARACTERS
                    : pendingChunk.length();
            var content = pendingChunk.substring(0, length);
            pendingChunk.delete(0, length);
            var sequence = ++auditChunkSequence;
            auditStore.appendChunk(request.invocationId(), sequence, content, occurredAt);
            eventPublisher.publish(request.runId(), (eventId, eventSequence, eventAt) ->
                    new AiTextChunkPublished(
                            eventId,
                            request.runId(),
                            eventSequence,
                            request.invocationId(),
                            request.nodeId(),
                            sequence,
                            content,
                            eventAt));
        }
    }

    private void cancel() {
        cancelled.set(true);
        if (subscription != null) {
            subscription.cancel();
        }
    }

    private void cancelForTask() {
        cancel();
        result.completeExceptionally(new java.util.concurrent.CancellationException(
                "Background task cancelled the AI invocation"));
    }

    private long remainingMilliseconds(Instant deadline) throws TimeoutException {
        var remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new TimeoutException("AI invocation deadline exceeded");
        }
        return Math.max(1, remaining.toMillis());
    }

    private static Instant minimum(Instant first, Instant second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}

record AiStreamCompletion(
        String output,
        long inputTokens,
        long outputTokens,
        String finishReason) {
}
