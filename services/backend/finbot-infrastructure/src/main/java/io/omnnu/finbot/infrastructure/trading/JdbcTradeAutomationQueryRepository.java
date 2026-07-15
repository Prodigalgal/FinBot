package io.omnnu.finbot.infrastructure.trading;

import io.omnnu.finbot.application.trading.TradeAutomationDetail;
import io.omnnu.finbot.application.trading.TradeAutomationQueryRepository;
import io.omnnu.finbot.application.trading.TradeAutomationStatus;
import io.omnnu.finbot.application.trading.TradeExecutionAiStage;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.oms.OrderStatus;
import io.omnnu.finbot.domain.risk.RiskAssessmentStatus;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcTradeAutomationQueryRepository implements TradeAutomationQueryRepository {
    private static final String SUMMARY_SELECT = """
            select automation.automation_run_id, automation.workflow_run_id, automation.status,
                   decision.symbol, decision.action, decision.confidence,
                   (select count(*) from oms_order order_record
                    join approved_trade_intent intent on intent.intent_id = order_record.intent_id
                    join trade_proposal proposal on proposal.proposal_id = intent.proposal_id
                    join trade_decision nested_decision on nested_decision.decision_id = proposal.decision_id
                    where nested_decision.workflow_run_id = automation.workflow_run_id) as order_count,
                   (select count(*) from estimated_trade_projection projection
                    where projection.workflow_run_id = automation.workflow_run_id) as estimated_trade_count,
                   automation.error_code, automation.error_message,
                   automation.started_at, automation.completed_at
            from trade_automation_run automation
            left join trade_decision decision on decision.decision_id = automation.decision_id
            """;

    private final JdbcClient jdbcClient;

    public JdbcTradeAutomationQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<TradeAutomationDetail.Summary> list(int limit) {
        return jdbcClient.sql(SUMMARY_SELECT + " order by automation.started_at desc, automation.id desc limit :limit")
                .param("limit", Math.max(1, Math.min(limit, 200)))
                .query((resultSet, rowNumber) -> summary(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TradeAutomationDetail> findByWorkflowRunId(WorkflowRunId workflowRunId) {
        var summary = jdbcClient.sql(SUMMARY_SELECT + " where automation.workflow_run_id = :workflowRunId")
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> summary(resultSet))
                .optional();
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TradeAutomationDetail(
                summary.orElseThrow(),
                decision(workflowRunId).orElse(null),
                reviews(workflowRunId),
                assessments(workflowRunId),
                estimatedTrades(workflowRunId),
                orders(workflowRunId)));
    }

    private Optional<TradeAutomationDetail.Decision> decision(WorkflowRunId workflowRunId) {
        return jdbcClient.sql("""
                select decision.decision_id, decision.decision_kind, decision.symbol,
                       decision.action, decision.confidence, decision.entry_reference,
                       decision.target_price, decision.invalidation_price,
                       decision.rationale::text as rationale_json,
                       proposal.proposal_id, proposal.status as proposal_status,
                       decision.created_at
                from trade_decision decision
                left join trade_proposal proposal on proposal.decision_id = decision.decision_id
                where decision.workflow_run_id = :workflowRunId
                order by proposal.created_at desc nulls last
                limit 1
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new TradeAutomationDetail.Decision(
                        resultSet.getString("decision_id"),
                        resultSet.getString("decision_kind"),
                        resultSet.getString("symbol"),
                        resultSet.getString("action"),
                        resultSet.getBigDecimal("confidence"),
                        resultSet.getBigDecimal("entry_reference"),
                        resultSet.getBigDecimal("target_price"),
                        resultSet.getBigDecimal("invalidation_price"),
                        resultSet.getString("rationale_json"),
                        resultSet.getString("proposal_id"),
                        resultSet.getString("proposal_status"),
                        instant(resultSet, "created_at")))
                .optional();
    }

    private List<TradeAutomationDetail.AiReview> reviews(WorkflowRunId workflowRunId) {
        return jdbcClient.sql("""
                select review.review_id, review.stage, review.status, review.invocation_id,
                       invocation.provider_profile_id, invocation.model_name,
                       invocation.reasoning_effort, review.output::text as output_json,
                       review.output_hash, review.error_code, review.error_message,
                       review.created_at
                from trade_execution_ai_review review
                left join ai_invocation invocation on invocation.invocation_id = review.invocation_id
                where review.workflow_run_id = :workflowRunId
                order by case review.stage when 'DRAFT' then 1 else 2 end
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new TradeAutomationDetail.AiReview(
                        resultSet.getString("review_id"),
                        TradeExecutionAiStage.valueOf(resultSet.getString("stage")),
                        resultSet.getString("status"),
                        resultSet.getString("invocation_id"),
                        resultSet.getString("provider_profile_id"),
                        resultSet.getString("model_name"),
                        nullableReasoning(resultSet.getString("reasoning_effort")),
                        resultSet.getString("output_json"),
                        resultSet.getString("output_hash"),
                        resultSet.getString("error_code"),
                        resultSet.getString("error_message"),
                        instant(resultSet, "created_at")))
                .list();
    }

    private List<TradeAutomationDetail.RiskAssessment> assessments(WorkflowRunId workflowRunId) {
        return jdbcClient.sql("""
                select assessment_id, proposal_id, account_id, policy_version, status,
                       reasons::text as reasons_json, quantity, notional_usdt, leverage,
                       initial_margin_usdt, estimated_max_loss_usdt,
                       approximate_liquidation_price, assessed_at
                from risk_assessment
                where workflow_run_id = :workflowRunId
                order by assessed_at, account_id
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new TradeAutomationDetail.RiskAssessment(
                        resultSet.getString("assessment_id"),
                        resultSet.getString("proposal_id"),
                        resultSet.getString("account_id"),
                        resultSet.getString("policy_version"),
                        RiskAssessmentStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("reasons_json"),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("notional_usdt"),
                        resultSet.getBigDecimal("leverage"),
                        resultSet.getBigDecimal("initial_margin_usdt"),
                        resultSet.getBigDecimal("estimated_max_loss_usdt"),
                        resultSet.getBigDecimal("approximate_liquidation_price"),
                        instant(resultSet, "assessed_at")))
                .list();
    }

    private List<TradeAutomationDetail.EstimatedTrade> estimatedTrades(WorkflowRunId workflowRunId) {
        return jdbcClient.sql("""
                select projection_id, proposal_id, instrument_id, exchange, symbol, side,
                       policy_version, entry_reference, market_price, target_price, stop_price,
                       quantity, contract_size, notional_usdt, leverage, initial_margin_usdt,
                       estimated_entry_cost_usdt, estimated_target_exit_cost_usdt,
                       estimated_stop_exit_cost_usdt, estimated_profit_usdt,
                       estimated_loss_usdt, risk_reward_ratio, calculated_at
                from estimated_trade_projection
                where workflow_run_id = :workflowRunId
                order by calculated_at, exchange, instrument_id
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new TradeAutomationDetail.EstimatedTrade(
                        resultSet.getString("projection_id"),
                        resultSet.getString("proposal_id"),
                        resultSet.getString("instrument_id"),
                        ExchangeVenue.valueOf(resultSet.getString("exchange")),
                        resultSet.getString("symbol"),
                        resultSet.getString("side"),
                        resultSet.getString("policy_version"),
                        resultSet.getBigDecimal("entry_reference"),
                        resultSet.getBigDecimal("market_price"),
                        resultSet.getBigDecimal("target_price"),
                        resultSet.getBigDecimal("stop_price"),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getBigDecimal("contract_size"),
                        resultSet.getBigDecimal("notional_usdt"),
                        resultSet.getBigDecimal("leverage"),
                        resultSet.getBigDecimal("initial_margin_usdt"),
                        resultSet.getBigDecimal("estimated_entry_cost_usdt"),
                        resultSet.getBigDecimal("estimated_target_exit_cost_usdt"),
                        resultSet.getBigDecimal("estimated_stop_exit_cost_usdt"),
                        resultSet.getBigDecimal("estimated_profit_usdt"),
                        resultSet.getBigDecimal("estimated_loss_usdt"),
                        resultSet.getBigDecimal("risk_reward_ratio"),
                        instant(resultSet, "calculated_at")))
                .list();
    }

    private List<TradeAutomationDetail.Order> orders(WorkflowRunId workflowRunId) {
        var events = orderEvents(workflowRunId);
        var attempts = submissionAttempts(workflowRunId);
        return jdbcClient.sql("""
                select order_record.order_id, order_record.intent_id, order_record.exchange,
                       order_record.environment, order_record.account_ref, order_record.symbol,
                       order_record.side, order_record.status, order_record.requested_quantity,
                       order_record.filled_quantity, order_record.average_fill_price,
                       order_record.leverage, order_record.client_order_id,
                       order_record.exchange_order_id, order_record.submitted_at,
                       order_record.terminal_at, order_record.created_at, order_record.updated_at
                from oms_order order_record
                join approved_trade_intent intent on intent.intent_id = order_record.intent_id
                join trade_proposal proposal on proposal.proposal_id = intent.proposal_id
                join trade_decision decision on decision.decision_id = proposal.decision_id
                where decision.workflow_run_id = :workflowRunId
                order by order_record.created_at, order_record.order_id
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> {
                    var orderId = resultSet.getString("order_id");
                    return new TradeAutomationDetail.Order(
                            orderId,
                            resultSet.getString("intent_id"),
                            ExchangeVenue.valueOf(resultSet.getString("exchange")),
                            ExchangeEnvironment.valueOf(resultSet.getString("environment")),
                            resultSet.getString("account_ref"),
                            resultSet.getString("symbol"),
                            resultSet.getString("side"),
                            OrderStatus.valueOf(resultSet.getString("status")),
                            resultSet.getBigDecimal("requested_quantity"),
                            resultSet.getBigDecimal("filled_quantity"),
                            resultSet.getBigDecimal("average_fill_price"),
                            resultSet.getBigDecimal("leverage"),
                            resultSet.getString("client_order_id"),
                            resultSet.getString("exchange_order_id"),
                            instant(resultSet, "submitted_at"),
                            instant(resultSet, "terminal_at"),
                            instant(resultSet, "created_at"),
                            instant(resultSet, "updated_at"),
                            events.getOrDefault(orderId, List.of()),
                            attempts.getOrDefault(orderId, List.of()));
                })
                .list();
    }

    private Map<String, List<TradeAutomationDetail.OrderEvent>> orderEvents(WorkflowRunId workflowRunId) {
        var rows = jdbcClient.sql("""
                select event.order_id, event.event_id, event.sequence, event.event_type,
                       event.from_status, event.to_status, event.payload::text as payload_json,
                       event.occurred_at
                from oms_order_event event
                join oms_order order_record on order_record.order_id = event.order_id
                join approved_trade_intent intent on intent.intent_id = order_record.intent_id
                join trade_proposal proposal on proposal.proposal_id = intent.proposal_id
                join trade_decision decision on decision.decision_id = proposal.decision_id
                where decision.workflow_run_id = :workflowRunId
                order by event.order_id, event.sequence
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new OwnedEvent(
                        resultSet.getString("order_id"),
                        new TradeAutomationDetail.OrderEvent(
                                resultSet.getString("event_id"),
                                resultSet.getLong("sequence"),
                                resultSet.getString("event_type"),
                                resultSet.getString("from_status"),
                                resultSet.getString("to_status"),
                                resultSet.getString("payload_json"),
                                instant(resultSet, "occurred_at"))))
                .list();
        var grouped = new LinkedHashMap<String, List<TradeAutomationDetail.OrderEvent>>();
        rows.forEach(row -> grouped.computeIfAbsent(row.orderId(), ignored -> new ArrayList<>()).add(row.event()));
        return grouped;
    }

    private Map<String, List<TradeAutomationDetail.SubmissionAttempt>> submissionAttempts(
            WorkflowRunId workflowRunId) {
        var rows = jdbcClient.sql("""
                select attempt.order_id, attempt.attempt_id, attempt.attempt_number,
                       attempt.request_hash, attempt.status, attempt.exchange_order_id,
                       attempt.http_status, attempt.response_payload::text as response_json,
                       attempt.error_code, attempt.error_message,
                       attempt.started_at, attempt.completed_at
                from exchange_submission_attempt attempt
                join oms_order order_record on order_record.order_id = attempt.order_id
                join approved_trade_intent intent on intent.intent_id = order_record.intent_id
                join trade_proposal proposal on proposal.proposal_id = intent.proposal_id
                join trade_decision decision on decision.decision_id = proposal.decision_id
                where decision.workflow_run_id = :workflowRunId
                order by attempt.order_id, attempt.attempt_number
                """)
                .param("workflowRunId", workflowRunId.value())
                .query((resultSet, rowNumber) -> new OwnedAttempt(
                        resultSet.getString("order_id"),
                        new TradeAutomationDetail.SubmissionAttempt(
                                resultSet.getString("attempt_id"),
                                resultSet.getInt("attempt_number"),
                                resultSet.getString("request_hash"),
                                resultSet.getString("status"),
                                resultSet.getString("exchange_order_id"),
                                (Integer) resultSet.getObject("http_status"),
                                resultSet.getString("response_json"),
                                resultSet.getString("error_code"),
                                resultSet.getString("error_message"),
                                instant(resultSet, "started_at"),
                                instant(resultSet, "completed_at"))))
                .list();
        var grouped = new LinkedHashMap<String, List<TradeAutomationDetail.SubmissionAttempt>>();
        rows.forEach(row -> grouped.computeIfAbsent(row.orderId(), ignored -> new ArrayList<>()).add(row.attempt()));
        return grouped;
    }

    private static TradeAutomationDetail.Summary summary(ResultSet resultSet) throws SQLException {
        return new TradeAutomationDetail.Summary(
                resultSet.getString("automation_run_id"),
                resultSet.getString("workflow_run_id"),
                TradeAutomationStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("symbol"),
                resultSet.getString("action"),
                resultSet.getBigDecimal("confidence"),
                resultSet.getInt("order_count"),
                resultSet.getInt("estimated_trade_count"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                instant(resultSet, "started_at"),
                instant(resultSet, "completed_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static ReasoningEffort nullableReasoning(String value) {
        return value == null ? null : ReasoningEffort.valueOf(value);
    }

    private record OwnedEvent(String orderId, TradeAutomationDetail.OrderEvent event) {
    }

    private record OwnedAttempt(String orderId, TradeAutomationDetail.SubmissionAttempt attempt) {
    }
}
