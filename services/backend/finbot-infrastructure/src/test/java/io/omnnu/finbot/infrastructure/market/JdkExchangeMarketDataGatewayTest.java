package io.omnnu.finbot.infrastructure.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.MarketDataFetchException;
import io.omnnu.finbot.application.market.ResearchInstrument;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.catalog.MarketType;
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
                        3_600,
                        ENDPOINT,
                        OBSERVED_AT));

        assertEquals("GATE_CANDLE_ROW_INVALID", failure.errorCode());
    }
}
