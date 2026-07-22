package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.dto.WorkflowCheckpoint;
import io.omnnu.finbot.application.workflow.dto.WorkflowEstimate;
import io.omnnu.finbot.application.workflow.dto.WorkflowExecutionPlan;
import io.omnnu.finbot.application.workflow.dto.WorkflowNodeTestResult;
import io.omnnu.finbot.application.workflow.exception.WorkflowNotFoundException;
import io.omnnu.finbot.application.workflow.port.in.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowDiagnosticsUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowManagementUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowRunFailureUseCase;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;

import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.configuration.port.in.ConfigurationUseCase;
import io.omnnu.finbot.application.shared.service.IdempotencyKeys;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointId;
import io.omnnu.finbot.domain.workflow.WorkflowCheckpointStatus;
import io.omnnu.finbot.domain.workflow.WorkflowCompleted;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class WorkflowDiagnosticsService implements WorkflowDiagnosticsUseCase {
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final int CONTEXT_TOKENS_PER_MESSAGE = 512;

    private final WorkflowManagementUseCase management;
    private final ConfigurationUseCase configuration;
    private final StartWorkflowUseCase startWorkflow;
    private final WorkflowExecutionStore executionStore;
    private final WorkflowRunFailureUseCase failureUseCase;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowAiInvoker aiInvoker;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;
    private final Executor executor;

    public WorkflowDiagnosticsService(
            WorkflowManagementUseCase management,
            ConfigurationUseCase configuration,
            StartWorkflowUseCase startWorkflow,
            WorkflowExecutionStore executionStore,
            WorkflowRunFailureUseCase failureUseCase,
            WorkflowEventPublisher eventPublisher,
            WorkflowAiInvoker aiInvoker,
            SortableIdGenerator idGenerator,
            Clock clock,
            Executor executor) {
        this.management = Objects.requireNonNull(management, "management");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.startWorkflow = Objects.requireNonNull(startWorkflow, "startWorkflow");
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.failureUseCase = Objects.requireNonNull(failureUseCase, "failureUseCase");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.aiInvoker = Objects.requireNonNull(aiInvoker, "aiInvoker");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public WorkflowEstimate estimate(WorkflowVersionId versionId) {
        var version = management.version(versionId);
        var rates = modelRates();
        var warnings = new ArrayList<String>();
        var maximumDebateRounds = version.defaultDebateRounds() + version.edges().stream()
                .filter(io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition::loopEdge)
                .map(io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition::maximumTraversals)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        var estimates = version.nodes().stream()
                .filter(node -> node.enabled() && node.nodeType().llmBacked())
                .map(node -> nodeEstimate(maximumDebateRounds, node, rates, warnings))
                .toList();
        var calls = estimates.stream().mapToLong(WorkflowEstimate.NodeEstimate::estimatedCalls).sum();
        var inputTokens = estimates.stream().mapToLong(WorkflowEstimate.NodeEstimate::estimatedInputTokens).sum();
        var outputTokens = estimates.stream().mapToLong(WorkflowEstimate.NodeEstimate::maximumOutputTokens).sum();
        var primaryCost = estimates.stream()
                .map(WorkflowEstimate.NodeEstimate::primaryCostUsd)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var fallbackCost = estimates.stream()
                .map(WorkflowEstimate.NodeEstimate::fallbackCostUsd)
                .filter(Objects::nonNull)
                .reduce(primaryCost, BigDecimal::add);
        if (inputTokens + outputTokens > version.maximumTokens()) {
            warnings.add("按最大输出估算的 Token 可能超过工作流硬预算");
        }
        if (fallbackCost.compareTo(version.maximumCostUsd()) > 0) {
            warnings.add("主模型加兜底模型最坏成本可能超过工作流成本上限");
        }
        return new WorkflowEstimate(
                version.versionId().value(),
                version.defaultDebateRounds(),
                calls,
                inputTokens,
                outputTokens,
                money(primaryCost),
                money(fallbackCost),
                version.maximumCostUsd(),
                version.maximumTokens(),
                estimates,
                warnings);
    }

    @Override
    public WorkflowExecutionPlan plan(WorkflowVersionId versionId) {
        var version = management.version(versionId);
        var warnings = new ArrayList<String>();
        try {
            WorkflowPublicationValidator.validate(version);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            warnings.add(exception.getMessage());
        }
        var ordered = version.topologicalNodes();
        var nodes = new ArrayList<WorkflowExecutionPlan.PlannedNode>(ordered.size());
        for (var index = 0; index < ordered.size(); index++) {
            var node = ordered.get(index);
            var primary = node.primaryAiBinding();
            var fallback = node.fallbackAiBinding();
            nodes.add(new WorkflowExecutionPlan.PlannedNode(
                    index + 1,
                    node.nodeId().value(),
                    node.displayName(),
                    node.nodeType().name(),
                    runtimeHandler(node.nodeType()),
                    invocationPolicy(node.nodeType(), version.defaultDebateRounds()),
                    version.edges().stream()
                            .filter(edge -> !edge.loopEdge())
                            .filter(edge -> edge.targetNodeId().equals(node.nodeId()))
                            .map(edge -> edge.sourceNodeId().value())
                            .sorted()
                            .toList(),
                    provider(primary),
                    model(primary),
                    primary == null ? null : primary.reasoningEffort().name(),
                    provider(fallback),
                    model(fallback),
                    node.enabled()));
        }
        var maximumRounds = version.defaultDebateRounds() + version.edges().stream()
                .filter(io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition::loopEdge)
                .map(io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition::maximumTraversals)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        return new WorkflowExecutionPlan(
                version.versionId().value(),
                version.defaultDebateRounds(),
                maximumRounds,
                version.maximumSteps(),
                version.maximumTokens(),
                version.maximumCostUsd().toPlainString(),
                nodes,
                warnings);
    }

    @Override
    public CompletionStage<WorkflowNodeTestResult> testNode(
            WorkflowVersionId versionId,
            WorkflowNodeId nodeId,
            String userPrompt,
            String idempotencyKey) {
        var version = management.version(versionId);
        var node = version.nodes().stream()
                .filter(candidate -> candidate.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new WorkflowNotFoundException("工作流节点不存在"));
        if (!node.enabled() || !node.nodeType().llmBacked() || node.primaryAiBinding() == null) {
            throw new IllegalArgumentException("Only an enabled LLM-backed node can be tested");
        }
        var prompt = Objects.requireNonNull(userPrompt, "userPrompt").strip();
        if (prompt.isEmpty() || prompt.length() > 20_000) {
            throw new IllegalArgumentException("userPrompt must contain 1 to 20000 characters");
        }
        var command = new StartWorkflowCommand(
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.API,
                versionId,
                "[节点测试] " + node.displayName(),
                IdempotencyKeys.scoped("workflow-node-test", idempotencyKey));
        return startWorkflow.start(command)
                .thenApplyAsync(started -> executeNodeTest(started, version, node, prompt), executor);
    }

    private WorkflowNodeTestResult executeNodeTest(
            StartWorkflowResult started,
            io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion version,
            io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition node,
            String prompt) {
        var startedAt = clock.instant();
        if (!executionStore.markRunning(started.runId(), startedAt)) {
            throw new IllegalStateException("Unable to start workflow node test run");
        }
        var checkpointId = new WorkflowCheckpointId(idGenerator.next("checkpoint_"));
        executionStore.saveCheckpoint(new WorkflowCheckpoint(
                checkpointId,
                started.runId(),
                node.nodeId(),
                0,
                0,
                1,
                WorkflowCheckpointStatus.RUNNING,
                null,
                null,
                null,
                startedAt,
                null,
                startedAt));
        try {
            var result = aiInvoker.invokeDetailed(
                    started.runId(),
                    version,
                    node,
                    prompt,
                    startedAt.plus(Duration.ofSeconds(node.timeoutSeconds())));
            var completedAt = clock.instant();
            executionStore.saveCheckpoint(new WorkflowCheckpoint(
                    checkpointId,
                    started.runId(),
                    node.nodeId(),
                    0,
                    0,
                    1,
                    WorkflowCheckpointStatus.COMPLETED,
                    summarize(result.output()),
                    null,
                    null,
                    startedAt,
                    completedAt,
                    completedAt));
            executionStore.completeRun(started.runId(), false, completedAt);
            eventPublisher.publish(started.runId(), (eventId, sequence, occurredAt) ->
                    new WorkflowCompleted(
                            eventId,
                            started.runId(),
                            sequence,
                            "node-test:" + result.invocationId().value(),
                            occurredAt));
            return new WorkflowNodeTestResult(
                    started.runId().value(),
                    version.versionId().value(),
                    node.nodeId().value(),
                    WorkflowRunStatus.COMPLETED.name(),
                    result.invocationId().value(),
                    result.output(),
                    null,
                    null,
                    startedAt,
                    completedAt);
        } catch (RuntimeException exception) {
            var completedAt = clock.instant();
            var safeMessage = safeMessage(exception);
            executionStore.saveCheckpoint(new WorkflowCheckpoint(
                    checkpointId,
                    started.runId(),
                    node.nodeId(),
                    0,
                    0,
                    1,
                    WorkflowCheckpointStatus.FAILED,
                    null,
                    "WORKFLOW_NODE_TEST_FAILED",
                    safeMessage,
                    startedAt,
                    completedAt,
                    completedAt));
            failureUseCase.fail(
                    started.runId(),
                    "WORKFLOW_NODE_TEST_FAILED",
                    safeMessage,
                    false,
                    completedAt);
            return new WorkflowNodeTestResult(
                    started.runId().value(),
                    version.versionId().value(),
                    node.nodeId().value(),
                    WorkflowRunStatus.FAILED.name(),
                    null,
                    null,
                    "WORKFLOW_NODE_TEST_FAILED",
                    safeMessage,
                    startedAt,
                    completedAt);
        }
    }

    private Map<ModelKey, ModelRates> modelRates() {
        var rates = new HashMap<ModelKey, ModelRates>();
        configuration.snapshot().models().forEach(model -> rates.put(
                new ModelKey(model.providerProfileId(), model.modelName()),
                new ModelRates(model.inputUsdPerMillion(), model.outputUsdPerMillion())));
        return Map.copyOf(rates);
    }

    private static WorkflowEstimate.NodeEstimate nodeEstimate(
            int debateRounds,
            io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition node,
            Map<ModelKey, ModelRates> rates,
            List<String> warnings) {
        var calls = switch (node.nodeType()) {
            case AGENT, AGGREGATOR -> debateRounds;
            case AI_CLEANER, COMPRESSOR, COMPRESSION_VALIDATOR -> 12L;
            default -> 1L;
        };
        var promptCharacters = (node.systemPrompt() == null ? 0 : node.systemPrompt().length())
                + (node.userPromptTemplate() == null ? 0 : node.userPromptTemplate().length());
        var inputPerCall = Math.max(
                1L,
                (promptCharacters + 3L) / 4L
                        + (long) node.contextMaximumMessages() * CONTEXT_TOKENS_PER_MESSAGE);
        var inputTokens = Math.multiplyExact(inputPerCall, calls);
        var outputTokens = Math.multiplyExact((long) node.maximumOutputTokens(), calls);
        var primary = rate(node.primaryAiBinding(), rates);
        var fallback = rate(node.fallbackAiBinding(), rates);
        var primaryCost = cost(inputTokens, outputTokens, primary);
        var fallbackCost = cost(inputTokens, outputTokens, fallback);
        var complete = primary != null && (node.fallbackAiBinding() == null || fallback != null);
        if (!complete) {
            warnings.add("节点 " + node.displayName() + " 的模型费率不完整，成本为已知部分");
        }
        return new WorkflowEstimate.NodeEstimate(
                node.nodeId().value(),
                node.displayName(),
                calls,
                inputTokens,
                outputTokens,
                provider(node.primaryAiBinding()),
                model(node.primaryAiBinding()),
                money(primaryCost),
                provider(node.fallbackAiBinding()),
                model(node.fallbackAiBinding()),
                money(fallbackCost),
                complete);
    }

    private static ModelRates rate(AiModelBinding binding, Map<ModelKey, ModelRates> rates) {
        return binding == null ? null : rates.get(new ModelKey(
                binding.providerProfileId().value(), binding.modelName()));
    }

    private static BigDecimal cost(long inputTokens, long outputTokens, ModelRates rates) {
        if (rates == null) {
            return BigDecimal.ZERO;
        }
        return rates.input().multiply(BigDecimal.valueOf(inputTokens)).divide(MILLION)
                .add(rates.output().multiply(BigDecimal.valueOf(outputTokens)).divide(MILLION));
    }

    private static String provider(AiModelBinding binding) {
        return binding == null ? null : binding.providerProfileId().value();
    }

    private static String model(AiModelBinding binding) {
        return binding == null ? null : binding.modelName();
    }

    private static String runtimeHandler(WorkflowNodeType nodeType) {
        return switch (nodeType) {
            case INPUT, OUTPUT -> "WORKFLOW_STATE";
            case COLLECTOR, CLEANER -> "INGESTION_PIPELINE";
            case AI_CLEANER, COMPRESSOR, COMPRESSION_VALIDATOR -> "AI_EVIDENCE_CONSENSUS";
            case QUANT -> "PYTHON_QUANT_HTTP";
            case AGENT, AGGREGATOR, CHAIR -> "MULTI_ROUND_DEBATE";
            case EXECUTION_REVIEW -> "TRADE_EXECUTION_AI";
            default -> "NO_RUNTIME_EXECUTOR";
        };
    }

    private static String invocationPolicy(WorkflowNodeType nodeType, int debateRounds) {
        return switch (nodeType) {
            case AI_CLEANER, COMPRESSOR -> "EACH_COLLECTED_DOCUMENT";
            case COMPRESSION_VALIDATOR -> "ONCE_PER_DOCUMENT_AFTER_CANDIDATES";
            case AGENT, AGGREGATOR -> "EACH_DEBATE_ROUND:" + debateRounds;
            case CHAIR -> "ONCE_AFTER_DEBATE";
            case EXECUTION_REVIEW -> "ONCE_AFTER_CHAIR";
            case COLLECTOR, CLEANER, QUANT -> "ONCE_BEFORE_DEBATE";
            case INPUT, OUTPUT -> "ONCE";
            default -> "NOT_EXECUTED";
        };
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(8, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }

    private static String summarize(String output) {
        var normalized = output.strip();
        return normalized.substring(0, Math.min(normalized.length(), 2000));
    }

    private static String safeMessage(RuntimeException exception) {
        var message = exception.getMessage();
        var normalized = message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.strip();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }

    private record ModelKey(String provider, String model) {
    }

    private record ModelRates(BigDecimal input, BigDecimal output) {
    }
}
