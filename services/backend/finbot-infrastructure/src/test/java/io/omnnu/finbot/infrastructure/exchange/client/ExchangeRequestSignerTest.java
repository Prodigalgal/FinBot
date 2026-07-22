package io.omnnu.finbot.infrastructure.exchange.client;

import io.omnnu.finbot.infrastructure.exchange.client.BybitRequestSigner;
import io.omnnu.finbot.infrastructure.exchange.client.GateRequestSigner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExchangeRequestSignerTest {
    @Test
    void gateSignatureMatchesFrozenApiV4Vector() {
        assertEquals(
                "fc424c6622da5cf9a3c466b5769de8dcefc9435dc1f8b6b0b5dc5d1ffe3543bfc4e9d0f991b39cc663ebe06dfb2f93783f750e1f99c32ef7b860aca82fcc14db",
                GateRequestSigner.sign(
                        "gate-secret",
                        "POST",
                        "/api/v4/futures/usdt/orders",
                        "",
                        "{\"contract\":\"BTC_USDT\",\"size\":1}",
                        "1700000000"));
    }

    @Test
    void bybitSignatureMatchesFrozenV5Vector() {
        assertEquals(
                "7c51a9423ec9181ad164d4d589bd42f9002c144d9eb59fa41dbd7afc5c16faf1",
                BybitRequestSigner.sign(
                        "bybit-secret",
                        "1700000000000",
                        "bybit-key",
                        5_000,
                        "{\"category\":\"linear\"}"));
    }
}
