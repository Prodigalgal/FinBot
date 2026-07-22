package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.DebateSession;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.exception.WorkflowNotFoundException;
import io.omnnu.finbot.application.workflow.port.in.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowRunFailureUseCase;
import io.omnnu.finbot.application.workflow.port.out.StructuredAiOutputParser;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;

import static io.omnnu.finbot.application.workflow.service.WorkflowExecutionGraph.agentLayers;
import static io.omnnu.finbot.application.workflow.service.WorkflowExecutionGraph.findMessage;
import static io.omnnu.finbot.application.workflow.service.WorkflowExecutionGraph.latestRound;
import static io.omnnu.finbot.application.workflow.service.WorkflowExecutionGraph.maximumDebateRounds;
import static io.omnnu.finbot.application.workflow.service.WorkflowExecutionGraph.roleName;
import static io.omnnu.finbot.application.workflow.service.WorkflowExecutionGraph.turnIndexes;

import io.omnnu.finbot.application.ai.service.AiExecutionFailure;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageContent;
import io.omnnu.finbot.domain.workflow.AgentMessagePublished;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
import io.omnnu.finbot.domain.workflow.DebateStatus;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointStatus;
import io.omnnu.finbot.domain.workflow.WorkflowCompleted;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowProgressed;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowStage;
import io.omnnu.finbot.domain.workflow.WorkflowStageStarted;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class WorkflowExecutionService implements WorkflowExecutionUseCase {
    private final WorkflowExecutionStore executionStore;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowRunFailureUseCase workflowFailure;
    private final AiExecutionPolicyExecutor aiExecution;
    private final StructuredAiOutputParser outputParser;
    private final Clock clock;
    private final Executor executor;
    private final WorkflowPromptComposer promptComposer = new WorkflowPromptComposer();
    private final WorkflowConditionEvaluator conditionEvaluator = new WorkflowConditionEvaluator();
    private final WorkflowCheckpointManager checkpoints;

    public WorkflowExecutionService(
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowRunFailureUseCase workflowFailure,
            AiExecutionPolicyExecutor aiExecution,
            StructuredAiOutputParser outputParser,
            Clock clock,
            Executor executor) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.workflowFailure = Objects.requireNonNull(workflowFailure, "workflowFailure");
        this.aiExecution = Objects.requireNonNull(aiExecution, "aiExecution");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.checkpoints = new WorkflowCheckpointManager(this.executionStore, this.clock);
    }

    @Override
    public CompletionStage<Void> execute(WorkflowRunId runId) {
        Objects.requireNonNull(runId, "runId");
        return CompletableFuture.runAsync(() -> executeSynchronously(runId), executor);
    }

    private void executeSynchronously(WorkflowRunId runId) {
        var execution = executionStore.load(runId)
                .orElseThrow(() -> new WorkflowNotFoundException(runId.value()));
        if (execution.status() == WorkflowRunStatus.FAILED) {
            if (!executionStore.resumeFailed(runId, clock.instant())) {
                return;
            }
            execution = executionStore.load(runId)
                    .orElseThrow(() -> new WorkflowNotFoundException(runId.value()));
        } else if (terminalOrPaused(execution.status())) {
            return;
        }
        if (!executionStore.markRunning(runId, clock.instant())) {
            return;
        }
        try {
            executeDebate(execution);
        } catch (TerminalWorkflowFailure failure) {
            terminalize(runId, failure);
            throw failure;
        }
    }

    private void executeDebate(WorkflowExecutionContext execution) {
        var version = execution.definitionVersion();
        if (version.status() != WorkflowVersionStatus.PUBLISHED
                && version.status() != WorkflowVersionStatus.ARCHIVED) {
            throw new TerminalWorkflowFailure(
                    "WORKFLOW_VERSION_NOT_PUBLISHED",
                    "Workflow run is not bound to an immutable published version",
                    false);
        }
        var layers = agentLayers(version);
        if (layers.isEmpty()) {
            throw new TerminalWorkflowFailure(
                    "WORKFLOW_HAS_NO_AGENTS",
                    "Workflow contains no enabled AI debate agents",
                    false);
        }
        var chair = version.chair();
        if (!chair.enabled()) {
            throw new TerminalWorkflowFailure(
                    "WORKFLOW_CHAIR_DISABLED",
                    "Workflow chair node is disabled",
                    false);
        }
        var agents = layers.stream().flatMap(List::stream).toList();
        var maximumRounds = maximumDebateRounds(version);
        var requiredSteps = Math.addExact(
                Math.multiplyExact(agents.size(), maximumRounds),
                1);
        if (requiredSteps > version.maximumSteps()) {
            throw new TerminalWorkflowFailure(
                    "WORKFLOW_STEP_BUDGET_EXCEEDED",
                    "Configured debate requires more steps than the workflow permits",
                    false);
        }

        var session = ensureDebate(execution, chair);
        if (!session.chairNodeId().equals(chair.nodeId())) {
            throw new TerminalWorkflowFailure(
                    "DEBATE_CHAIR_MISMATCH",
                    "Persisted debate chair does not match the workflow version",
                    false);
        }
        if (session.configuredRounds() != maximumRounds) {
            throw new TerminalWorkflowFailure(
                    "DEBATE_ROUND_BUDGET_MISMATCH",
                    "Persisted debate round budget does not match the workflow version",
                    false);
        }
        var attemptStartedAt = clock.instant();
        var deadlineBase = session.startedAt().isAfter(attemptStartedAt)
                ? session.startedAt()
                : attemptStartedAt;
        var deadline = deadlineBase.plus(version.maximumDuration());
        var messages = new ArrayList<>(executionStore.messages(session.debateId()));
        var turnIndexes = turnIndexes(agents);
        var partial = messages.stream().anyMatch(message -> message.status() == AgentMessageStatus.FAILED);
        var completedRounds = 0;

        publishStageStarted(execution.runId(), WorkflowStage.DEBATE, agents.getFirst().nodeId());
        for (var round = 1; round <= version.defaultDebateRounds(); round++) {
            partial = executeRound(
                    execution,
                    session,
                    layers,
                    turnIndexes,
                    messages,
                    round,
                    deadline) || partial;
            completedRounds++;
            TaskCancellationContext.throwIfCancelled();
            executionStore.updateDebate(
                    session.debateId(),
                    DebateStatus.RUNNING,
                    completedRounds,
                    null);
            publishProgress(
                    execution.runId(),
                    chair.nodeId(),
                    Math.min(85, completedRounds * 80 / maximumRounds),
                    "已完成第 " + completedRounds + " / " + maximumRounds + " 轮辩论");
        }

        var loopRoundOffset = version.defaultDebateRounds();
        for (var loopEdge : version.edges().stream()
                .filter(io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition::loopEdge)
                .sorted(Comparator.comparing(edge -> edge.edgeId().value()))
                .toList()) {
            for (var traversal = 1; traversal <= loopEdge.maximumTraversals(); traversal++) {
                var loopRound = loopRoundOffset + traversal;
                var alreadyStarted = messages.stream().anyMatch(message -> message.roundIndex() == loopRound);
                if (!alreadyStarted && !conditionEvaluator.passes(
                        execution,
                        loopEdge,
                        Math.max(1, latestRound(messages)),
                        List.copyOf(messages))) {
                    break;
                }
                partial = executeRound(
                        execution,
                        session,
                        layers,
                        turnIndexes,
                        messages,
                        loopRound,
                        deadline) || partial;
                completedRounds++;
                TaskCancellationContext.throwIfCancelled();
                executionStore.updateDebate(
                        session.debateId(),
                        DebateStatus.RUNNING,
                        completedRounds,
                        null);
                publishProgress(
                        execution.runId(),
                        chair.nodeId(),
                        Math.min(85, completedRounds * 80 / maximumRounds),
                        "条件循环已触发第 " + traversal + " 次修订，累计完成 "
                                + completedRounds + " / " + maximumRounds + " 轮");
            }
            loopRoundOffset += loopEdge.maximumTraversals();
        }

        if (!conditionEvaluator.isActive(
                execution,
                chair,
                Math.max(1, latestRound(messages)),
                List.copyOf(messages))) {
            throw new TerminalWorkflowFailure(
                    "WORKFLOW_CHAIR_NOT_ACTIVATED",
                    "Workflow edge conditions did not activate the chair node",
                    false);
        }
        var chairMessage = executeChair(
                execution,
                session,
                chair,
                agents.size() + 1,
                List.copyOf(messages),
                deadline,
                Math.max(1, latestRound(messages)));
        if (messages.stream().noneMatch(message -> message.messageId().equals(chairMessage.messageId()))) {
            messages.add(chairMessage);
        }
        publishProgress(execution.runId(), chair.nodeId(), 95, "主席已完成独立仲裁");
        var completedAt = clock.instant();
        TaskCancellationContext.throwIfCancelled();
        executionStore.updateDebate(
                session.debateId(),
                partial ? DebateStatus.PARTIAL : DebateStatus.COMPLETED,
                completedRounds,
                completedAt);
        executionStore.completeRun(execution.runId(), partial, completedAt);
        eventPublisher.publish(execution.runId(), (eventId, sequence, occurredAt) ->
                new WorkflowCompleted(
                        eventId,
                        execution.runId(),
                        sequence,
                        "debate:" + session.debateId().value(),
                        occurredAt));
    }

    private DebateSession ensureDebate(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition chair) {
        var existing = executionStore.findDebate(execution.runId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        var proposed = new DebateSession(
                WorkflowExecutionIds.debate(execution.runId()),
                execution.runId(),
                DebateStatus.RUNNING,
                maximumDebateRounds(execution.definitionVersion()),
                0,
                chair.nodeId(),
                clock.instant(),
                null);
        executionStore.startDebate(proposed);
        return executionStore.findDebate(execution.runId()).orElse(proposed);
    }

    private boolean executeRound(
            WorkflowExecutionContext execution,
            DebateSession session,
            List<List<WorkflowNodeDefinition>> layers,
            Map<WorkflowNodeId, Integer> turnIndexes,
            List<AgentMessage> messages,
            int round,
            Instant deadline) {
        var partial = false;
        for (var layer : layers) {
            var visibleMessages = List.copyOf(messages);
            var activeNodes = layer.stream()
                    .filter(node -> conditionEvaluator.isActive(
                            execution,
                            node,
                            round,
                            visibleMessages))
                    .toList();
            layer.stream()
                    .filter(node -> !activeNodes.contains(node))
                    .forEach(node -> checkpoints.skipped(
                            execution.runId(),
                            node,
                            round,
                            "Incoming workflow edge conditions were not satisfied"));
            var futures = activeNodes.stream()
                    .map(node -> CompletableFuture.supplyAsync(
                            () -> executeAgentWithPolicy(
                                    execution,
                                    session,
                                    node,
                                    round,
                                    turnIndexes.get(node.nodeId()),
                                    visibleMessages,
                                    deadline),
                            executor))
                    .toList();
            awaitLayer(futures);
            var results = futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(result -> result.message().turnIndex()))
                    .toList();
            results.forEach(result -> {
                if (messages.stream().noneMatch(existing ->
                        existing.messageId().equals(result.message().messageId()))) {
                    messages.add(result.message());
                }
            });
            partial = partial || results.stream().anyMatch(NodeResult::partial);
        }
        return partial;
    }

    private NodeResult executeAgentWithPolicy(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition node,
            int round,
            int turnIndex,
            List<AgentMessage> visibleMessages,
            Instant deadline) {
        try {
            return new NodeResult(
                    executeAgent(
                            execution,
                            session,
                            node,
                            round,
                            turnIndex,
                            visibleMessages,
                            deadline),
                    false);
        } catch (NodeExecutionFailure failure) {
            if (execution.definitionVersion().failurePolicy() == WorkflowFailurePolicy.CONTINUE) {
                var prompt = promptComposer.composeAgent(execution, node, round, visibleMessages);
                var message = new AgentMessage(
                        WorkflowExecutionIds.message(execution.runId(), node.nodeId(), round),
                        session.debateId(),
                        execution.runId(),
                        node.nodeId(),
                        roleName(node),
                        round,
                        turnIndex,
                        round == 1 ? AgentMessageType.ARGUMENT : AgentMessageType.REVISION,
                        AgentMessageStatus.FAILED,
                        new AgentMessageContent(
                                "角色执行失败",
                                failure.getMessage(),
                                null,
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        prompt.repliesTo(),
                        clock.instant());
                TaskCancellationContext.throwIfCancelled();
                executionStore.saveMessage(message);
                publishMessage(message, round);
                return new NodeResult(message, true);
            }
            if (execution.definitionVersion().failurePolicy() == WorkflowFailurePolicy.REPLAN) {
                throw new TerminalWorkflowFailure(
                        "WORKFLOW_REPLAN_REQUIRED",
                        "A workflow node failed and requested replanning: " + failure.getMessage(),
                        false);
            }
            throw terminalFailure(failure);
        }
    }

    private AgentMessage executeAgent(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition node,
            int round,
            int turnIndex,
            List<AgentMessage> visibleMessages,
            Instant deadline) {
        var messageId = WorkflowExecutionIds.message(execution.runId(), node.nodeId(), round);
        var existingMessage = findMessage(visibleMessages, messageId)
                .or(() -> findMessage(executionStore.messages(session.debateId()), messageId));
        if (existingMessage.isPresent()) {
            checkpoints.healCompleted(execution.runId(), node, round, existingMessage.orElseThrow());
            return existingMessage.orElseThrow();
        }
        var prompt = promptComposer.composeAgent(execution, node, round, visibleMessages);
        var content = invokeWithRetry(execution, node, round, prompt.userPrompt(), deadline, false);
        var message = new AgentMessage(
                messageId,
                session.debateId(),
                execution.runId(),
                node.nodeId(),
                roleName(node),
                round,
                turnIndex,
                round == 1 ? AgentMessageType.ARGUMENT : AgentMessageType.REVISION,
                AgentMessageStatus.COMPLETED,
                content,
                prompt.repliesTo(),
                clock.instant());
        TaskCancellationContext.throwIfCancelled();
        executionStore.saveMessage(message);
        checkpoints.completed(execution.runId(), node, round, message.content().summary());
        publishMessage(message, round);
        return message;
    }

    private AgentMessage executeChair(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition chair,
            int turnIndex,
            List<AgentMessage> messages,
            Instant deadline,
            int eventRound) {
        var messageId = WorkflowExecutionIds.message(execution.runId(), chair.nodeId(), 0);
        var existing = findMessage(messages, messageId)
                .or(() -> findMessage(executionStore.messages(session.debateId()), messageId));
        if (existing.isPresent()) {
            checkpoints.healCompleted(execution.runId(), chair, 0, existing.orElseThrow());
            return existing.orElseThrow();
        }
        publishStageStarted(execution.runId(), WorkflowStage.PRODUCT_SELECTION, chair.nodeId());
        var prompt = promptComposer.composeChair(execution, chair, messages);
        AgentMessageContent content;
        try {
            content = invokeWithRetry(execution, chair, 0, prompt.userPrompt(), deadline, true);
        } catch (NodeExecutionFailure failure) {
            throw terminalFailure(failure);
        }
        var message = new AgentMessage(
                messageId,
                session.debateId(),
                execution.runId(),
                chair.nodeId(),
                roleName(chair),
                0,
                turnIndex,
                AgentMessageType.CHAIR_VERDICT,
                AgentMessageStatus.COMPLETED,
                content,
                prompt.repliesTo(),
                clock.instant());
        TaskCancellationContext.throwIfCancelled();
        executionStore.saveMessage(message);
        checkpoints.completed(execution.runId(), chair, 0, message.content().summary());
        publishMessage(message, eventRound);
        return message;
    }

    private static AgentMessageContent normalizeForecastReference(
            AgentMessageContent content,
            WorkflowExecutionContext execution) {
        var forecast = content.forecast();
        if (forecast == null
                || execution.marketScope() == null
                || forecast.direction() == io.omnnu.finbot.domain.research.ForecastDirection.UNCERTAIN) {
            return content;
        }
        var normalized = new io.omnnu.finbot.domain.research.ForecastSignal(
                forecast.direction(),
                execution.marketScope().marketReferencePrice(),
                forecast.expectedLow(),
                forecast.expectedHigh(),
                forecast.invalidationPrice(),
                forecast.confidence(),
                forecast.thesis(),
                forecast.evidenceReferences());
        return new AgentMessageContent(
                content.summary(),
                content.argument(),
                content.confidence(),
                content.claims(),
                content.evidenceReferences(),
                content.challenges(),
                content.revisionNotes(),
                normalized);
    }

    private AgentMessageContent invokeWithRetry(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            int round,
            String userPrompt,
            Instant deadline,
            boolean chair) {
        var checkpoint = executionStore.findCheckpoint(execution.runId(), node.nodeId(), round, 0);
        var totalAttempts = aiExecution.totalAttempts(node);
        var firstAttempt = checkpoint.map(value -> Math.min(
                        totalAttempts,
                        value.attempt() + 1))
                .orElse(1);
        try {
            return aiExecution.execute(
                    execution.runId(),
                    execution.definitionVersion(),
                    node,
                    userPrompt,
                    deadline,
                    firstAttempt,
                    output -> {
                        var parsed = chair ? outputParser.parseChair(output) : outputParser.parseAgent(output);
                        if (chair && execution.marketScope() != null && parsed.forecast() == null) {
                            throw new IllegalArgumentException(
                                    "Market analysis chair did not return the required structured forecast");
                        }
                        return chair ? normalizeForecastReference(parsed, execution) : parsed;
                    },
                    (attempt, binding) -> checkpoints.running(
                            execution.runId(),
                            node,
                            round,
                            attempt,
                            checkpoint.orElse(null)))
                    .parsed();
        } catch (AiExecutionFailure failure) {
            var nodeFailure = new NodeExecutionFailure(
                    failure.errorCode(),
                    failure.getMessage(),
                    failure.retryable());
            checkpoints.failed(
                    execution.runId(),
                    node,
                    round,
                    nodeFailure.errorCode(),
                    nodeFailure.getMessage());
            throw nodeFailure;
        }
    }

    private void publishMessage(AgentMessage message, int eventRound) {
        TaskCancellationContext.throwIfCancelled();
        eventPublisher.publish(message.runId(), (eventId, sequence, occurredAt) ->
                new AgentMessagePublished(
                        eventId,
                        message.runId(),
                        sequence,
                        message.nodeId(),
                        message.roleName(),
                        eventRound,
                        message.content().summary(),
                        occurredAt));
    }

    private void publishStageStarted(
            WorkflowRunId runId,
            WorkflowStage stage,
            WorkflowNodeId nodeId) {
        TaskCancellationContext.throwIfCancelled();
        eventPublisher.publish(runId, (eventId, sequence, occurredAt) ->
                new WorkflowStageStarted(eventId, runId, sequence, stage, nodeId, occurredAt));
    }

    private void publishProgress(
            WorkflowRunId runId,
            WorkflowNodeId nodeId,
            int percent,
            String summary) {
        TaskCancellationContext.throwIfCancelled();
        eventPublisher.publish(runId, (eventId, sequence, occurredAt) ->
                new WorkflowProgressed(
                        eventId,
                        runId,
                        sequence,
                        WorkflowStage.DEBATE,
                        nodeId,
                        percent,
                        summary,
                        occurredAt));
    }

    private void terminalize(WorkflowRunId runId, TerminalWorkflowFailure failure) {
        TaskCancellationContext.throwIfCancelled();
        var failedAt = clock.instant();
        executionStore.findDebate(runId).ifPresent(session -> executionStore.updateDebate(
                session.debateId(),
                DebateStatus.FAILED,
                session.completedRounds(),
                failedAt));
        workflowFailure.fail(
                runId,
                failure.errorCode(),
                failure.getMessage(),
                failure.retryable(),
                failedAt);
    }

    private static void awaitLayer(List<CompletableFuture<NodeResult>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof TerminalWorkflowFailure failure) {
                throw failure;
            }
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private static boolean terminalOrPaused(WorkflowRunStatus status) {
        return switch (status) {
            case ACCEPTED, RUNNING -> false;
            case WAITING_HUMAN, PARTIAL, COMPLETED, FAILED, CANCELLED -> true;
        };
    }

    private static TerminalWorkflowFailure terminalFailure(NodeExecutionFailure failure) {
        return new TerminalWorkflowFailure(
                failure.errorCode(),
                failure.getMessage(),
                failure.retryable());
    }

    private record NodeResult(AgentMessage message, boolean partial) {
    }

    private static final class NodeExecutionFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String errorCode;
        private final boolean retryable;

        private NodeExecutionFailure(String errorCode, String safeMessage, boolean retryable) {
            super(Objects.requireNonNull(safeMessage, "safeMessage"));
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
            this.retryable = retryable;
        }

        private String errorCode() {
            return errorCode;
        }

        private boolean retryable() {
            return retryable;
        }
    }

    private static final class TerminalWorkflowFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String errorCode;
        private final boolean retryable;

        private TerminalWorkflowFailure(String errorCode, String safeMessage, boolean retryable) {
            super(Objects.requireNonNull(safeMessage, "safeMessage"));
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
            this.retryable = retryable;
        }

        private String errorCode() {
            return errorCode;
        }

        private boolean retryable() {
            return retryable;
        }
    }
}
