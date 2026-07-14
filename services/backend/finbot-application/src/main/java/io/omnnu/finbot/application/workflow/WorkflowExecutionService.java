package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.application.ai.WorkflowAiInvoker;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker.AiInvocationRejectedException;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class WorkflowExecutionService implements WorkflowExecutionUseCase {
    private final WorkflowExecutionStore executionStore;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowRunFailureUseCase workflowFailure;
    private final WorkflowAiInvoker aiInvoker;
    private final StructuredAiOutputParser outputParser;
    private final Clock clock;
    private final Executor executor;
    private final WorkflowPromptComposer promptComposer = new WorkflowPromptComposer();

    public WorkflowExecutionService(
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowRunFailureUseCase workflowFailure,
            WorkflowAiInvoker aiInvoker,
            StructuredAiOutputParser outputParser,
            Clock clock,
            Executor executor) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.workflowFailure = Objects.requireNonNull(workflowFailure, "workflowFailure");
        this.aiInvoker = Objects.requireNonNull(aiInvoker, "aiInvoker");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
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
        var requiredSteps = Math.addExact(
                Math.multiplyExact(agents.size(), version.defaultDebateRounds()),
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
        var attemptStartedAt = clock.instant();
        var deadlineBase = session.startedAt().isAfter(attemptStartedAt)
                ? session.startedAt()
                : attemptStartedAt;
        var deadline = deadlineBase.plus(version.maximumDuration());
        var messages = new ArrayList<>(executionStore.messages(session.debateId()));
        var turnIndexes = turnIndexes(agents);
        var partial = messages.stream().anyMatch(message -> message.status() == AgentMessageStatus.FAILED);

        publishStageStarted(execution.runId(), WorkflowStage.DEBATE, agents.getFirst().nodeId());
        for (var round = 1; round <= session.configuredRounds(); round++) {
            var currentRound = round;
            for (var layer : layers) {
                var visibleMessages = List.copyOf(messages);
                var futures = layer.stream()
                        .map(node -> CompletableFuture.supplyAsync(
                                () -> executeAgentWithPolicy(
                                        execution,
                                        session,
                                        node,
                                        currentRound,
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
            executionStore.updateDebate(
                    session.debateId(),
                    DebateStatus.RUNNING,
                    currentRound,
                    null);
            publishProgress(
                    execution.runId(),
                    chair.nodeId(),
                    Math.min(85, currentRound * 80 / session.configuredRounds()),
                    "已完成第 " + currentRound + " / " + session.configuredRounds() + " 轮辩论");
        }

        var chairMessage = executeChair(
                execution,
                session,
                chair,
                agents.size() + 1,
                List.copyOf(messages),
                deadline);
        if (messages.stream().noneMatch(message -> message.messageId().equals(chairMessage.messageId()))) {
            messages.add(chairMessage);
        }
        publishProgress(execution.runId(), chair.nodeId(), 95, "主席已完成独立仲裁");
        var completedAt = clock.instant();
        executionStore.updateDebate(
                session.debateId(),
                partial ? DebateStatus.PARTIAL : DebateStatus.COMPLETED,
                session.configuredRounds(),
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
                execution.definitionVersion().defaultDebateRounds(),
                0,
                chair.nodeId(),
                clock.instant(),
                null);
        executionStore.startDebate(proposed);
        return executionStore.findDebate(execution.runId()).orElse(proposed);
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
            healCompletedCheckpoint(execution.runId(), node, round, existingMessage.orElseThrow());
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
        executionStore.saveMessage(message);
        saveCompletedCheckpoint(execution.runId(), node, round, message.content().summary());
        publishMessage(message, round);
        return message;
    }

    private AgentMessage executeChair(
            WorkflowExecutionContext execution,
            DebateSession session,
            WorkflowNodeDefinition chair,
            int turnIndex,
            List<AgentMessage> messages,
            Instant deadline) {
        var messageId = WorkflowExecutionIds.message(execution.runId(), chair.nodeId(), 0);
        var existing = findMessage(messages, messageId)
                .or(() -> findMessage(executionStore.messages(session.debateId()), messageId));
        if (existing.isPresent()) {
            healCompletedCheckpoint(execution.runId(), chair, 0, existing.orElseThrow());
            return existing.orElseThrow();
        }
        publishStageStarted(execution.runId(), WorkflowStage.PRODUCT_SELECTION, chair.nodeId());
        var prompt = promptComposer.composeChair(execution, chair, messages);
        final AgentMessageContent content;
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
        executionStore.saveMessage(message);
        saveCompletedCheckpoint(execution.runId(), chair, 0, message.content().summary());
        publishMessage(message, session.configuredRounds());
        return message;
    }

    private AgentMessageContent invokeWithRetry(
            WorkflowExecutionContext execution,
            WorkflowNodeDefinition node,
            int round,
            String userPrompt,
            Instant deadline,
            boolean chair) {
        var checkpoint = executionStore.findCheckpoint(execution.runId(), node.nodeId(), round, 0);
        var firstAttempt = checkpoint.map(value -> Math.min(
                        node.retryPolicy().maximumAttempts(),
                        value.attempt() + 1))
                .orElse(1);
        NodeExecutionFailure lastFailure = null;
        for (var attempt = firstAttempt; attempt <= node.retryPolicy().maximumAttempts(); attempt++) {
            saveRunningCheckpoint(execution.runId(), node, round, attempt, checkpoint.orElse(null));
            try {
                var output = aiInvoker.invoke(
                        execution.runId(),
                        execution.definitionVersion(),
                        node,
                        userPrompt,
                        deadline);
                return chair ? outputParser.parseChair(output) : outputParser.parseAgent(output);
            } catch (AiInvocationRejectedException exception) {
                lastFailure = new NodeExecutionFailure(
                        exception.errorCode(),
                        exception.getMessage(),
                        exception.retryable());
            } catch (IllegalArgumentException exception) {
                lastFailure = new NodeExecutionFailure(
                        "AI_OUTPUT_SCHEMA_INVALID",
                        "AI output did not match the required structured contract",
                        true);
            }
            if (!lastFailure.retryable() || attempt == node.retryPolicy().maximumAttempts()) {
                break;
            }
            pause(node.retryPolicy().backoff().multipliedBy(attempt));
        }
        var failure = Objects.requireNonNullElseGet(lastFailure, () -> new NodeExecutionFailure(
                "NODE_EXECUTION_FAILED",
                "Workflow node failed without a classified error",
                false));
        saveFailedCheckpoint(execution.runId(), node, round, failure);
        throw failure;
    }

    private void healCompletedCheckpoint(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            AgentMessage message) {
        var checkpoint = executionStore.findCheckpoint(runId, node.nodeId(), round, 0);
        if (checkpoint.isEmpty()
                || checkpoint.orElseThrow().status() != WorkflowCheckpointStatus.COMPLETED) {
            saveCompletedCheckpoint(runId, node, round, message.content().summary());
        }
    }

    private void saveRunningCheckpoint(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            int attempt,
            WorkflowCheckpoint previous) {
        var now = clock.instant();
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                WorkflowExecutionIds.checkpoint(runId, node.nodeId(), round),
                runId,
                node.nodeId(),
                round,
                0,
                attempt,
                WorkflowCheckpointStatus.RUNNING,
                null,
                null,
                null,
                previous == null || previous.startedAt() == null ? now : previous.startedAt(),
                null,
                now));
    }

    private void saveCompletedCheckpoint(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            String summary) {
        var previous = executionStore.findCheckpoint(runId, node.nodeId(), round, 0);
        var now = clock.instant();
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                WorkflowExecutionIds.checkpoint(runId, node.nodeId(), round),
                runId,
                node.nodeId(),
                round,
                0,
                previous.map(WorkflowCheckpoint::attempt).orElse(1),
                WorkflowCheckpointStatus.COMPLETED,
                summary,
                null,
                null,
                previous.map(WorkflowCheckpoint::startedAt).orElse(now),
                now,
                now));
    }

    private void saveFailedCheckpoint(
            WorkflowRunId runId,
            WorkflowNodeDefinition node,
            int round,
            NodeExecutionFailure failure) {
        var previous = executionStore.findCheckpoint(runId, node.nodeId(), round, 0);
        var now = clock.instant();
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                WorkflowExecutionIds.checkpoint(runId, node.nodeId(), round),
                runId,
                node.nodeId(),
                round,
                0,
                previous.map(WorkflowCheckpoint::attempt)
                        .orElse(node.retryPolicy().maximumAttempts()),
                WorkflowCheckpointStatus.FAILED,
                null,
                failure.errorCode(),
                failure.getMessage(),
                previous.map(WorkflowCheckpoint::startedAt).orElse(now),
                now,
                now));
    }

    private void publishMessage(AgentMessage message, int eventRound) {
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
        eventPublisher.publish(runId, (eventId, sequence, occurredAt) ->
                new WorkflowStageStarted(eventId, runId, sequence, stage, nodeId, occurredAt));
    }

    private void publishProgress(
            WorkflowRunId runId,
            WorkflowNodeId nodeId,
            int percent,
            String summary) {
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

    private static List<List<WorkflowNodeDefinition>> agentLayers(WorkflowDefinitionVersion version) {
        var orderedAgents = version.topologicalNodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .filter(node -> node.nodeType() == WorkflowNodeType.AGENT)
                .toList();
        var agentIds = orderedAgents.stream()
                .map(WorkflowNodeDefinition::nodeId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var levelByNode = new HashMap<WorkflowNodeId, Integer>();
        var layers = new TreeMap<Integer, List<WorkflowNodeDefinition>>();
        for (var agent : orderedAgents) {
            var level = version.edges().stream()
                    .filter(edge -> !edge.loopEdge())
                    .filter(edge -> edge.targetNodeId().equals(agent.nodeId()))
                    .filter(edge -> agentIds.contains(edge.sourceNodeId()))
                    .mapToInt(edge -> levelByNode.getOrDefault(edge.sourceNodeId(), 0) + 1)
                    .max()
                    .orElse(0);
            levelByNode.put(agent.nodeId(), level);
            layers.computeIfAbsent(level, ignored -> new ArrayList<>()).add(agent);
        }
        return layers.values().stream()
                .map(layer -> layer.stream()
                        .sorted(Comparator.comparing(node -> node.nodeId().value()))
                        .toList())
                .toList();
    }

    private static Map<WorkflowNodeId, Integer> turnIndexes(List<WorkflowNodeDefinition> agents) {
        var indexes = new LinkedHashMap<WorkflowNodeId, Integer>();
        for (var index = 0; index < agents.size(); index++) {
            indexes.put(agents.get(index).nodeId(), index + 1);
        }
        return Map.copyOf(indexes);
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

    private static Optional<AgentMessage> findMessage(
            List<AgentMessage> messages,
            io.omnnu.finbot.domain.workflow.AgentMessageId messageId) {
        return messages.stream()
                .filter(message -> message.messageId().equals(messageId))
                .findFirst();
    }

    private static String roleName(WorkflowNodeDefinition node) {
        return node.roleName() == null ? node.displayName() : node.roleName();
    }

    private static boolean terminalOrPaused(WorkflowRunStatus status) {
        return switch (status) {
            case ACCEPTED, RUNNING -> false;
            case WAITING_HUMAN, PARTIAL, COMPLETED, FAILED, CANCELLED -> true;
        };
    }

    private static void pause(java.time.Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NodeExecutionFailure(
                    "NODE_RETRY_INTERRUPTED",
                    "Workflow node retry was interrupted",
                    true);
        }
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
