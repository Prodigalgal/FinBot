package io.omnnu.finbot.infrastructure.workspace.persistence;

import static io.omnnu.finbot.infrastructure.jdbc.persistence.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.workspace.dto.OperationsReport;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcOperationsReportQuery {
    private final JdbcClient jdbcClient;

    JdbcOperationsReportQuery(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    OperationsReport report(Instant fromInclusive, Instant toExclusive, Instant generatedAt) {
        return new OperationsReport(
                fromInclusive,
                toExclusive,
                List.of(
                        research(fromInclusive, toExclusive),
                        ingestion(fromInclusive, toExclusive),
                        quant(fromInclusive, toExclusive),
                        ai(fromInclusive, toExclusive),
                        trading(fromInclusive, toExclusive),
                        tasks(fromInclusive, toExclusive)),
                generatedAt);
    }

    private OperationsReport.Section research(Instant from, Instant to) {
        var metric = jdbcClient.sql("""
                select count(*) as total,
                       count(*) filter (where status in ('COMPLETED', 'PARTIAL')) as completed,
                       count(*) filter (where status = 'FAILED') as failed
                from workflow_run where accepted_at >= :from and accepted_at < :to
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> countMetric(resultSet))
                .single();
        return section("RESEARCH", "研究运行", metric, recentWorkflowFailures(from, to));
    }

    private OperationsReport.Section ingestion(Instant from, Instant to) {
        var metric = jdbcClient.sql("""
                select count(*) as total,
                       count(*) filter (where status in ('COMPLETED', 'PARTIAL')) as completed,
                       count(*) filter (where status in ('FAILED', 'BLOCKED')) as failed
                from source_collection_run where started_at >= :from and started_at < :to
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> countMetric(resultSet))
                .single();
        var entries = jdbcClient.sql("""
                select run.collection_id, source.display_name, run.status,
                       coalesce(run.error_message, '采集未完整完成') as summary, run.started_at
                from source_collection_run run
                join information_source source on source.source_id = run.source_id
                where run.started_at >= :from and run.started_at < :to
                  and run.status in ('FAILED', 'BLOCKED', 'PARTIAL')
                order by run.started_at desc, run.id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("collection_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "started_at")))
                .list();
        return section("INGESTION", "采集与证据", metric, entries);
    }

    private OperationsReport.Section quant(Instant from, Instant to) {
        var metric = countMetric("quant_research_run", from, to);
        var entries = jdbcClient.sql("""
                select research_run_id, strategy_id, status,
                       coalesce(error_message, observation_count || ' 条观测') as summary,
                       requested_at
                from quant_research_run
                where requested_at >= :from and requested_at < :to
                  and status in ('FAILED', 'CANCELLED')
                order by requested_at desc, id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("research_run_id"),
                        resultSet.getString("strategy_id"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "requested_at")))
                .list();
        return section("QUANT", "量化研究", metric, entries);
    }

    private OperationsReport.Section ai(Instant from, Instant to) {
        var aggregate = jdbcClient.sql("""
                select count(*) as total,
                       count(*) filter (where status = 'COMPLETED') as completed,
                       count(*) filter (where status = 'FAILED') as failed,
                       coalesce(sum(input_tokens + output_tokens), 0) as tokens,
                       coalesce(sum(estimated_cost_usd), 0) as cost
                from ai_invocation where started_at >= :from and started_at < :to
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> new AiMetric(
                        resultSet.getLong("total"),
                        resultSet.getLong("completed"),
                        resultSet.getLong("failed"),
                        resultSet.getLong("tokens"),
                        resultSet.getBigDecimal("cost")))
                .single();
        var metrics = new ArrayList<>(metrics(new CountMetric(
                aggregate.total(), aggregate.completed(), aggregate.failed())));
        metrics.add(new OperationsReport.Metric("Token", Long.toString(aggregate.tokens()), "tokens", "INFO"));
        metrics.add(new OperationsReport.Metric("估算成本", aggregate.cost().toPlainString(), "USD", "INFO"));
        var entries = jdbcClient.sql("""
                select invocation_id, model_name, status,
                       coalesce(error_message, error_code, 'AI 调用失败') as summary, started_at
                from ai_invocation
                where started_at >= :from and started_at < :to and status = 'FAILED'
                order by started_at desc, id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("invocation_id"),
                        resultSet.getString("model_name"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "started_at")))
                .list();
        return new OperationsReport.Section("AI", "AI 调用与成本", metrics, entries);
    }

    private OperationsReport.Section trading(Instant from, Instant to) {
        var metric = jdbcClient.sql("""
                select (select count(*) from oms_order where created_at >= :from and created_at < :to) as total,
                       (select count(*) from exchange_fill_fact where occurred_at >= :from and occurred_at < :to) as completed,
                       (select count(*) from exchange_submission_attempt
                        where started_at >= :from and started_at < :to and status = 'FAILED') as failed
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> countMetric(resultSet))
                .single();
        var entries = jdbcClient.sql("""
                select attempt.attempt_id, order_record.symbol, attempt.status,
                       coalesce(attempt.error_message, attempt.error_code, '提交失败') as summary,
                       attempt.started_at
                from exchange_submission_attempt attempt
                join oms_order order_record on order_record.order_id = attempt.order_id
                where attempt.started_at >= :from and attempt.started_at < :to
                  and attempt.status = 'FAILED'
                order by attempt.started_at desc, attempt.id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("attempt_id"),
                        resultSet.getString("symbol"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "started_at")))
                .list();
        return section("TRADING", "模拟交易与风控", metric, entries);
    }

    private OperationsReport.Section tasks(Instant from, Instant to) {
        var metric = countMetric("background_task", from, to);
        var entries = jdbcClient.sql("""
                select task_id, task_type, status,
                       coalesce(error_message, error_code, '后台任务失败') as summary, created_at
                from background_task
                where created_at >= :from and created_at < :to and status = 'FAILED'
                order by created_at desc, id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("task_id"),
                        resultSet.getString("task_type"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "created_at")))
                .list();
        return section("OPERATIONS", "后台任务", metric, entries);
    }

    private CountMetric countMetric(String source, Instant from, Instant to) {
        var sql = switch (source) {
            case "quant_research_run" -> """
                    select count(*) as total,
                           count(*) filter (where status = 'COMPLETED') as completed,
                           count(*) filter (where status in ('FAILED', 'CANCELLED')) as failed
                    from quant_research_run where requested_at >= :from and requested_at < :to
                    """;
            case "background_task" -> """
                    select count(*) as total,
                           count(*) filter (where status = 'COMPLETED') as completed,
                           count(*) filter (where status = 'FAILED') as failed
                    from background_task where created_at >= :from and created_at < :to
                    """;
            default -> throw new IllegalArgumentException("Unsupported report source: " + source);
        };
        return jdbcClient.sql(sql)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> countMetric(resultSet))
                .single();
    }

    private List<OperationsReport.Entry> recentWorkflowFailures(Instant from, Instant to) {
        return jdbcClient.sql("""
                select run.run_id, run.request_summary, run.status,
                       coalesce(checkpoint.error_message, checkpoint.error_code, '研究运行失败') as summary,
                       run.updated_at
                from workflow_run run
                left join lateral (
                  select value.error_code, value.error_message
                  from workflow_node_checkpoint value
                  where value.run_id = run.run_id and value.status = 'FAILED'
                  order by value.updated_at desc, value.id desc limit 1
                ) checkpoint on true
                where run.accepted_at >= :from and run.accepted_at < :to and run.status = 'FAILED'
                order by run.updated_at desc, run.id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("run_id"),
                        resultSet.getString("request_summary"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "updated_at")))
                .list();
    }

    private static CountMetric countMetric(ResultSet resultSet) throws SQLException {
        return new CountMetric(
                resultSet.getLong("total"),
                resultSet.getLong("completed"),
                resultSet.getLong("failed"));
    }

    private static OperationsReport.Section section(
            String code,
            String title,
            CountMetric metric,
            List<OperationsReport.Entry> entries) {
        return new OperationsReport.Section(code, title, metrics(metric), entries);
    }

    private static List<OperationsReport.Metric> metrics(CountMetric metric) {
        return List.of(
                new OperationsReport.Metric("总数", Long.toString(metric.total()), "次", "INFO"),
                new OperationsReport.Metric("完成", Long.toString(metric.completed()), "次", "SUCCESS"),
                new OperationsReport.Metric(
                        "失败", Long.toString(metric.failed()), "次", metric.failed() > 0 ? "WARNING" : "SUCCESS"));
    }

    private static OperationsReport.Entry entry(
            String referenceId,
            String title,
            String summary,
            String status,
            Instant occurredAt) {
        return new OperationsReport.Entry(referenceId, title, safe(summary), status, occurredAt);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.strip();
        return normalized.substring(0, Math.min(normalized.length(), 2_000));
    }

    private record CountMetric(long total, long completed, long failed) {
    }

    private record AiMetric(long total, long completed, long failed, long tokens, BigDecimal cost) {
    }
}
