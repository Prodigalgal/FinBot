package io.omnnu.finbot.domain.quant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuantResearchTypeTest {
    @Test
    void preservesDecimalParameterWithoutFloatingPointConversion() {
        ResearchParameter parameter = new ResearchParameter.DecimalParameter(
                "risk.budget",
                new BigDecimal("0.000000000000000001"));

        var decimal = (ResearchParameter.DecimalParameter) parameter;
        assertEquals(new BigDecimal("0.000000000000000001"), decimal.value());
        assertEquals("DECIMAL", decimal.valueType());
    }

    @Test
    void rejectsDuplicateParameterNames() {
        assertThrows(IllegalArgumentException.class, () -> specification(List.of(
                new ResearchParameter.IntegerValue("window", 10),
                new ResearchParameter.IntegerValue("window", 20))));
    }

    @Test
    void exposesStableTerminalEventType() {
        QuantResearchEvent event = new ResearchFailedEvent(
                new QuantResearchEventId("quant_event_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                new ResearchRunId("research_01j0000000001"),
                3,
                Instant.parse("2026-07-13T12:00:00Z"),
                ResearchErrorCode.TIMEOUT,
                "Research timed out",
                true);

        assertEquals("research.failed", event.eventType());
        assertTrue(event.terminal());
    }

    private static QuantResearchSpecification specification(List<ResearchParameter> parameters) {
        return new QuantResearchSpecification(
                ResearchKind.BACKTEST,
                List.of(new QuantInstrument(
                        QuantExchange.GATE,
                        new io.omnnu.finbot.domain.market.InstrumentSymbol("BTC_USDT"),
                        QuantMarketType.PERPETUAL,
                        "USDT")),
                new ResearchTimeRange(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-02-01T00:00:00Z")),
                new ResearchArtifact(
                        ArtifactKind.INPUT_MARKET_DATA,
                        java.net.URI.create("s3://finbot/input.parquet"),
                        "a".repeat(64),
                        "application/vnd.apache.parquet",
                        1_024),
                "breakout",
                "1.0.0",
                parameters,
                42);
    }
}
