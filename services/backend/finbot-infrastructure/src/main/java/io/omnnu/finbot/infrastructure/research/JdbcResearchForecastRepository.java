package io.omnnu.finbot.infrastructure.research;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.research.ForecastEvaluationCandidate;
import io.omnnu.finbot.application.research.ResearchForecastRepository;
import io.omnnu.finbot.application.research.ResearchForecastView;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.research.ForecastDirection;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcResearchForecastRepository implements ResearchForecastRepository {
    private static final String COLUMNS = """
            forecast_id, workflow_run_id, instrument_id, exchange, symbol,
            interval_seconds, horizon_seconds, market_reference_price, direction,
            expected_low, expected_high, invalidation_price, confidence,
            thesis, evidence_refs::text as evidence_refs, status, issued_at, target_at,
            actual_price, actual_return, direction_correct, range_hit, evaluated_at
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcResearchForecastRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchForecastView> findByRun(WorkflowRunId workflowRunId) {
        return jdbcClient.sql("select " + COLUMNS + " from research_forecast where workflow_run_id = :runId")
                .param("runId", workflowRunId.value())
                .query((resultSet, rowNumber) -> map(resultSet))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResearchForecastView> recent(int limit) {
        return jdbcClient.sql("select " + COLUMNS
                        + " from research_forecast order by issued_at desc, id desc limit :limit")
                .param("limit", limit)
                .query((resultSet, rowNumber) -> map(resultSet))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ForecastEvaluationCandidate> due(Instant now, int limit) {
        return jdbcClient.sql("""
                select forecast_id, instrument_id, interval_seconds
                from research_forecast
                where status = 'PENDING' and target_at <= :now
                order by target_at, id
                limit :limit
                """)
                .param("now", timestamp(now))
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ForecastEvaluationCandidate(
                        resultSet.getString("forecast_id"),
                        new InstrumentId(resultSet.getString("instrument_id")),
                        resultSet.getInt("interval_seconds")))
                .list();
    }

    @Override
    public boolean evaluate(String forecastId, Instant evaluatedAt) {
        return jdbcClient.sql("""
                with evaluation as (
                  select forecast.forecast_id,
                         actual.close_price as actual_price,
                         (actual.close_price - forecast.market_reference_price)
                           / forecast.market_reference_price as actual_return,
                         case forecast.direction
                           when 'UP' then actual.close_price > forecast.market_reference_price
                           when 'DOWN' then actual.close_price < forecast.market_reference_price
                           when 'SIDEWAYS' then actual.close_price between forecast.expected_low and forecast.expected_high
                           else null
                         end as direction_correct,
                         case when forecast.direction = 'UNCERTAIN' then null
                           else actual.close_price between forecast.expected_low and forecast.expected_high
                         end as range_hit
                  from research_forecast forecast
                  join lateral (
                    select candle.close_price
                    from market_candle_fact candle
                    where candle.instrument_id = forecast.instrument_id
                      and candle.interval_seconds = forecast.interval_seconds
                      and candle.open_time < forecast.target_at
                      and candle.open_time >= forecast.target_at
                            - make_interval(secs => forecast.interval_seconds)
                      and candle.observed_at >= forecast.target_at
                    order by candle.open_time desc, candle.id desc
                    limit 1
                  ) actual on true
                  where forecast.forecast_id = :forecastId
                    and forecast.status = 'PENDING'
                    and forecast.target_at <= :evaluatedAt
                )
                update research_forecast forecast
                set status = 'EVALUATED', actual_price = evaluation.actual_price,
                    actual_return = evaluation.actual_return,
                    direction_correct = evaluation.direction_correct,
                    range_hit = evaluation.range_hit,
                    evaluated_at = :evaluatedAt
                from evaluation
                where forecast.forecast_id = evaluation.forecast_id
                """)
                .param("forecastId", forecastId)
                .param("evaluatedAt", timestamp(evaluatedAt))
                .update() == 1;
    }

    private ResearchForecastView map(ResultSet resultSet) throws SQLException {
        return new ResearchForecastView(
                resultSet.getString("forecast_id"),
                new WorkflowRunId(resultSet.getString("workflow_run_id")),
                new InstrumentId(resultSet.getString("instrument_id")),
                ExchangeVenue.valueOf(resultSet.getString("exchange")),
                resultSet.getString("symbol"),
                resultSet.getInt("interval_seconds"),
                resultSet.getInt("horizon_seconds"),
                resultSet.getBigDecimal("market_reference_price"),
                ForecastDirection.valueOf(resultSet.getString("direction")),
                resultSet.getBigDecimal("expected_low"),
                resultSet.getBigDecimal("expected_high"),
                resultSet.getBigDecimal("invalidation_price"),
                resultSet.getBigDecimal("confidence"),
                resultSet.getString("thesis"),
                strings(resultSet.getString("evidence_refs")),
                resultSet.getString("status"),
                instant(resultSet.getObject("issued_at", OffsetDateTime.class)),
                instant(resultSet.getObject("target_at", OffsetDateTime.class)),
                resultSet.getBigDecimal("actual_price"),
                resultSet.getBigDecimal("actual_return"),
                nullableBoolean(resultSet, "direction_correct"),
                nullableBoolean(resultSet, "range_hit"),
                nullableInstant(resultSet.getObject("evaluated_at", OffsetDateTime.class)));
    }

    private List<String> strings(String json) {
        try {
            return List.of(objectMapper.readValue(json, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode forecast evidence references", exception);
        }
    }

    private static Boolean nullableBoolean(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getBoolean(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(OffsetDateTime value) {
        return Objects.requireNonNull(value, "database timestamp").toInstant();
    }

    private static Instant nullableInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
