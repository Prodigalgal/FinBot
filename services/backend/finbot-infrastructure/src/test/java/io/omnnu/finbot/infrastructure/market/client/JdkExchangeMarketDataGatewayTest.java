package io.omnnu.finbot.infrastructure.market.client;

import io.omnnu.finbot.infrastructure.market.client.JdkExchangeMarketDataGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.exception.MarketDataFetchException;
import io.omnnu.finbot.application.market.dto.ResearchInstrument;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class JdkExchangeMarketDataGatewayTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ResearchInstrument INSTRUMENT = new ResearchInstrument(
            new InstrumentId("instrument_gate_btc_usdt"),
            ExchangeVenue.GATE,
            MarketType.LINEAR_PERPETUAL,
            "BTC_USDT",
            "USDT");
    private static final URI ENDPOINT = URI.create(
            "https://api.gateio.ws/api/v4/futures/usdt/candlesticks");
    private static final ResearchInstrument BYBIT_INSTRUMENT = new ResearchInstrument(
            new InstrumentId("instrument_bybit_btc_usdt"),
            ExchangeVenue.BYBIT,
            MarketType.LINEAR_PERPETUAL,
            "BTCUSDT",
            "USDT");
    private static final URI BYBIT_ENDPOINT = URI.create("https://api.bybit.com/v5/market/kline");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-14T07:31:00Z");

    @Test
    void parsesTheCurrentGateObjectCandleSchemaAndOrdersAscending() throws Exception {
        var root = OBJECT_MAPPER.readTree("""
                [
                  {"t":1782219600,"o":"62233.9","h":"62510","l":"61916.3","c":"62450","v":71876625,"sum":"447001234.5"},
                  {"t":1782216000,"o":"62476.6","h":"62530.4","l":"62130","c":"62233.9","v":22613913,"sum":"140949113.96436"}
                ]
                """);

        var candles = JdkExchangeMarketDataGateway.parseGateCandles(
                root,
                INSTRUMENT,
                ExchangeEnvironment.LIVE,
                3_600,
                ENDPOINT,
                OBSERVED_AT);

        assertEquals(2, candles.size());
        assertEquals(Instant.ofEpochSecond(1_782_216_000L), candles.getFirst().openTime());
        assertEquals(new BigDecimal("62476.6"), candles.getFirst().open());
        assertEquals(new BigDecimal("62530.4"), candles.getFirst().high());
        assertEquals(new BigDecimal("62130"), candles.getFirst().low());
        assertEquals(new BigDecimal("62233.9"), candles.getFirst().close());
        assertEquals(new BigDecimal("22613913"), candles.getFirst().volume());
        assertEquals(new BigDecimal("140949113.96436"), candles.getFirst().turnover());
        assertEquals(OBSERVED_AT, candles.getFirst().observedAt());
    }

    @Test
    void rejectsLegacyArrayRowsInsteadOfSilentlyReturningNoCandles() throws Exception {
        var root = OBJECT_MAPPER.readTree("[[1782216000,22613913,\"62233.9\"]]");

        var failure = assertThrows(
                MarketDataFetchException.class,
                () -> JdkExchangeMarketDataGateway.parseGateCandles(
                        root,
                        INSTRUMENT,
                        ExchangeEnvironment.LIVE,
                        3_600,
                        ENDPOINT,
                        OBSERVED_AT));

        assertEquals("GATE_CANDLE_ROW_INVALID", failure.errorCode());
    }

    @Test
    void parsesGateSpotArraySchemaWithoutConfusingBaseAndQuoteVolume() throws Exception {
        var root = OBJECT_MAPPER.readTree("""
                [
                  ["1782216000", "140949113.96436", "62233.9", "62530.4", "62130", "62476.6", "2261.3913"]
                ]
                """);
        var spot = new ResearchInstrument(
                new InstrumentId("instrument_gate_btc_usdt_spot"),
                ExchangeVenue.GATE,
                MarketType.SPOT,
                "BTC_USDT",
                "USDT");

        var candles = JdkExchangeMarketDataGateway.parseGateSpotCandles(
                root,
                spot,
                ExchangeEnvironment.LIVE,
                3_600,
                URI.create("https://api.gateio.ws/api/v4/spot/candlesticks"),
                OBSERVED_AT);

        assertEquals(new BigDecimal("62476.6"), candles.getFirst().open());
        assertEquals(new BigDecimal("62233.9"), candles.getFirst().close());
        assertEquals(new BigDecimal("2261.3913"), candles.getFirst().volume());
        assertEquals(new BigDecimal("140949113.96436"), candles.getFirst().turnover());
    }

    @Test
    void parsesBybitRowsAndOrdersTheReverseChronologicalResponse() throws Exception {
        var rows = OBJECT_MAPPER.readTree("""
                [
                  ["1782219600000", "62233.9", "62510", "61916.3", "62450", "718.7", "44700123.45"],
                  ["1782216000000", "62476.6", "62530.4", "62130", "62233.9", "226.1", "14094911.39"]
                ]
                """);

        var candles = JdkExchangeMarketDataGateway.parseBybitCandles(
                rows,
                BYBIT_INSTRUMENT,
                ExchangeEnvironment.LIVE,
                3_600,
                BYBIT_ENDPOINT,
                OBSERVED_AT);

        assertEquals(2, candles.size());
        assertEquals(Instant.ofEpochMilli(1_782_216_000_000L), candles.getFirst().openTime());
        assertEquals(new BigDecimal("62476.6"), candles.getFirst().open());
        assertEquals(new BigDecimal("14094911.39"), candles.getFirst().turnover());
    }

    @Test
    void rejectsMalformedBybitRows() throws Exception {
        var rows = OBJECT_MAPPER.readTree("[[\"1782216000000\", \"62476.6\"]]");

        var failure = assertThrows(
                MarketDataFetchException.class,
                () -> JdkExchangeMarketDataGateway.parseBybitCandles(
                        rows,
                        BYBIT_INSTRUMENT,
                        ExchangeEnvironment.LIVE,
                        3_600,
                        BYBIT_ENDPOINT,
                        OBSERVED_AT));

        assertEquals("BYBIT_CANDLE_ROW_INVALID", failure.errorCode());
    }

    @Test
    void rejectsDuplicateBybitTimestamps() throws Exception {
        var rows = OBJECT_MAPPER.readTree("""
                [
                  ["1782216000000", "62476.6", "62530.4", "62130", "62233.9", "226.1"],
                  ["1782216000000", "62476.6", "62530.4", "62130", "62233.9", "226.1"]
                ]
                """);

        var failure = assertThrows(
                MarketDataFetchException.class,
                () -> JdkExchangeMarketDataGateway.parseBybitCandles(
                        rows,
                        BYBIT_INSTRUMENT,
                        ExchangeEnvironment.LIVE,
                        3_600,
                        BYBIT_ENDPOINT,
                        OBSERVED_AT));

        assertEquals("MARKET_CANDLE_DUPLICATE_TIMESTAMP", failure.errorCode());
    }

    @Test
    void rejectsBybitTimeSeriesGaps() throws Exception {
        var rows = OBJECT_MAPPER.readTree("""
                [
                  ["1782223200000", "62233.9", "62510", "61916.3", "62450", "718.7"],
                  ["1782216000000", "62476.6", "62530.4", "62130", "62233.9", "226.1"]
                ]
                """);

        var failure = assertThrows(
                MarketDataFetchException.class,
                () -> JdkExchangeMarketDataGateway.parseBybitCandles(
                        rows,
                        BYBIT_INSTRUMENT,
                        ExchangeEnvironment.LIVE,
                        3_600,
                        BYBIT_ENDPOINT,
                        OBSERVED_AT));

        assertEquals("MARKET_CANDLE_TIME_GAP", failure.errorCode());
    }
}
