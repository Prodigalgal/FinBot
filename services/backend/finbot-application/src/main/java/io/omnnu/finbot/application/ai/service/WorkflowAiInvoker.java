package io.omnnu.finbot.application.ai.service;

import io.omnnu.finbot.application.ai.dto.AiCompletionRequest;
import io.omnnu.finbot.application.ai.dto.AiInvocationCompletion;
import io.omnnu.finbot.application.ai.dto.AiInvocationFailure;
import io.omnnu.finbot.application.ai.dto.AiInvocationResult;
import io.omnnu.finbot.application.ai.dto.AiInvocationStart;
import io.omnnu.finbot.application.ai.dto.AiRuntimeBinding;
import io.omnnu.finbot.application.ai.exception.AiBudgetExceededException;
import io.omnnu.finbot.application.ai.exception.AiProviderUnavailableException;
import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.port.out.AiCompletionGateway;
import io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class WorkflowAiInvoker {
    private final AiCompletionGateway completionGateway;
    private final AiRuntimeBindingResolver bindingResolver;
    private final AiInvocationAuditStore auditStore;
    private final AiBudgetReservationStore budgetStore;
    private final WorkflowEventPublisher eventPublisher;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public WorkflowAiInvoker(
            AiCompletionGateway completionGateway,
            AiRuntimeBindingResolver bindingResolver,
            AiInvocationAuditStore auditStore,
            AiBudgetReservationStore budgetStore,
            WorkflowEventPublisher eventPublisher,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.completionGateway = Objects.requireNonNull(completionGateway, "completionGateway");
        this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
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
        var runtimeBinding = resolveBinding(binding);
        var protocol = runtimeBinding.protocol();
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
                runtimeBinding.capacityWaitTimeout(),
                deadline,
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

        try {
            reserveBudget(request, version, startedAt);
            var collector = new AiCompletionCollector(request, auditStore, eventPublisher, clock);
            completionGateway.stream(request).subscribe(collector);
            var completion = collector.await(startedAt, timeout, request.capacityWaitTimeout(), deadline);
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
        }
    }

    private AiRuntimeBinding resolveBinding(
            AiModelBinding binding) {
        try {
            return bindingResolver.resolve(binding);
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

}
