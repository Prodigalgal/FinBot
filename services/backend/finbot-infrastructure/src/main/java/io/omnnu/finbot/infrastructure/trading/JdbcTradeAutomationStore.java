package io.omnnu.finbot.infrastructure.trading;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.trading.PlannedOrder;
import io.omnnu.finbot.application.trading.StoredExecutionAiReview;
import io.omnnu.finbot.application.trading.StoredRiskAssessment;
import io.omnnu.finbot.application.trading.TradeAutomationResult;
import io.omnnu.finbot.application.trading.TradeAutomationStatus;
import io.omnnu.finbot.application.trading.TradeAutomationStore;
import io.omnnu.finbot.application.trading.TradeExecutionAiStage;
import io.omnnu.finbot.application.trading.TradeExecutionAiStageConfig;
import io.omnnu.finbot.application.exchange.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionResult;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Price;
import io.omnnu.finbot.domain.oms.OrderId;
import io.omnnu.finbot.domain.risk.RiskInstrumentSpec;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import io.omnnu.finbot.domain.trading.ApprovedTradeIntent;
import io.omnnu.finbot.domain.trading.DirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.NonDirectionalTradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecision;
import io.omnnu.finbot.domain.trading.TradeDecisionId;
import io.omnnu.finbot.domain.trading.TradeProposal;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcTradeAutomationStore implements TradeAutomationStore {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcTradeAutomationStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TradeAutomationResult> findTerminal(WorkflowRunId workflowRunId) {
        var root = jdbcClient.sql("""
                select automation_run_id, status, decision_id, error_message
                from trade_automation_run
                where workflow_run_id = :workflowRunId and status <> 'STARTED'
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new AutomationRoot(
                        resultSet.getString("automation_run_id"),
                        TradeAutomationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("decision_id"),
                        resultSet.getString("error_message")))
                .optional();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        var value = root.orElseThrow();
        var orders = jdbcClient.sql("""
                select order_record.order_id
                from oms_order order_record
                join approved_trade_intent intent on intent.intent_id = order_record.intent_id
                join trade_proposal proposal on proposal.proposal_id = intent.proposal_id
                join trade_decision decision on decision.decision_id = proposal.decision_id
                where decision.workflow_run_id = :workflowRunId
                order by order_record.created_at, order_record.order_id
                """)
                .param("workflowRunId", workflowRunId.value())
                .query(String.class)
                .list()
                .stream()
                .map(OrderId::new)
                .toList();
        return Optional.of(new TradeAutomationResult(
                value.automationRunId(),
                value.status(),
                value.decisionId() == null ? null : new TradeDecisionId(value.decisionId()),
                orders,
                value.errorMessage() == null ? List.of() : List.of(value.errorMessage())));
    }

    @Override
    public boolean start(String automationRunId, WorkflowRunId workflowRunId, Instant startedAt) {
        return jdbcClient.sql("""
                insert into trade_automation_run (
                  automation_run_id, workflow_run_id, status, started_at
                ) values (:automationRunId, :workflowRunId, 'STARTED', :startedAt)
                on conflict (workflow_run_id) do update
                set status = 'STARTED',
                    attempt_count = trade_automation_run.attempt_count + 1,
                    error_code = null,
                    error_message = null,
                    completed_at = null
                where trade_automation_run.status = 'FAILED'
                  and trade_automation_run.decision_id is null
                  and trade_automation_run.proposal_id is null
                  and trade_automation_run.risk_assessment_id is null
                  and trade_automation_run.intent_id is null
                  and trade_automation_run.order_id is null
                  and trade_automation_run.attempt_count < 20
                """)
                .param("automationRunId", automationRunId)
                .param("workflowRunId", workflowRunId.value())
                .param("startedAt", timestamp(startedAt))
                .update() == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TradeExecutionAiStageConfig> executionAiStages() {
        return jdbcClient.sql("""
                select stage, provider_profile_id, model_name, reasoning_effort,
                       fallback_provider_profile_id, fallback_model_name, fallback_reasoning_effort,
                       system_prompt, user_prompt_template, maximum_output_tokens,
                       timeout_seconds, retry_max_attempts, retry_backoff_seconds, enabled, version
                from trade_execution_ai_stage order by stage
                """)
                .query((resultSet, rowNumber) -> new TradeExecutionAiStageConfig(
                        TradeExecutionAiStage.valueOf(resultSet.getString("stage")),
                        binding(
                                resultSet.getString("provider_profile_id"),
                                resultSet.getString("model_name"),
                                resultSet.getString("reasoning_effort")),
                        binding(
                                resultSet.getString("fallback_provider_profile_id"),
                                resultSet.getString("fallback_model_name"),
                                resultSet.getString("fallback_reasoning_effort")),
                        resultSet.getString("system_prompt"),
                        resultSet.getString("user_prompt_template"),
                        resultSet.getInt("maximum_output_tokens"),
                        resultSet.getInt("timeout_seconds"),
                        new WorkflowRetryPolicy(
                                resultSet.getInt("retry_max_attempts"),
                                java.time.Duration.ofSeconds(resultSet.getInt("retry_backoff_seconds"))),
                        resultSet.getBoolean("enabled"),
                        resultSet.getLong("version")))
                .list();
    }

    private static AiModelBinding binding(String providerProfileId, String modelName, String reasoningEffort) {
        return providerProfileId == null ? null : new AiModelBinding(
                new AiProviderProfileId(providerProfileId),
                modelName,
                ReasoningEffort.valueOf(reasoningEffort));
    }

    @Override
    @Transactional(readOnly = true)
    public RiskPolicy activeRiskPolicy() {
        return jdbcClient.sql("""
                select policy_version, test_environment_only, minimum_confidence,
                       risk_budget_usdt, maximum_notional_usdt, preferred_leverage, maximum_leverage,
                       maximum_open_positions, maximum_stop_distance, taker_fee_rate,
                       slippage_rate, liquidation_buffer_rate
                from risk_policy where active = true
                """)
                .query((resultSet, rowNumber) -> new RiskPolicy(
                        resultSet.getString("policy_version"),
                        resultSet.getBoolean("test_environment_only"),
                        resultSet.getBigDecimal("minimum_confidence"),
                        resultSet.getBigDecimal("risk_budget_usdt"),
                        resultSet.getBigDecimal("maximum_notional_usdt"),
                        resultSet.getBigDecimal("preferred_leverage"),
                        resultSet.getBigDecimal("maximum_leverage"),
                        resultSet.getInt("maximum_open_positions"),
                        resultSet.getBigDecimal("maximum_stop_distance"),
                        resultSet.getBigDecimal("taker_fee_rate"),
                        resultSet.getBigDecimal("slippage_rate"),
                        resultSet.getBigDecimal("liquidation_buffer_rate")))
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RiskInstrumentSpec> executionCandidates(String normalizedSymbol) {
        return jdbcClient.sql("""
                select instrument.instrument_id, account.account_id, instrument.exchange,
                       account.environment, instrument.symbol, instrument.contract_size,
                       instrument.quantity_step, instrument.minimum_quantity,
                       instrument.maximum_leverage, latest.close_price,
                       coalesce(open_positions.position_count, 0) as open_position_count
                from venue_instrument instrument
                join exchange_account account
                  on account.exchange = instrument.exchange and account.enabled = true
                join lateral (
                  select candle.close_price
                  from market_candle_fact candle
                  where candle.instrument_id = instrument.instrument_id
                  order by candle.open_time desc, candle.id desc
                  limit 1
                ) latest on true
                left join lateral (
                  select count(*) as position_count
                  from (
                    select distinct on (position.symbol) position.symbol, position.quantity
                    from exchange_position_snapshot position
                    where position.account_id = account.account_id
                    order by position.symbol, position.occurred_at desc, position.id desc
                  ) current_position
                  where current_position.quantity > 0
                ) open_positions on true
                where instrument.status = 'ACTIVE'
                  and instrument.execution_enabled = true
                  and replace(replace(upper(instrument.symbol), '_', ''), '-', '') = :normalizedSymbol
                order by instrument.exchange, account.account_id, instrument.instrument_id
                """)
                .param("normalizedSymbol", normalizedSymbol)
                .query((resultSet, rowNumber) -> new RiskInstrumentSpec(
                        new InstrumentId(resultSet.getString("instrument_id")),
                        new ExchangeAccountId(resultSet.getString("account_id")),
                        ExchangeVenue.valueOf(resultSet.getString("exchange")),
                        ExchangeEnvironment.valueOf(resultSet.getString("environment")),
                        new InstrumentSymbol(resultSet.getString("symbol")),
                        resultSet.getBigDecimal("contract_size"),
                        resultSet.getBigDecimal("quantity_step"),
                        resultSet.getBigDecimal("minimum_quantity"),
                        resultSet.getBigDecimal("maximum_leverage"),
                        new Price(resultSet.getBigDecimal("close_price")),
                        resultSet.getInt("open_position_count")))
                .list();
    }

    @Override
    public void saveExecutionAiReview(StoredExecutionAiReview review) {
        jdbcClient.sql("""
                insert into trade_execution_ai_review (
                  review_id, automation_run_id, workflow_run_id, stage, invocation_id,
                  status, output, output_hash, created_at
                ) values (
                  :reviewId, :automationRunId, :workflowRunId, :stage, :invocationId,
                  'COMPLETED', cast(:output as jsonb), :outputHash, :createdAt
                ) on conflict (automation_run_id, stage) do update
                set invocation_id = excluded.invocation_id,
                    status = 'COMPLETED',
                    output = excluded.output,
                    output_hash = excluded.output_hash,
                    error_code = null,
                    error_message = null,
                    created_at = excluded.created_at
                """)
                .param("reviewId", review.reviewId())
                .param("automationRunId", review.automationRunId())
                .param("workflowRunId", review.workflowRunId().value())
                .param("stage", review.stage().name())
                .param("invocationId", review.invocationId().value())
                .param("output", review.canonicalJson())
                .param("outputHash", review.outputHash())
                .param("createdAt", timestamp(review.createdAt()))
                .update();
    }

    @Override
    public void saveExecutionAiFailure(
            String reviewId,
            String automationRunId,
            WorkflowRunId workflowRunId,
            TradeExecutionAiStage stage,
            String errorCode,
            String safeMessage,
            Instant createdAt) {
        jdbcClient.sql("""
                insert into trade_execution_ai_review (
                  review_id, automation_run_id, workflow_run_id, stage, status,
                  error_code, error_message, created_at
                ) values (
                  :reviewId, :automationRunId, :workflowRunId, :stage, 'FAILED',
                  :errorCode, :errorMessage, :createdAt
                ) on conflict (automation_run_id, stage) do update
                set error_code = excluded.error_code,
                    error_message = excluded.error_message,
                    created_at = excluded.created_at
                where trade_execution_ai_review.status = 'FAILED'
                """)
                .param("reviewId", reviewId)
                .param("automationRunId", automationRunId)
                .param("workflowRunId", workflowRunId.value())
                .param("stage", stage.name())
                .param("errorCode", safe(errorCode, 80))
                .param("errorMessage", safe(safeMessage, 2_000))
                .param("createdAt", timestamp(createdAt))
                .update();
    }

    @Override
    public void saveDecision(WorkflowRunId workflowRunId, TradeDecision decision) {
        var directional = decision instanceof DirectionalTradeDecision;
        var entry = directional ? ((DirectionalTradeDecision) decision).entryReference().value() : null;
        var target = directional ? ((DirectionalTradeDecision) decision).targetPrice().value() : null;
        var invalidation = directional
                ? ((DirectionalTradeDecision) decision).invalidationPrice().value()
                : null;
        jdbcClient.sql("""
                insert into trade_decision (
                  decision_id, workflow_run_id, symbol, decision_kind, action, confidence,
                  entry_reference, target_price, invalidation_price, rationale, created_at
                ) values (
                  :decisionId, :workflowRunId, :symbol, :decisionKind, :action, :confidence,
                  :entryReference, :targetPrice, :invalidationPrice,
                  cast(:rationale as jsonb), :createdAt
                ) on conflict (decision_id) do nothing
                """)
                .param("decisionId", decision.id().value())
                .param("workflowRunId", workflowRunId.value())
                .param("symbol", decision.symbol().value())
                .param("decisionKind", directional ? "DIRECTIONAL" : "NON_DIRECTIONAL")
                .param("action", decision.action().code())
                .param("confidence", decision.confidence().value())
                .param("entryReference", entry)
                .param("targetPrice", target)
                .param("invalidationPrice", invalidation)
                .param("rationale", json(decision.rationale()))
                .param("createdAt", timestamp(decision.createdAt()))
                .update();
    }

    @Override
    public void saveProposal(TradeProposal proposal) {
        jdbcClient.sql("""
                insert into trade_proposal (
                  proposal_id, decision_id, symbol, action, status,
                  entry_reference, target_price, invalidation_price, created_at
                ) values (
                  :proposalId, :decisionId, :symbol, :action, :status,
                  :entryReference, :targetPrice, :invalidationPrice, :createdAt
                ) on conflict (proposal_id) do nothing
                """)
                .param("proposalId", proposal.id().value())
                .param("decisionId", proposal.decisionId().value())
                .param("symbol", proposal.symbol().value())
                .param("action", proposal.action().name())
                .param("status", proposal.status().name())
                .param("entryReference", proposal.entryReference().value())
                .param("targetPrice", proposal.targetPrice().value())
                .param("invalidationPrice", proposal.invalidationPrice().value())
                .param("createdAt", timestamp(proposal.createdAt()))
                .update();
    }

    @Override
    public void saveRiskAssessment(StoredRiskAssessment assessment) {
        var plan = assessment.plan();
        jdbcClient.sql("""
                insert into risk_assessment (
                  assessment_id, automation_run_id, workflow_run_id, proposal_id,
                  account_id, policy_version, status, reasons, quantity, notional_usdt,
                  leverage, initial_margin_usdt, estimated_max_loss_usdt,
                  approximate_liquidation_price, assessed_at
                ) values (
                  :assessmentId, :automationRunId, :workflowRunId, :proposalId,
                  :accountId, :policyVersion, :status, cast(:reasons as jsonb),
                  :quantity, :notionalUsdt, :leverage, :initialMarginUsdt,
                  :estimatedMaxLossUsdt, :approximateLiquidationPrice, :assessedAt
                ) on conflict (proposal_id, account_id) do nothing
                """)
                .param("assessmentId", assessment.assessmentId().value())
                .param("automationRunId", assessment.automationRunId())
                .param("workflowRunId", assessment.workflowRunId().value())
                .param("proposalId", assessment.proposalId().value())
                .param("accountId", assessment.accountId().value())
                .param("policyVersion", assessment.policyVersion())
                .param("status", plan.status().name())
                .param("reasons", json(plan.reasons()))
                .param("quantity", plan.quantity())
                .param("notionalUsdt", plan.notionalUsdt())
                .param("leverage", plan.leverage())
                .param("initialMarginUsdt", plan.initialMarginUsdt())
                .param("estimatedMaxLossUsdt", plan.estimatedMaximumLossUsdt())
                .param("approximateLiquidationPrice", plan.approximateLiquidationPrice())
                .param("assessedAt", timestamp(assessment.assessedAt()))
                .update();
    }

    @Override
    @Transactional
    public void saveApprovedIntentAndOrder(ApprovedTradeIntent intent, PlannedOrder order) {
        jdbcClient.sql("""
                insert into approved_trade_intent (
                  intent_id, proposal_id, account_id, risk_assessment_id, symbol, action,
                  quantity, leverage, entry_reference, target_price, invalidation_price,
                  policy_version, approved_at
                ) values (
                  :intentId, :proposalId, :accountId, :riskAssessmentId, :symbol, :action,
                  :quantity, :leverage, :entryReference, :targetPrice, :invalidationPrice,
                  :policyVersion, :approvedAt
                ) on conflict (proposal_id, account_id) do nothing
                """)
                .param("intentId", intent.id().value())
                .param("proposalId", intent.proposalId().value())
                .param("accountId", intent.accountId().value())
                .param("riskAssessmentId", intent.riskAssessmentId().value())
                .param("symbol", intent.symbol().value())
                .param("action", intent.action().name())
                .param("quantity", intent.quantity().value())
                .param("leverage", intent.leverage())
                .param("entryReference", intent.entryReference().value())
                .param("targetPrice", intent.targetPrice().value())
                .param("invalidationPrice", intent.invalidationPrice().value())
                .param("policyVersion", intent.policyVersion())
                .param("approvedAt", timestamp(intent.approvedAt()))
                .update();
        var inserted = jdbcClient.sql("""
                insert into oms_order (
                  order_id, intent_id, idempotency_key, exchange, environment,
                  account_ref, symbol, side, status, requested_quantity,
                  filled_quantity, leverage, client_order_id, created_at, updated_at
                ) values (
                  :orderId, :intentId, :idempotencyKey, :exchange, :environment,
                  :accountRef, :symbol, :side, 'PLANNED', :requestedQuantity,
                  0, :leverage, :clientOrderId, :createdAt, :createdAt
                ) on conflict (idempotency_key) do nothing
                """)
                .param("orderId", order.orderId().value())
                .param("intentId", order.intentId().value())
                .param("idempotencyKey", order.idempotencyKey())
                .param("exchange", order.exchange().name())
                .param("environment", order.environment().name())
                .param("accountRef", order.accountId().value())
                .param("symbol", order.symbol().value())
                .param("side", order.side().name())
                .param("requestedQuantity", order.quantity())
                .param("leverage", order.leverage())
                .param("clientOrderId", order.clientOrderId())
                .param("createdAt", timestamp(order.createdAt()))
                .update();
        if (inserted == 1) {
            jdbcClient.sql("""
                    insert into oms_order_event (
                      event_id, order_id, sequence, event_type, from_status,
                      to_status, payload, occurred_at
                    ) values (
                      :eventId, :orderId, 1, 'OrderPlanned', null,
                      'PLANNED', '{}'::jsonb, :occurredAt
                    )
                    """)
                    .param("eventId", "event_" + order.orderId().value().substring("order_".length()))
                    .param("orderId", order.orderId().value())
                    .param("occurredAt", timestamp(order.createdAt()))
                    .update();
        }
    }

    @Override
    public void complete(
            String automationRunId,
            TradeAutomationStatus status,
            TradeDecision decision,
            TradeProposal proposal,
            List<StoredRiskAssessment> assessments,
            List<PlannedOrder> orders,
            List<String> reasons,
            Instant completedAt) {
        jdbcClient.sql("""
                update trade_automation_run
                set status = :status, decision_id = :decisionId,
                    proposal_id = :proposalId, risk_assessment_id = :riskAssessmentId,
                    intent_id = :intentId, order_id = :orderId,
                    error_message = :summary, completed_at = :completedAt
                where automation_run_id = :automationRunId and status = 'STARTED'
                """)
                .param("automationRunId", automationRunId)
                .param("status", status.name())
                .param("decisionId", decision == null ? null : decision.id().value())
                .param("proposalId", proposal == null ? null : proposal.id().value())
                .param("riskAssessmentId", assessments.isEmpty()
                        ? null
                        : assessments.getFirst().assessmentId().value())
                .param("intentId", orders.isEmpty() ? null : orders.getFirst().intentId().value())
                .param("orderId", orders.isEmpty() ? null : orders.getFirst().orderId().value())
                .param("summary", reasons.isEmpty() ? null : safe(String.join("; ", reasons), 2_000))
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    @Override
    public void fail(
            String automationRunId,
            String errorCode,
            String safeMessage,
            Instant failedAt) {
        jdbcClient.sql("""
                update trade_automation_run
                set status = 'FAILED', error_code = :errorCode,
                    error_message = :errorMessage, completed_at = :failedAt
                where automation_run_id = :automationRunId and status = 'STARTED'
                """)
                .param("automationRunId", automationRunId)
                .param("errorCode", safe(errorCode, 80))
                .param("errorMessage", safe(safeMessage, 2_000))
                .param("failedAt", timestamp(failedAt))
                .update();
    }

    @Override
    public void recordExecutionResults(
            String automationRunId,
            List<PaperOrderExecutionResult> results,
            Instant completedAt) {
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
        var summary = results.stream()
                .map(result -> result.orderId().value() + ": "
                        + Objects.requireNonNullElse(result.safeMessage(), result.status().name()))
                .collect(java.util.stream.Collectors.joining("; "));
        jdbcClient.sql("""
                update trade_automation_run
                set status = :status, error_message = :summary, completed_at = :completedAt
                where automation_run_id = :automationRunId and status = 'ORDER_PLANNED'
                """)
                .param("automationRunId", automationRunId)
                .param("status", status.name())
                .param("summary", safe(summary, 2_000))
                .param("completedAt", timestamp(completedAt))
                .update();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode trade automation persistence value", exception);
        }
    }

    private static String safe(String value, int maximumLength) {
        var normalized = Objects.requireNonNull(value, "value").strip();
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }

    private record AutomationRoot(
            String automationRunId,
            TradeAutomationStatus status,
            String decisionId,
            String errorMessage) {
    }
}
