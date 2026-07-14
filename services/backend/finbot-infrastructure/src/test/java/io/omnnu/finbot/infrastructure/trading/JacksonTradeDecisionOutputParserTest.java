package io.omnnu.finbot.infrastructure.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import io.omnnu.finbot.domain.trading.NonDirectionalAction;
import org.junit.jupiter.api.Test;

class JacksonTradeDecisionOutputParserTest {
    private final JacksonTradeDecisionOutputParser parser = new JacksonTradeDecisionOutputParser(
            JsonMapper.builder().build());

    @Test
    void parsesStrictDirectionalDraftAndApprovedReflection() {
        var decision = """
                {"action":"BUY","symbol":"BTC_USDT","confidence":0.82,
                 "entry_reference":60000,"target_price":63000,"invalidation_price":58500,
                 "rationale":["evidence aligned"],"evidence_refs":["artifact:quant"]}
                """;

        var draft = parser.parseDraft(decision).decision();
        var reflection = parser.parseReflection("""
                {"verdict":"APPROVE","reasons":["checked"],"decision":%s}
                """.formatted(decision)).reflection();

        assertEquals(DirectionalAction.BUY, draft.action());
        assertEquals(DirectionalAction.BUY, reflection.decision().action());
    }

    @Test
    void requiresNullPricesForWatchAndRejectHasNoDecision() {
        var draft = parser.parseDraft("""
                {"action":"WATCH","symbol":"BTCUSDT","confidence":0.45,
                 "entry_reference":null,"target_price":null,"invalidation_price":null,
                 "rationale":["insufficient evidence"],"evidence_refs":["artifact:evidence"]}
                """).decision();
        var reflection = parser.parseReflection("""
                {"verdict":"REJECT","reasons":["not executable"],"decision":null}
                """).reflection();

        assertEquals(NonDirectionalAction.WATCH, draft.action());
        assertEquals(false, reflection.approved());
    }

    @Test
    void rejectsUnexpectedFieldsAndContradictoryNonDirectionalPrices() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseDraft("""
                {"action":"WATCH","symbol":"BTCUSDT","confidence":0.45,
                 "entry_reference":60000,"target_price":null,"invalidation_price":null,
                 "rationale":["wait"],"evidence_refs":["artifact:evidence"]}
                """));
        assertThrows(IllegalArgumentException.class, () -> parser.parseReflection("""
                {"verdict":"REJECT","reasons":["no"],"decision":null,"reasoning":"hidden"}
                """));
    }
}
