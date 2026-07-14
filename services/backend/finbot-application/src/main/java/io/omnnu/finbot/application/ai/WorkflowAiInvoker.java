package io.omnnu.finbot.application.ai;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.WorkflowEventPublisher;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.workflow.AiTextChunkPublished;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class WorkflowAiInvoker {
    private static final int TARGET_AUDIT_CHUNK_CHARACTERS = 512;
    private static final long STREAM_GRACE_MILLISECONDS = 10_000;

    private final AiCompletionGateway completionGateway;
    private final AiProviderProtocolResolver protocolResolver;
    private final AiInvocationAuditStore auditStore;
    private final AiBudgetReservationStore budgetStore;
    private final WorkflowEventPublisher eventPublisher;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public WorkflowAiInvoker(
            AiCompletionGateway completionGateway,
            AiProviderProtocolResolver protocolResolver,
            AiInvocationAuditStore auditStore,
            AiBudgetReservationStore budgetStore,
            WorkflowEventPublisher eventPublisher,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.completionGateway = Objects.requireNonNull(completionGateway, "completionGateway");
        this.protocolResolver = Objects.requireNonNull(protocolResolver, "protocolResolver");
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore");
        this.budgetStore = Objects.requireNonNull(budgetStore, "budgetStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String invoke(
            WorkflowRunId runId,
            WorkflowDefinitionVersion version,
            WorkflowNodeDefinition node,
            String userPrompt,
            Instant deadline) {
        return invokeDetailed(runId, version, node, node.primaryAiBinding(), userPrompt, deadline).output();
    }

    public AiInvocationResult invokeDetailed(
            WorkflowRunId runId,
            WorkflowDefinitionVersion version,
            WorkflowNodeDefinition node,
            String userPrompt,
            Instant deadline) {
        return invokeDetailed(runId, version, node, node.primaryAiBinding(), userPrompt, deadline);
    }

    public AiInvocationResult invokeDetailed(
            WorkflowRunId runId,
            WorkflowDefinitionVersion version,
            WorkflowNodeDefinition node,
            AiModelBinding binding,
            String userPrompt,
            Instant deadline) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(userPrompt, "userPrompt");
        Objects.requireNonNull(deadline, "deadline");
        var invocationId = new AiInvocationId(idGenerator.next("invocation_"));
        var timeout = requestTimeout(node, deadline);
        var protocol = resolveProtocol(binding);
        var request = new AiCompletionRequest(
                invocationId,
                runId,
                node.nodeId(),
                binding.providerProfileId(),
                protocol,
                binding.modelName(),
                binding.reasoningEffort(),
                node.systemPrompt(),
                userPrompt,
                node.maximumOutputTokens(),
                timeout,
                version.checksum().substring(0, 16));
        var startedAt = clock.instant();
        auditStore.start(new AiInvocationStart(
                invocationId,
                runId,
                node.nodeId(),
                binding.providerProfileId(),
                protocol,
                binding.modelName(),
                binding.reasoningEffort(),
                request.promptVersion(),
                AiRequestHasher.hash(request),
                startedAt));

        var reserved = false;
        try {
            reserveBudget(request, version, startedAt);
            reserved = true;
            var collector = new CompletionCollector(request);
            completionGateway.stream(request).subscribe(collector);
            var completion = collector.await(timeout);
            if (completion.output().isBlank()) {
                throw new AiInvocationRejectedException(
                        "AI_EMPTY_OUTPUT",
                        "AI provider completed without output text",
                        true);
            }
            auditStore.complete(new AiInvocationCompletion(
                    invocationId,
                    completion.inputTokens(),
                    completion.outputTokens(),
                    completion.finishReason(),
                    clock.instant()));
            return new AiInvocationResult(invocationId, completion.output());
        } catch (AiInvocationRejectedException exception) {
            auditStore.fail(new AiInvocationFailure(
                    invocationId,
                    exception.errorCode(),
                    exception.getMessage(),
                    clock.instant()));
            throw exception;
        } catch (AiBudgetExceededException exception) {
            var rejected = new AiInvocationRejectedException(
                    "AI_BUDGET_EXCEEDED",
                    exception.getMessage(),
                    false);
            auditStore.fail(new AiInvocationFailure(
                    invocationId,
                    rejected.errorCode(),
                    rejected.getMessage(),
                    clock.instant()));
            throw rejected;
        } catch (RuntimeException exception) {
            auditStore.fail(new AiInvocationFailure(
                    invocationId,
                    "AI_PIPELINE_FAILURE",
                    "AI invocation pipeline failed: " + exception.getClass().getSimpleName(),
                    clock.instant()));
            throw exception;
        } finally {
            if (reserved) {
                budgetStore.release(invocationId, clock.instant());
            }
        }
    }

    private io.omnnu.finbot.domain.configuration.AiProtocol resolveProtocol(
            AiModelBinding binding) {
        try {
            return protocolResolver.protocolFor(binding.providerProfileId());
        } catch (AiProviderUnavailableException exception) {
            throw new AiInvocationRejectedException(
                    "AI_PROVIDER_CONFIGURATION_INVALID",
                    exception.getMessage(),
                    false);
        }
    }

    private void reserveBudget(
            AiCompletionRequest request,
            WorkflowDefinitionVersion version,
            Instant reservedAt) {
        var promptCharacters = Math.addExact(
                request.systemPrompt().length(),
                request.userPrompt().length());
        var estimatedInputTokens = Math.max(1, (promptCharacters + 3L) / 4L);
        budgetStore.reserve(
                request.invocationId(),
                request.runId(),
                request.providerProfileId(),
                request.modelName(),
                estimatedInputTokens,
                request.maximumOutputTokens(),
                version.maximumTokens(),
                version.maximumCostUsd(),
                reservedAt);
    }

    private Duration requestTimeout(WorkflowNodeDefinition node, Instant deadline) {
        var remaining = Duration.between(clock.instant(), deadline);
        var configured = Duration.ofSeconds(node.timeoutSeconds());
        var timeout = configured.compareTo(remaining) <= 0 ? configured : remaining;
        if (timeout.compareTo(Duration.ofSeconds(5)) < 0) {
            throw new AiInvocationRejectedException(
                    "WORKFLOW_DEADLINE_EXCEEDED",
                    "Workflow deadline leaves less than five seconds for the AI request",
                    false);
        }
        return timeout;
    }

    public static final class AiInvocationRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String errorCode;
        private final boolean retryable;

        public AiInvocationRejectedException(
                String errorCode,
                String safeMessage,
                boolean retryable) {
            super(Objects.requireNonNull(safeMessage, "safeMessage"));
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
            this.retryable = retryable;
        }

        public String errorCode() {
            return errorCode;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final class CompletionCollector implements Flow.Subscriber<AiCompletionEvent> {
        private final AiCompletionRequest request;
        private final CompletableFuture<StreamCompletion> result = new CompletableFuture<>();
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder pendingChunk = new StringBuilder();
        private Flow.Subscription subscription;
        private long auditChunkSequence;
        private long inputTokens;
        private long outputTokens;

        private CompletionCollector(AiCompletionRequest request) {
            this.request = request;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = Objects.requireNonNull(subscription, "subscription");
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AiCompletionEvent event) {
            if (result.isDone()) {
                return;
            }
            try {
                switch (event) {
                    case AiStreamStarted ignored -> {
                    }
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
            result.completeExceptionally(new AiInvocationRejectedException(
                    "AI_STREAM_SUBSCRIPTION_FAILED",
                    "AI stream subscription failed: " + error.getClass().getSimpleName(),
                    true));
        }

        @Override
        public void onComplete() {
            if (!result.isDone()) {
                result.completeExceptionally(new AiInvocationRejectedException(
                        "AI_STREAM_TRUNCATED",
                        "AI stream ended without a terminal event",
                        true));
            }
        }

        private void append(AiTextDelta delta) {
            output.append(delta.text());
            pendingChunk.append(delta.text());
            flush(false, delta.occurredAt());
        }

        private void finish(AiCompletionFinished finished) {
            flush(true, finished.occurredAt());
            result.complete(new StreamCompletion(
                    output.toString(),
                    inputTokens,
                    outputTokens,
                    finished.finishReason()));
        }

        private void fail(AiCompletionFailed failed) {
            flush(true, failed.occurredAt());
            result.completeExceptionally(new AiInvocationRejectedException(
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

        private StreamCompletion await(Duration timeout) {
            try {
                return result.get(
                        Math.addExact(timeout.toMillis(), STREAM_GRACE_MILLISECONDS),
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cancel();
                throw new AiInvocationRejectedException(
                        "AI_INVOCATION_INTERRUPTED",
                        "AI invocation was interrupted",
                        true);
            } catch (TimeoutException exception) {
                cancel();
                throw new AiInvocationRejectedException(
                        "AI_INVOCATION_TIMEOUT",
                        "AI invocation exceeded its timeout",
                        true);
            } catch (ExecutionException exception) {
                var cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("AI stream failed", cause);
            }
        }

        private void cancel() {
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    private record StreamCompletion(
            String output,
            long inputTokens,
            long outputTokens,
            String finishReason) {
    }
}
