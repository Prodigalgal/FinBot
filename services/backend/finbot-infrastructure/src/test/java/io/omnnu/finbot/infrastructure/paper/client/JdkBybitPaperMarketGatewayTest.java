package io.omnnu.finbot.infrastructure.paper.client;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.exception.MarketDataFetchException;
import io.omnnu.finbot.domain.paper.*;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdkBybitPaperMarketGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Test void acceptsCryptoAndTradFiByContractIdentityRatherThanLegacyCategory() throws Exception {
        var metadata = json.readTree(metadataJson());
        JdkBybitPaperMarketGateway.validateInstrument(metadata, trade());
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata).put("symbolType", "ETF");
        JdkBybitPaperMarketGateway.validateInstrument(metadata, trade());
    }
    @Test void rejectsPrelistingWrongSettlementAndInvalidQuantityOrPriceStep() throws Exception {
        var metadata = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(metadataJson());
        metadata.put("settleCoin", "USDC");
        assertThrows(MarketDataFetchException.class, () -> JdkBybitPaperMarketGateway.validateInstrument(metadata, trade()));
        metadata.put("settleCoin", "USDT").put("isPreListing", true);
        assertThrows(MarketDataFetchException.class, () -> JdkBybitPaperMarketGateway.validateInstrument(metadata, trade()));
        metadata.put("isPreListing", false);
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("priceFilter")).put("tickSize", "0.3");
        assertThrows(MarketDataFetchException.class, () -> JdkBybitPaperMarketGateway.validateInstrument(metadata, trade()));
    }
    @Test void usesMatchingEngineTimeRatherThanHttpResponseTimeAndRejectsMissingOrCrossedQuotes() throws Exception {
        var root = json.readTree("{\"time\":1791072000000}");
        var book = json.readTree("""
                {"time":1791072000000,"result":{"s":"BTCUSDT","b":[["99","1"]],"a":[["100","2"]],
                "ts":1791071999999,"cts":1791071999000}}
                """);
        var ticker = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
                {"symbol":"BTCUSDT","bid1Price":"99","ask1Price":"100","bid1Size":"1","ask1Size":"2","markPrice":"100"}
                """);
        var quote = JdkBybitPaperMarketGateway.decodeQuote(book, root, ticker, NOW, "PUBLIC");
        assertEquals(NOW.minusSeconds(1), quote.occurredAt());
        assertEquals(new BigDecimal("2"), quote.askSize());
        ticker.remove("markPrice");
        assertThrows(MarketDataFetchException.class, () -> JdkBybitPaperMarketGateway.decodeQuote(book, root, ticker, NOW, "PUBLIC"));
        ticker.put("markPrice", "100");
        ((com.fasterxml.jackson.databind.node.ArrayNode) book.path("result").path("b").path(0)).set(0, json.getNodeFactory().textNode("101"));
        assertThrows(IllegalArgumentException.class, () -> JdkBybitPaperMarketGateway.decodeQuote(book, root, ticker, NOW, "PUBLIC"));
    }
    @Test void cannotTreatFreshHttpResponseAsFreshOrderbookOrUseStaleMarkSnapshot() throws Exception {
        var root = json.readTree("{\"time\":1791072000000}");
        var book = json.readTree("""
                {"time":1791072000000,"result":{"s":"BTCUSDT","b":[["99","1"]],"a":[["100","2"]],
                "ts":1791071900000,"cts":1791071900000}}
                """);
        var ticker = json.readTree("{\"symbol\":\"BTCUSDT\",\"markPrice\":\"100\"}");
        var quote = JdkBybitPaperMarketGateway.decodeQuote(book, root, ticker, NOW, "PUBLIC");
        assertEquals(NOW.minusSeconds(100), quote.occurredAt());
        assertEquals(PaperTrade.Health.QUOTE_UNAVAILABLE,
                new PaperMatchingEngine().evaluate(trade(), new PaperMarketObservation(quote, java.util.List.of(), java.util.List.of(), NOW), NOW).trade().healthCode());
        var stale = json.readTree("{\"time\":1791071900000}");
        assertThrows(MarketDataFetchException.class, () -> JdkBybitPaperMarketGateway.decodeQuote(book, stale, ticker, NOW, "PUBLIC"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) book.path("result")).put("s", "QQQUSDT");
        assertThrows(MarketDataFetchException.class, () -> JdkBybitPaperMarketGateway.decodeQuote(book, root, ticker, NOW, "PUBLIC"));
    }
    private static String metadataJson() {
        return """
                {"symbol":"BTCUSDT","status":"Trading","contractType":"LinearPerpetual","quoteCoin":"USDT","settleCoin":"USDT",
                 "lotSizeFilter":{"qtyStep":"0.001","minOrderQty":"0.001","maxOrderQty":"1000","minNotionalValue":"5"},
                 "priceFilter":{"tickSize":"0.1"},"leverageFilter":{"maxLeverage":"100"}}
                """;
    }
    private static PaperTrade trade() {
        var terms = new PaperTradeTerms(DirectionalAction.BUY, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("110"), new BigDecimal("95"), new BigDecimal("2"), new BigDecimal("0.001"),
                BigDecimal.ZERO, new BigDecimal("0.005"), "policy_test", NOW.plusSeconds(600));
        return new PaperTrade("paper_test_market", "projection_test_market", "instrument_bybit_btcusdt", "BTCUSDT", terms,
                PaperTrade.Status.PENDING_ENTRY, NOW, null, null, null, null, NOW, NOW, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, PaperTrade.Health.READY, 0);
    }
}
