package io.omnnu.finbot.application.trading;

import io.omnnu.finbot.application.ai.AiInvocationResult;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker;
import io.omnnu.finbot.application.exchange.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.workflow.WorkflowExecutionContext;
import io.omnnu.finbot.application.workflow.WorkflowExecutionStore;
import io.omnnu.finbot.domain.market.Quantity;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.risk.MarginRiskEngine;
import io.omnnu.finbot.domain.risk.RiskAssessmentId;
import io.omnnu.finbot.domain.risk.RiskAssessmentStatus;
import io.omnnu.finbot.domain.trading.ApprovalStatus;
import io.omnnu.finbot.domain.trading.ApprovedTradeIntent;
import io.omnnu.finbot.domain.trading.ApprovedTradeIntentId;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.DirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.ExecutionReview;
import io.omnnu.finbot.domain.trading.NonDirectionalAction;
import io.omnnu.finbot.domain.trading.NonDirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecisionId;
import io.omnnu.finbot.domain.trading.TradeProposal;
import io.omnnu.finbot.domain.trading.TradeProposalId;
import io.omnnu.finbot.domain.workflow.AgentMessage;
import io.omnnu.finbot.domain.workflow.AgentMessageStatus;
import io.omnnu.finbot.domain.workflow.AgentMessageType;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowRunStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class TradeAutomationApplicationService implements TradeAutomationUseCase {
    private static final int MAXIMUM_PROMPT_CHARACTERS = 180_000;

    private final WorkflowExecutionStore workflowStore;
    private final WorkflowAiInvoker aiInvoker;
    private final TradeDecisionOutputParser outputParser;
    private final TradeAutomationStore store;
    private final PaperOrderExecutionUseCase orderExecution;
    private final MarginRiskEngine riskEngine;
    private final Clock clock;
    private final Executor executor;

    public TradeAutomationApplicationService(
            WorkflowExecutionStore workflowStore,
            WorkflowAiInvoker aiInvoker,
            TradeDecisionOutputParser outputParser,
            TradeAutomationStore store,
            PaperOrderExecutionUseCase orderExecution,
            MarginRiskEngine riskEngine,
            Clock clock,
            Executor executor) {
        this.workflowStore = Objects.requireNonNull(workflowStore, "workflowStore");
        this.aiInvoker = Objects.requireNonNull(aiInvoker, "aiInvoker");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
        this.store = Objects.requireNonNull(store, "store");
        this.orderExecution = Objects.requireNonNull(orderExecution, "orderExecution");
        this.riskEngine = Objects.requireNonNull(riskEngine, "riskEngine");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<TradeAutomationResult> execute(WorkflowRunId workflowRunId) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        return CompletableFuture.supplyAsync(() -> executeSynchronously(workflowRunId), executor);
    }

    private TradeAutomationResult executeSynchronously(WorkflowRunId workflowRunId) {
        var existing = store.findTerminal(workflowRunId);
        if (existing.isPresent()) {
            var result = existing.orElseThrow();
            return result.status() == TradeAutomationStatus.ORDER_PLANNED
                    ? submitPlanned(result)
                    : result;
        }
        var automationRunId = deterministicId("automation_", workflowRunId.value());
        store.start(automationRunId, workflowRunId, clock.instant());
        try {
            var workflow = completedWorkflow(workflowRunId);
            var chair = chairMessage(workflowRunId);
            var stages = store.executionAiStages().stream()
                    .filter(TradeExecutionAiStageConfig::enabled)
                    .sorted(Comparator.comparing(TradeExecutionAiStageConfig::stage))
                    .toList();
            var draftStage = requiredStage(stages, TradeExecutionAiStage.DRAFT);
            var reflectionStage = requiredStage(stages, TradeExecutionAiStage.REFLECTION);

            var draftInvocation = invoke(
                    workflow,
                    draftStage,
                    draftPrompt(workflow, chair));
            var parsedDraft = outputParser.parseDraft(draftInvocation.output());
            saveReview(
                    automationRunId,
                    workflowRunId,
                    draftStage.stage(),
                    draftInvocation,
                    parsedDraft.canonicalJson());

            var reflectionInvocation = invoke(
                    workflow,
                    reflectionStage,
                    reflectionPrompt(workflow, chair, parsedDraft.canonicalJson()));
            var parsedReflection = outputParser.parseReflection(reflectionInvocation.output());
            saveReview(
                    automationRunId,
                    workflowRunId,
                    reflectionStage.stage(),
                    reflectionInvocation,
                    parsedReflection.canonicalJson());

            var finalDraft = reflectedDecision(parsedDraft.decision(), parsedReflection.reflection());
            var decision = toDecision(workflowRunId, finalDraft);
            store.saveDecision(workflowRunId, decision);
            if (!(decision instanceof DirectionalTradeDecision directional)) {
                var reasons = decision.rationale();
                store.complete(
                        automationRunId,
                        TradeAutomationStatus.NO_ACTION,
                        decision,
                        null,
                        List.of(),
                        List.of(),
                        reasons,
                        clock.instant());
                return result(automationRunId, TradeAutomationStatus.NO_ACTION, decision, List.of(), reasons);
            }
            return submitPlanned(planOrders(automationRunId, workflowRunId, directional));
        } catch (RuntimeException exception) {
            store.fail(
                    automationRunId,
                    "TRADE_AUTOMATION_FAILED",
                    failureMessage(exception),
                    clock.instant());
            throw exception;
        }
    }

    private TradeAutomationResult submitPlanned(TradeAutomationResult planned) {
        if (planned.plannedOrderIds().isEmpty()) {
            return planned;
        }
        var results = orderExecution.submitAll(planned.plannedOrderIds())
                .toCompletableFuture()
                .join();
        store.recordExecutionResults(planned.automationRunId(), results, clock.instant());
        var acknowledged = results.stream()
                .filter(result -> result.status() == ExchangeSubmissionStatus.ACKNOWLEDGED)
                .count();
        var unknown = results.stream()
                .filter(result -> result.status() == ExchangeSubmissionStatus.UNKNOWN)
                .count();
        var status = acknowledged > 0
                ? TradeAutomationStatus.SUBMITTED
                : unknown > 0
                        ? TradeAutomationStatus.ORDER_PLANNED
                        : TradeAutomationStatus.BLOCKED;
        var reasons = results.stream()
                .map(result -> result.orderId().value() + ": "
                        + Objects.requireNonNullElse(result.safeMessage(), result.status().name()))
                .toList();
        return new TradeAutomationResult(
                planned.automationRunId(),
                status,
                planned.decisionId(),
                planned.plannedOrderIds(),
                reasons);
    }

    private TradeAutomationResult planOrders(
            String automationRunId,
            WorkflowRunId workflowRunId,
            DirectionalTradeDecision decision) {
        var proposal = TradeProposal.from(
                new TradeProposalId(deterministicId("proposal_", decision.id().value())),
                decision,
                clock.instant());
        store.saveProposal(proposal);
        var policy = store.activeRiskPolicy();
        var normalizedSymbol = normalizeSymbol(decision.symbol().value());
        var candidates = store.executionCandidates(normalizedSymbol);
        if (candidates.isEmpty()) {
            var reasons = List.of("没有与决策标的匹配且可用的模拟交易账户");
            store.complete(
                    automationRunId,
                    TradeAutomationStatus.BLOCKED,
                    decision,
                    proposal,
                    List.of(),
                    List.of(),
                    reasons,
                    clock.instant());
            return result(automationRunId, TradeAutomationStatus.BLOCKED, decision, List.of(), reasons);
        }

        var assessments = new ArrayList<StoredRiskAssessment>();
        var orders = new ArrayList<PlannedOrder>();
        for (var candidate : candidates) {
            var assessmentId = new RiskAssessmentId(deterministicId(
                    "assessment_",
                    proposal.id().value() + ':' + candidate.accountId().value()));
            var plan = riskEngine.assess(proposal, decision.confidence(), candidate, policy);
            var assessment = new StoredRiskAssessment(
                    assessmentId,
                    automationRunId,
                    workflowRunId,
                    proposal.id(),
                    candidate.accountId(),
                    policy.version(),
                    plan,
                    clock.instant());
            store.saveRiskAssessment(assessment);
            assessments.add(assessment);
            if (plan.status() == RiskAssessmentStatus.BLOCKED) {
                continue;
            }
            var intent = ApprovedTradeIntent.approve(
                    new ApprovedTradeIntentId(deterministicId(
                            "intent_",
                            proposal.id().value() + ':' + candidate.accountId().value())),
                    proposal,
                    new ExecutionReview(
                            ApprovalStatus.APPROVED,
                            plan.reasons(),
                            policy.version(),
                            assessment.assessedAt()),
                    candidate.accountId(),
                    assessmentId,
                    Quantity.positive(plan.quantity()),
                    plan.leverage());
            var orderId = new OrderId(deterministicId("order_", intent.id().value()));
            var order = new PlannedOrder(
                    orderId,
                    intent.id(),
                    "paper-order:" + orderId.value(),
                    candidate.exchange(),
                    candidate.environment(),
                    candidate.accountId(),
                    candidate.symbol(),
                    decision.action(),
                    plan.quantity(),
                    plan.leverage(),
                    clientOrderId(orderId),
                    clock.instant());
            store.saveApprovedIntentAndOrder(intent, order);
            orders.add(order);
        }
        var reasons = assessments.stream()
                .flatMap(assessment -> assessment.plan().reasons().stream()
                        .map(reason -> assessment.accountId().value() + ": " + reason))
                .toList();
        var status = orders.isEmpty()
                ? TradeAutomationStatus.BLOCKED
                : TradeAutomationStatus.ORDER_PLANNED;
        store.complete(
                automationRunId,
                status,
                decision,
                proposal,
                assessments,
                orders,
                reasons,
                clock.instant());
        return result(
                automationRunId,
                status,
                decision,
                orders.stream().map(PlannedOrder::orderId).toList(),
                reasons);
    }

    private AiInvocationResult invoke(
            WorkflowExecutionContext workflow,
            TradeExecutionAiStageConfig stage,
            String prompt) {
        var node = executionNode(stage);
        try {
            return aiInvoker.invokeDetailed(
                    workflow.runId(),
                    workflow.definitionVersion(),
                    node,
                    bounded(stage.userPromptTemplate() + "\n\n" + prompt),
                    clock.instant().plusSeconds(stage.timeoutSeconds()));
        } catch (RuntimeException exception) {
            store.saveExecutionAiFailure(
                    deterministicId(
                            "review_",
                            workflow.runId().value() + ':' + stage.stage().name()),
                    deterministicId("automation_", workflow.runId().value()),
                    workflow.runId(),
                    stage.stage(),
                    "AI_EXECUTION_STAGE_FAILED",
                    "Execution AI stage failed: " + exception.getClass().getSimpleName(),
                    clock.instant());
            throw exception;
        }
    }

    private void saveReview(
            String automationRunId,
            WorkflowRunId workflowRunId,
            TradeExecutionAiStage stage,
            AiInvocationResult invocation,
            String canonicalJson) {
        store.saveExecutionAiReview(new StoredExecutionAiReview(
                deterministicId("review_", workflowRunId.value() + ':' + stage.name()),
                automationRunId,
                workflowRunId,
                stage,
                invocation.invocationId(),
                canonicalJson,
                sha256(canonicalJson),
                clock.instant()));
    }

    private WorkflowExecutionContext completedWorkflow(WorkflowRunId workflowRunId) {
        var workflow = workflowStore.load(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run does not exist"));
        if (workflow.status() != WorkflowRunStatus.COMPLETED
                && workflow.status() != WorkflowRunStatus.PARTIAL) {
            throw new IllegalStateException("Trade automation requires a completed workflow");
        }
        return workflow;
    }

    private AgentMessage chairMessage(WorkflowRunId workflowRunId) {
        var debate = workflowStore.findDebate(workflowRunId)
                .orElseThrow(() -> new IllegalStateException("Workflow has no debate result"));
        return workflowStore.messages(debate.debateId()).stream()
                .filter(message -> message.messageType() == AgentMessageType.CHAIR_VERDICT)
                .filter(message -> message.status() == AgentMessageStatus.COMPLETED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow has no completed chair verdict"));
    }

    private static TradeExecutionAiStageConfig requiredStage(
            List<TradeExecutionAiStageConfig> stages,
            TradeExecutionAiStage required) {
        return stages.stream()
                .filter(stage -> stage.stage() == required)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Required trade execution AI stage is disabled: " + required));
    }

    static WorkflowNodeDefinition executionNode(TradeExecutionAiStageConfig stage) {
        return new WorkflowNodeDefinition(
                new WorkflowNodeId("node_execution_" + stage.stage().name().toLowerCase(Locale.ROOT)),
                WorkflowNodeType.EXECUTION_REVIEW,
                stage.stage() == TradeExecutionAiStage.DRAFT ? "执行决策初审" : "执行决策反思",
                stage.stage() == TradeExecutionAiStage.DRAFT ? "Execution Draft" : "Execution Reflection",
                null,
                stage.providerProfileId(),
                stage.modelName(),
                stage.reasoningEffort(),
                stage.systemPrompt(),
                stage.userPromptTemplate(),
                stage.stage() == TradeExecutionAiStage.DRAFT
                        ? WorkflowOutputContract.TRADE_DECISIONS
                        : WorkflowOutputContract.EXECUTION_VERDICT,
                WorkflowContextMode.UPSTREAM,
                3,
                48,
                stage.maximumOutputTokens(),
                stage.timeoutSeconds(),
                new WorkflowRetryPolicy(1, Duration.ZERO),
                stage.stage().name().toLowerCase(Locale.ROOT),
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                true);
    }

    private static String draftPrompt(WorkflowExecutionContext workflow, AgentMessage chair) {
        return """
                任务：%s

                主席裁决摘要：%s
                主席裁决：%s
                主席证据引用：%s

                可追溯研究制品：%s

                只输出一个 JSON 对象，不得输出 Markdown 或隐藏思维链。
                """.formatted(
                workflow.requestSummary(),
                chair.content().summary(),
                chair.content().argument(),
                chair.content().evidenceReferences(),
                workflow.researchContext());
    }

    private static String reflectionPrompt(
            WorkflowExecutionContext workflow,
            AgentMessage chair,
            String draftJson) {
        return """
                任务：%s
                主席裁决：%s
                初审交易决策：%s
                可追溯研究制品：%s

                独立反思并只输出严格 JSON，不得输出 Markdown 或隐藏思维链。
                """.formatted(
                workflow.requestSummary(),
                chair.content().argument(),
                draftJson,
                workflow.researchContext());
    }

    private static TradeDecisionDraft reflectedDecision(
            TradeDecisionDraft draft,
            TradeReflection reflection) {
        if (reflection.approved()) {
            return reflection.decision();
        }
        return new TradeDecisionDraft(
                NonDirectionalAction.WATCH,
                draft.symbol(),
                draft.confidence(),
                null,
                null,
                null,
                reflection.reasons(),
                draft.evidenceReferences());
    }

    private TradeDecision toDecision(WorkflowRunId workflowRunId, TradeDecisionDraft draft) {
        var id = new TradeDecisionId(deterministicId(
                "decision_",
                workflowRunId.value() + ':' + normalizeSymbol(draft.symbol().value())));
        var rationale = new ArrayList<>(draft.rationale());
        draft.evidenceReferences().forEach(reference -> rationale.add("evidence:" + reference));
        return switch (draft.action()) {
            case DirectionalAction action -> new DirectionalTradeDecision(
                    id,
                    draft.symbol(),
                    action,
                    draft.confidence(),
                    draft.entryReference(),
                    draft.targetPrice(),
                    draft.invalidationPrice(),
                    rationale,
                    clock.instant());
            case NonDirectionalAction action -> new NonDirectionalTradeDecision(
                    id,
                    draft.symbol(),
                    action,
                    draft.confidence(),
                    rationale,
                    clock.instant());
        };
    }

    private static TradeAutomationResult result(
            String automationRunId,
            TradeAutomationStatus status,
            TradeDecision decision,
            List<OrderId> orderIds,
            List<String> reasons) {
        return new TradeAutomationResult(
                automationRunId,
                status,
                decision.id(),
                orderIds,
                reasons);
    }

    private static String clientOrderId(OrderId orderId) {
        var value = "finbot-" + orderId.value().substring("order_".length());
        return value.substring(0, Math.min(32, value.length()));
    }

    private static String normalizeSymbol(String value) {
        return value.replace("_", "").replace("-", "").toUpperCase(Locale.ROOT);
    }

    private static String bounded(String prompt) {
        return prompt.length() <= MAXIMUM_PROMPT_CHARACTERS
                ? prompt
                : prompt.substring(0, MAXIMUM_PROMPT_CHARACTERS);
    }

    private static String failureMessage(RuntimeException exception) {
        var type = exception.getClass().getSimpleName();
        var detail = Objects.requireNonNullElse(exception.getMessage(), "").strip()
                .replaceAll(
                        "(?i)(api[_-]?key|secret|token|password)\\s*[=:]\\s*[^\\s,;]+",
                        "$1=[REDACTED]");
        var message = detail.isEmpty()
                ? "Trade automation failed: " + type
                : "Trade automation failed: " + type + ": " + detail;
        return message.substring(0, Math.min(message.length(), 500));
    }

    private static String deterministicId(String prefix, String input) {
        return prefix + sha256(input).substring(0, 40);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
