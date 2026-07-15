package io.omnnu.finbot.infrastructure.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.domain.research.ForecastDirection;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class JacksonStructuredAiOutputParserTest {
    private final JacksonStructuredAiOutputParser parser =
            new JacksonStructuredAiOutputParser(new ObjectMapper());

    @Test
    void parsesStrictDirectionalChairForecast() {
        var content = parser.parseChair("""
                {
                  "summary": "Chair synthesis",
                  "argument": "Evidence supports a bounded upside scenario.",
                  "confidence": 0.74,
                  "claims": [],
                  "evidence_refs": ["evidence_market_snapshot"],
                  "challenges": [],
                  "revision_notes": [],
                  "forecast": {
                    "direction": "UP",
                    "reference_price": 100,
                    "expected_low": 97,
                    "expected_high": 112,
                    "invalidation_price": 94,
                    "confidence": 0.71,
                    "thesis": "Demand and liquidity indicate a bounded upside move.",
                    "evidence_refs": ["evidence_market_snapshot"]
                  }
                }
                """);

        assertEquals(ForecastDirection.UP, content.forecast().direction());
        assertEquals(0, new BigDecimal("100").compareTo(content.forecast().referencePrice()));
        assertEquals(0, new BigDecimal("0.71").compareTo(content.forecast().confidence()));
    }

    @Test
    void parsesUncertainForecastOnlyWithNullPriceLevels() {
        var content = parser.parseChair("""
                {
                  "summary": "Chair abstention",
                  "argument": "Evidence is insufficient for a directional call.",
                  "forecast": {
                    "direction": "UNCERTAIN",
                    "reference_price": null,
                    "expected_low": null,
                    "expected_high": null,
                    "invalidation_price": null,
                    "confidence": 0.20,
                    "thesis": "Conflicting evidence prevents a reliable price range.",
                    "evidence_refs": []
                  }
                }
                """);

        assertEquals(ForecastDirection.UNCERTAIN, content.forecast().direction());
        assertNull(content.forecast().referencePrice());
    }

    @Test
    void rejectsUnsupportedFieldsAndInventedUncertainPrices() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseChair("""
                {
                  "summary": "Chair synthesis",
                  "argument": "Unknown fields must not enter the forecast contract.",
                  "forecast": {
                    "direction": "UP",
                    "reference_price": 100,
                    "expected_low": 95,
                    "expected_high": 110,
                    "invalidation_price": 92,
                    "confidence": 0.70,
                    "thesis": "A directional thesis with one unsupported field.",
                    "evidence_refs": ["evidence_market_snapshot"],
                    "target_return": 0.10
                  }
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> parser.parseChair("""
                {
                  "summary": "Chair abstention",
                  "argument": "Uncertain output cannot carry a reference price.",
                  "forecast": {
                    "direction": "UNCERTAIN",
                    "reference_price": 100,
                    "expected_low": null,
                    "expected_high": null,
                    "invalidation_price": null,
                    "confidence": 0.20,
                    "thesis": "Conflicting evidence prevents a reliable price range.",
                    "evidence_refs": []
                  }
                }
                """));
    }
}
