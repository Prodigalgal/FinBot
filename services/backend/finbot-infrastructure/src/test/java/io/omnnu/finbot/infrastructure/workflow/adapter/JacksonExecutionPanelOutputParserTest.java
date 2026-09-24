package io.omnnu.finbot.infrastructure.workflow.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.NonDirectionalAction;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class JacksonExecutionPanelOutputParserTest {
    private final JacksonExecutionPanelOutputParser parser =
            new JacksonExecutionPanelOutputParser(new ObjectMapper());

    @Test
    void parsesDirectionalProposalSuccessfully() {
        var json = """
                {
                  "action": "BUY",
                  "symbol": "BTCUSDT",
                  "confidence": "0.85",
                  "entry_reference": "65000.0",
                  "target_price": "68000.0",
                  "invalidation_price": "63500.0",
                  "rationale": ["流动性支撑充裕", "滑点控制在万分之五内"],
                  "evidence_refs": ["ref1", "ref2"]
                }
                """;

        var draft = parser.parseProposal(json).draft();
        assertEquals(DirectionalAction.BUY, draft.action());
        assertEquals("BTCUSDT", draft.symbol().value());
        assertEquals(0, new BigDecimal("0.85").compareTo(draft.confidence().value()));
        assertEquals(0, new BigDecimal("65000.0").compareTo(draft.entryReference().value()));
        assertEquals(0, new BigDecimal("68000.0").compareTo(draft.targetPrice().value()));
        assertEquals(0, new BigDecimal("63500.0").compareTo(draft.invalidationPrice().value()));
        assertEquals(2, draft.rationale().size());
        assertEquals(2, draft.evidenceReferences().size());
    }

    @Test
    void parsesNonDirectionalProposalSuccessfully() {
        var json = """
                {
                  "action": "WATCH",
                  "symbol": "BTCUSDT",
                  "confidence": "0.50",
                  "entry_reference": null,
                  "target_price": null,
                  "invalidation_price": null,
                  "rationale": ["流动性不足，等待突破"],
                  "evidence_refs": []
                }
                """;

        var draft = parser.parseProposal(json).draft();
        assertEquals(NonDirectionalAction.WATCH, draft.action());
        assertEquals(0, new BigDecimal("0.50").compareTo(draft.confidence().value()));
    }

    @Test
    void rejectsDisallowedFields() {
        var json = """
                {
                  "action": "BUY",
                  "symbol": "BTCUSDT",
                  "confidence": "0.85",
                  "entry_reference": "65000.0",
                  "target_price": "68000.0",
                  "invalidation_price": "63500.0",
                  "rationale": ["ok"],
                  "evidence_refs": [],
                  "unauthorized_field": "leak"
                }
                """;

        assertThrows(IllegalArgumentException.class, () -> parser.parseProposal(json));
    }
}
