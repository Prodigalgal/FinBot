package io.omnnu.finbot.infrastructure.exchange.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class BybitLeverageResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsAnAlreadyAppliedLeverageAsAnIdempotentSuccess() throws Exception {
        var unchanged = mapper.readTree("""
                {"retCode":110043,"retMsg":"leverage not modified","result":{}}
                """);
        assertTrue(JdkPaperExchangeGateway.bybitLeverageAccepted(200, unchanged));
        assertTrue(JdkPaperExchangeGateway.bybitLeverageAccepted(200, mapper.readTree("{\"retCode\":0}")));
    }

    @Test
    void keepsAuthenticationRiskAndHttpFailuresBlocked() {
        for (var code : new int[]{10002, 10003, 10005, 110013, 110036, 110038, 110044}) {
            assertFalse(JdkPaperExchangeGateway.bybitLeverageAccepted(
                    200, mapper.createObjectNode().put("retCode", code)));
        }
        assertFalse(JdkPaperExchangeGateway.bybitLeverageAccepted(503, mapper.createObjectNode().put("retCode", 110043)));
        assertFalse(JdkPaperExchangeGateway.bybitLeverageAccepted(200, mapper.createObjectNode()));
    }
}
