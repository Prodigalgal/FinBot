package io.omnnu.finbot.application.ai.service;

import io.omnnu.finbot.application.ai.dto.AiInvocationResult;

import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker.AiInvocationRejectedException;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

public final class AiExecutionPolicyExecutor {
    private final WorkflowAiInvoker aiInvoker;
    private final Clock clock;

    public AiExecutionPolicyExecutor(WorkflowAiInvoker aiInvoker, Clock clock) {
        this.aiInvoker = Objects.requireNonNull(aiInvoker, "aiInvoker");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int totalAttempts(WorkflowNodeDefinition node) {
        return Math.multiplyExact(bindings(node).size(), node.retryPolicy().maximumAttempts());
    }

    public <T> AiParsedInvocation<T> execute(
            WorkflowRunId runId,
            WorkflowDefinitionVersion version,
            WorkflowNodeDefinition node,
            String userPrompt,
            Instant deadline,
            int firstAttempt,
            Function<String, T> parser,
            AiAttemptListener attemptListener) {
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(attemptListener, "attemptListener");
        var bindings = bindings(node);
        var attemptsPerBinding = node.retryPolicy().maximumAttempts();
        var totalAttempts = Math.multiplyExact(bindings.size(), attemptsPerBinding);
        if (firstAttempt < 1 || firstAttempt > totalAttempts) {
            throw new IllegalArgumentException("firstAttempt must be within the configured AI attempt range");
        }
        AiExecutionFailure lastFailure = null;
        AiInvocationId lastInvocationId = null;
        for (var attempt = firstAttempt; attempt <= totalAttempts; attempt++) {
            var bindingIndex = (attempt - 1) / attemptsPerBinding;
            var bindingAttempt = (attempt - 1) % attemptsPerBinding + 1;
            var binding = bindings.get(bindingIndex);
            attemptListener.beforeAttempt(attempt, binding);
            try {
                var invocation = aiInvoker.invokeDetailed(
                        runId,
                        version,
                        node,
                        binding,
                        userPrompt,
                        deadline);
                lastInvocationId = invocation.invocationId();
                return new AiParsedInvocation<>(invocation, parser.apply(invocation.output()));
            } catch (AiInvocationRejectedException exception) {
                lastFailure = new AiExecutionFailure(
                        exception.errorCode(),
                        exception.getMessage(),
                        exception.retryable(),
                        lastInvocationId);
            } catch (IllegalArgumentException exception) {
                lastFailure = new AiExecutionFailure(
                        "AI_OUTPUT_SCHEMA_INVALID",
                        "AI output did not match the required structured contract",
                        true,
                        lastInvocationId);
            } catch (CancellationException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                lastFailure = new AiExecutionFailure(
                        "AI_PIPELINE_FAILURE",
                        "AI invocation pipeline failed: " + exception.getClass().getSimpleName(),
                        true,
                        lastInvocationId);
            }
            var bindingExhausted = bindingAttempt == attemptsPerBinding || !lastFailure.retryable();
            if (bindingExhausted && bindingIndex + 1 < bindings.size()) {
                continue;
            }
            if (bindingExhausted) {
                break;
            }
            pause(node.retryPolicy().backoff().multipliedBy(bindingAttempt), deadline, lastInvocationId);
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new AiExecutionFailure(
                "AI_EXECUTION_FAILED",
                "AI execution failed without a classified error",
                false,
                lastInvocationId);
    }

    private void pause(Duration duration, Instant deadline, AiInvocationId invocationId) {
        if (duration.isZero()) {
            return;
        }
        var remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative() || duration.compareTo(remaining) >= 0) {
            throw new AiExecutionFailure(
                    "WORKFLOW_DEADLINE_EXCEEDED",
                    "Workflow deadline was exceeded before the next AI retry",
                    false,
                    invocationId);
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiExecutionFailure(
                    "AI_RETRY_INTERRUPTED",
                    "AI retry was interrupted",
                    true,
                    invocationId);
        }
    }

    private static List<AiModelBinding> bindings(WorkflowNodeDefinition node) {
        return node.fallbackAiBinding() == null
                ? List.of(node.primaryAiBinding())
                : List.of(node.primaryAiBinding(), node.fallbackAiBinding());
    }

    @FunctionalInterface
    public interface AiAttemptListener {
        void beforeAttempt(int attempt, AiModelBinding binding);

        static AiAttemptListener noOp() {
            return (attempt, binding) -> {
            };
        }
    }

    public record AiParsedInvocation<T>(AiInvocationResult invocation, T parsed) {
        public AiParsedInvocation {
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(parsed, "parsed");
        }
    }
}
