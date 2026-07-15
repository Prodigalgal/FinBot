package io.omnnu.finbot.infrastructure.market;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.market.EncodedMarketDataArtifact;
import io.omnnu.finbot.application.market.MarketCandle;
import io.omnnu.finbot.application.market.MarketDataArtifactRecord;
import io.omnnu.finbot.application.market.MarketDataRepository;
import io.omnnu.finbot.application.market.ResearchInstrument;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.research.ResearchDataPlane;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcMarketDataRepository implements MarketDataRepository {
    private static final String UPSERT_CANDLE = """
            insert into market_candle_fact (
              instrument_id, exchange, environment, symbol, interval_seconds, open_time,
              open_price, high_price, low_price, close_price, volume, turnover,
              funding_rate, source_endpoint, observed_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (instrument_id, environment, interval_seconds, open_time) do update
            set open_price = excluded.open_price,
                high_price = excluded.high_price,
                low_price = excluded.low_price,
                close_price = excluded.close_price,
                volume = excluded.volume,
                turnover = excluded.turnover,
                funding_rate = excluded.funding_rate,
                source_endpoint = excluded.source_endpoint,
                observed_at = excluded.observed_at
            """;

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    public JdbcMarketDataRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResearchInstrument> listResearchInstruments() {
        return jdbcClient.sql("""
                select instrument.instrument_id, instrument.exchange,
                       instrument.market_type, instrument.symbol, instrument.settlement_asset
                from watchlist_item item
                join watchlist list on list.watchlist_id = item.watchlist_id
                join lateral (
                  select candidate.instrument_id, candidate.exchange, candidate.market_type,
                         candidate.symbol, candidate.settlement_asset
                  from venue_instrument candidate
                  where candidate.product_id = item.product_id
                    and candidate.status = 'ACTIVE'
                  order by
                    case when candidate.instrument_id = item.preferred_instrument_id then 0 else 1 end,
                    case candidate.market_type
                      when 'LINEAR_PERPETUAL' then 0
                      when 'INVERSE_PERPETUAL' then 1
                      when 'SPOT' then 2
                      else 3
                    end,
                    case candidate.exchange when 'GATE' then 0 else 1 end,
                    candidate.symbol,
                    candidate.instrument_id
                  limit 1
                ) instrument on true
                where list.owner_id = 'admin'
                  and list.is_default = true
                  and item.research_mode in ('RESEARCH', 'PINNED')
                order by instrument.exchange, instrument.symbol, instrument.instrument_id
                """)
                .query((resultSet, rowNumber) -> new ResearchInstrument(
                        new InstrumentId(resultSet.getString("instrument_id")),
                        ExchangeVenue.valueOf(resultSet.getString("exchange")),
                        MarketType.valueOf(resultSet.getString("market_type")),
                        resultSet.getString("symbol"),
                        resultSet.getString("settlement_asset")))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchInstrument> findInstrument(InstrumentId instrumentId) {
        return jdbcClient.sql("""
                select instrument_id, exchange, market_type, symbol, settlement_asset
                from venue_instrument
                where instrument_id = :instrumentId and status = 'ACTIVE'
                """)
                .param("instrumentId", instrumentId.value())
                .query((resultSet, rowNumber) -> new ResearchInstrument(
                        instrumentId,
                        ExchangeVenue.valueOf(resultSet.getString("exchange")),
                        MarketType.valueOf(resultSet.getString("market_type")),
                        resultSet.getString("symbol"),
                        resultSet.getString("settlement_asset")))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchInstrument> findResearchInstrument(
            ExchangeVenue exchange,
            String symbol) {
        return jdbcClient.sql("""
                select instrument_id, market_type, settlement_asset
                from venue_instrument
                where exchange = :exchange and symbol = :symbol and status = 'ACTIVE'
                order by execution_enabled desc, instrument_id
                limit 1
                """)
                .param("exchange", exchange.name())
                .param("symbol", symbol)
                .query((resultSet, rowNumber) -> new ResearchInstrument(
                        new InstrumentId(resultSet.getString("instrument_id")),
                        exchange,
                        MarketType.valueOf(resultSet.getString("market_type")),
                        symbol,
                        resultSet.getString("settlement_asset")))
                .optional();
    }

    @Override
    @Transactional
    public void saveCandles(List<MarketCandle> candles) {
        if (candles.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPSERT_CANDLE, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                var candle = candles.get(index);
                statement.setString(1, candle.instrumentId().value());
                statement.setString(2, candle.exchange().name());
                statement.setString(3, candle.environment().name());
                statement.setString(4, candle.symbol());
                statement.setInt(5, candle.intervalSeconds());
                statement.setObject(6, OffsetDateTime.ofInstant(candle.openTime(), java.time.ZoneOffset.UTC));
                statement.setBigDecimal(7, candle.open());
                statement.setBigDecimal(8, candle.high());
                statement.setBigDecimal(9, candle.low());
                statement.setBigDecimal(10, candle.close());
                statement.setBigDecimal(11, candle.volume());
                statement.setBigDecimal(12, candle.turnover());
                statement.setBigDecimal(13, candle.fundingRate());
                statement.setString(14, candle.sourceEndpoint());
                statement.setObject(15, OffsetDateTime.ofInstant(candle.observedAt(), java.time.ZoneOffset.UTC));
            }

            @Override
            public int getBatchSize() {
                return candles.size();
            }
        });
    }

    @Override
    public void saveResearchScope(
            WorkflowRunId workflowRunId,
            ResearchInstrument instrument,
            io.omnnu.finbot.application.market.MarketAnalysisScope scope,
            java.math.BigDecimal marketReferencePrice,
            java.time.Instant capturedAt) {
        jdbcClient.sql("""
                insert into research_market_scope (
                  workflow_run_id, instrument_id, exchange, environment, symbol,
                  interval_seconds, forecast_horizon_seconds, market_reference_price, captured_at
                ) values (
                  :workflowRunId, :instrumentId, :exchange, :environment, :symbol,
                  :intervalSeconds, :forecastHorizonSeconds, :marketReferencePrice, :capturedAt
                ) on conflict (workflow_run_id) do update
                set instrument_id = excluded.instrument_id,
                    exchange = excluded.exchange,
                    environment = excluded.environment,
                    symbol = excluded.symbol,
                    interval_seconds = excluded.interval_seconds,
                    forecast_horizon_seconds = excluded.forecast_horizon_seconds,
                    market_reference_price = excluded.market_reference_price,
                    captured_at = excluded.captured_at
                """)
                .param("workflowRunId", workflowRunId.value())
                .param("instrumentId", instrument.instrumentId().value())
                .param("exchange", instrument.exchange().name())
                .param("environment", scope.environment().name())
                .param("symbol", instrument.symbol())
                .param("intervalSeconds", scope.intervalSeconds())
                .param("forecastHorizonSeconds", scope.forecastHorizonSeconds())
                .param("marketReferencePrice", marketReferencePrice)
                .param("capturedAt", timestamp(capturedAt))
                .update();
    }

    @Override
    public void saveArtifact(MarketDataArtifactRecord artifact) {
        var payload = artifact.encoded().payload();
        jdbcClient.sql("""
                insert into market_data_artifact (
                  artifact_id, workflow_run_id, data_plane, schema_version, content, payload,
                  sha256_hex, byte_size, media_type, candle_count, created_at
                ) values (
                  :artifactId, :workflowRunId, :dataPlane, :schemaVersion, cast(:content as jsonb), :payload,
                  :sha256Hex, :byteSize, :mediaType, :candleCount, :createdAt
                )
                on conflict (artifact_id) do nothing
                """)
                .param("artifactId", artifact.artifactId().value())
                .param("workflowRunId", artifact.workflowRunId().value())
                .param("dataPlane", artifact.dataPlane().name())
                .param("schemaVersion", artifact.schemaVersion())
                .param("content", new String(payload, java.nio.charset.StandardCharsets.UTF_8))
                .param("payload", payload)
                .param("sha256Hex", artifact.encoded().sha256Hex())
                .param("byteSize", payload.length)
                .param("mediaType", artifact.encoded().mediaType())
                .param("candleCount", artifact.encoded().candleCount())
                .param("createdAt", timestamp(artifact.createdAt()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MarketDataArtifactRecord> findArtifact(ResearchArtifactId artifactId) {
        return jdbcClient.sql("""
                select workflow_run_id, data_plane, schema_version, payload, sha256_hex,
                       media_type, candle_count, created_at
                from market_data_artifact where artifact_id = :artifactId
                """)
                .param("artifactId", artifactId.value())
                .query((resultSet, rowNumber) -> new MarketDataArtifactRecord(
                        artifactId,
                        new WorkflowRunId(resultSet.getString("workflow_run_id")),
                        ResearchDataPlane.valueOf(resultSet.getString("data_plane")),
                        resultSet.getInt("schema_version"),
                        new EncodedMarketDataArtifact(
                                resultSet.getBytes("payload"),
                                resultSet.getString("sha256_hex"),
                                resultSet.getString("media_type"),
                                resultSet.getInt("candle_count")),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
