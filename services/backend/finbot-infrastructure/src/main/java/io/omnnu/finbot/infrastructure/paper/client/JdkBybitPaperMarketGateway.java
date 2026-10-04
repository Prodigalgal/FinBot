package io.omnnu.finbot.infrastructure.paper.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.exception.MarketDataFetchException;
import io.omnnu.finbot.application.network.exception.ProxyRouteUnavailableException;
import io.omnnu.finbot.application.operations.service.TaskCancellationContext;
import io.omnnu.finbot.application.paper.port.out.LocalPaperMarketGateway;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.domain.paper.PaperMarketObservation;
import io.omnnu.finbot.domain.paper.PaperTrade;
import io.omnnu.finbot.infrastructure.network.client.RoutedHttpClientFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class JdkBybitPaperMarketGateway implements LocalPaperMarketGateway {
    private static final List<String> BASES = List.of("https://api.bybit.com", "https://api.bytick.com");
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final long MAXIMUM_REPLAY_SECONDS = Duration.ofDays(3).toSeconds();
    private final RoutedHttpClientFactory clients;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, CachedResponse> instruments = new ConcurrentHashMap<>();
    private volatile CachedResponse tickers;

    public JdkBybitPaperMarketGateway(RoutedHttpClientFactory clients, ObjectMapper json, Clock clock) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PaperMarketObservation observe(PaperTrade trade) {
        try {
            var metadata = metadata(trade.symbol());
            validateInstrument(metadata, trade);
            var response = tickerResponse(trade.quoteAt());
            var ticker = symbolRow(response.root(), trade.symbol());
            var orderbook = get("/v5/market/orderbook?category=linear&symbol=" + encode(trade.symbol()) + "&limit=1");
            var quote = decodeQuote(orderbook.root(), response.root(), ticker, clock.instant(),
                    orderbook.endpoint() + "+" + response.endpoint());
            var from = trade.candleCursor().plusSeconds(60);
            var through = quote.occurredAt().truncatedTo(ChronoUnit.MINUTES).minusSeconds(60);
            if (Duration.between(from, through).getSeconds() > MAXIMUM_REPLAY_SECONDS) {
                throw failure("PAPER_REPLAY_LIMIT", "行情缺口超过三天的可验证回补范围");
            }
            var candles = candles("/v5/market/kline", trade.symbol(), from, through);
            var markCandles = candles("/v5/market/mark-price-kline", trade.symbol(), from, through);
            var bars = new ArrayList<PaperMarketObservation.Bar>();
            for (var row : candles.values()) {
                var at = time(row.path(0));
                var mark = markCandles.get(at);
                if (mark == null) continue; // The engine detects the missing minute before advancing any state.
                bars.add(new PaperMarketObservation.Bar(at, decimal(row.path(1)), decimal(row.path(2)),
                        decimal(row.path(3)), decimal(row.path(4)), decimal(row.path(5)), decimal(mark.path(1)),
                        decimal(mark.path(2)), decimal(mark.path(3)), "BYBIT_LIVE:/v5/market/kline+/v5/market/mark-price-kline"));
            }
            return observationWithFunding(trade, metadata, ticker, quote, bars, markCandles);
        } catch (ProxyRouteUnavailableException failure) {
            throw failure("PAPER_MARKET_PROXY_UNAVAILABLE", failure.getMessage());
        } catch (IllegalArgumentException failure) {
            throw failure("PAPER_MARKET_DATA_INVALID", "Bybit 行情或合约参数无效");
        }
    }

    private PaperMarketObservation observationWithFunding(PaperTrade trade, JsonNode metadata, JsonNode ticker,
            PaperMarketObservation.Quote quote, List<PaperMarketObservation.Bar> bars, Map<Instant, JsonNode> marks) {
        var intervalMinutes = metadata.path("fundingInterval").asInt(0);
        if (intervalMinutes < 1 || intervalMinutes > 1440) throw failure("PAPER_FUNDING_INTERVAL_INVALID", "资金费周期缺失");
        var interval = intervalMinutes * 60L;
        var next = time(ticker.path("nextFundingTime"));
        if (!next.isAfter(quote.occurredAt())) {
            return new PaperMarketObservation(quote, bars, List.of(), trade.fundingCursor());
        }
        var due = new ArrayList<Instant>();
        for (var at = next.minusSeconds(interval); at.isAfter(trade.fundingCursor()); at = at.minusSeconds(interval)) {
            if (due.size() >= 200) throw failure("PAPER_FUNDING_REPLAY_LIMIT", "资金费回补超出上限");
            due.add(at);
        }
        if (due.isEmpty()) return new PaperMarketObservation(quote, bars, List.of(), quote.occurredAt());
        var history = get("/v5/market/funding/history?category=linear&symbol=" + encode(trade.symbol())
                + "&startTime=" + trade.fundingCursor().plusMillis(1).toEpochMilli()
                + "&endTime=" + quote.occurredAt().toEpochMilli() + "&limit=200");
        var rates = new HashMap<Instant, JsonNode>();
        for (var row : rows(history.root())) {
            if (!trade.symbol().equals(row.path("symbol").asText())) throw failure("PAPER_FUNDING_SYMBOL_INVALID", "资金费标的不匹配");
            var at = time(row.path("fundingRateTimestamp"));
            if (!at.isAfter(trade.fundingCursor()) || at.isAfter(quote.occurredAt())) continue;
            if (rates.put(at, row) != null) throw failure("PAPER_FUNDING_DUPLICATE", "资金费时点重复");
        }
        if (due.stream().anyMatch(at -> !rates.containsKey(at))) {
            return new PaperMarketObservation(quote, bars, List.of(), trade.fundingCursor());
        }
        var funding = new ArrayList<PaperMarketObservation.Funding>();
        for (var entry : rates.entrySet()) {
            var at = entry.getKey();
            var mark = marks.get(at);
            if (mark == null) mark = candles("/v5/market/mark-price-kline", trade.symbol(), at, at).get(at);
            if (mark == null) return new PaperMarketObservation(quote, bars, List.of(), trade.fundingCursor());
            funding.add(new PaperMarketObservation.Funding(at, decimal(entry.getValue().path("fundingRate")),
                    decimal(mark.path(1)), "BYBIT_LIVE:/v5/market/funding/history+mark-price-kline(open)"));
        }
        return new PaperMarketObservation(quote, bars, funding, quote.occurredAt());
    }

    private JsonNode metadata(String symbol) {
        var cached = instruments.get(symbol);
        if (cached == null || cached.fetchedAt().isBefore(clock.instant().minusSeconds(300))) {
            if (instruments.size() >= 200) instruments.clear();
            cached = get("/v5/market/instruments-info?category=linear&symbol=" + encode(symbol));
            instruments.put(symbol, cached);
        }
        return symbolRow(cached.root(), symbol);
    }

    private synchronized CachedResponse tickerResponse(Instant previousQuote) {
        var cached = tickers;
        if (cached == null || cached.fetchedAt().isBefore(clock.instant().minusSeconds(5))
                || (previousQuote != null && !time(cached.root().path("time")).isAfter(previousQuote))) {
            cached = get("/v5/market/tickers?category=linear");
            tickers = cached;
        }
        return cached;
    }

    private Map<Instant, JsonNode> candles(String path, String symbol, Instant from, Instant through) {
        var result = new HashMap<Instant, JsonNode>();
        if (through.isBefore(from)) return result;
        var end = through;
        for (var page = 0; page < 5 && !end.isBefore(from); page++) {
            var response = get(path + "?category=linear&symbol=" + encode(symbol) + "&interval=1&limit=1000"
                    + "&start=" + from.toEpochMilli() + "&end=" + end.toEpochMilli());
            if (!symbol.equals(response.root().path("result").path("symbol").asText())) {
                throw failure("PAPER_CANDLE_SYMBOL_INVALID", "K 线标的不匹配");
            }
            var oldest = end.plusSeconds(60);
            for (var row : rows(response.root())) {
                if (!row.isArray() || row.size() < (path.endsWith("mark-price-kline") ? 5 : 7)) {
                    throw failure("PAPER_CANDLE_ROW_INVALID", "K 线字段缺失");
                }
                var at = time(row.path(0));
                if (!at.equals(at.truncatedTo(ChronoUnit.MINUTES))) throw failure("PAPER_CANDLE_TIME_INVALID", "K 线时间未对齐");
                if (at.isBefore(from) || at.isAfter(end)) continue;
                if (result.put(at, row) != null) throw failure("PAPER_CANDLE_DUPLICATE", "K 线时点重复");
                if (at.isBefore(oldest)) oldest = at;
            }
            if (oldest.isAfter(end)) break;
            end = oldest.minusSeconds(60);
        }
        return result;
    }

    static void validateInstrument(JsonNode instrument, PaperTrade trade) {
        if (!trade.symbol().equals(instrument.path("symbol").asText())
                || !"Trading".equals(instrument.path("status").asText())
                || !"LinearPerpetual".equals(instrument.path("contractType").asText())
                || !"USDT".equals(instrument.path("quoteCoin").asText())
                || !"USDT".equals(instrument.path("settleCoin").asText())
                || instrument.path("isPreListing").asBoolean(false)) {
            throw failure("PAPER_INSTRUMENT_UNSUPPORTED", "只支持当前可交易的 Bybit USDT 永续合约");
        }
        var lots = instrument.path("lotSizeFilter");
        var quantity = trade.terms().quantity();
        var step = decimal(lots.path("qtyStep"));
        var tick = decimal(instrument.path("priceFilter").path("tickSize"));
        if (step.signum() <= 0 || tick.signum() <= 0 || quantity.remainder(step).signum() != 0
                || quantity.compareTo(decimal(lots.path("minOrderQty"))) < 0
                || quantity.compareTo(decimal(lots.path("maxOrderQty"))) > 0
                || trade.terms().limitPrice().remainder(tick).signum() != 0
                || trade.terms().leverage().compareTo(decimal(instrument.path("leverageFilter").path("maxLeverage"))) > 0
                || trade.terms().notional(trade.terms().limitPrice()).compareTo(decimal(lots.path("minNotionalValue"))) < 0) {
            throw failure("PAPER_INSTRUMENT_LIMITS", "模拟计划超出当前合约的数量、价格步长或额度限制");
        }
    }

    static PaperMarketObservation.Quote decodeQuote(JsonNode orderbookRoot, JsonNode tickerRoot, JsonNode ticker,
            Instant observedAt, String endpoint) {
        var book = orderbookRoot.path("result");
        var bid = book.path("b").path(0);
        var ask = book.path("a").path(0);
        if (!book.path("s").asText().equals(ticker.path("symbol").asText())
                || !bid.isArray() || bid.size() != 2 || !ask.isArray() || ask.size() != 2) {
            throw failure("PAPER_ORDERBOOK_INVALID", "盘口与标记价标的不匹配或买卖档位缺失");
        }
        var producedAt = time(book.path("cts"));
        var generatedAt = time(book.path("ts"));
        var responseAt = time(orderbookRoot.path("time"));
        var markObservedAt = time(tickerRoot.path("time"));
        if (producedAt.isAfter(generatedAt) || generatedAt.isAfter(responseAt)
                || responseAt.isAfter(observedAt) || markObservedAt.isAfter(observedAt)
                || markObservedAt.isBefore(observedAt.minusSeconds(30))) {
            throw failure("PAPER_QUOTE_TIME_INVALID", "盘口时间顺序或标记价快照有效期无效");
        }
        return new PaperMarketObservation.Quote(book.path("s").asText(), decimal(bid.path(0)),
                decimal(ask.path(0)), decimal(bid.path(1)), decimal(ask.path(1)),
                decimal(ticker.path("markPrice")), producedAt, observedAt, endpoint);
    }

    private CachedResponse get(String path) {
        MarketDataFetchException last = null;
        for (var base : BASES) {
            TaskCancellationContext.throwIfCancelled();
            var endpoint = base + path;
            try {
                var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json").GET().build();
                var response = clients.client(OutboundRoute.EXCHANGE_BYBIT).send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var body = response.body()) {
                    var bytes = body.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
                    if (response.statusCode() / 100 != 2) throw failure("PAPER_MARKET_HTTP", "Bybit public HTTP " + response.statusCode());
                    if (bytes.length > MAXIMUM_RESPONSE_BYTES) throw failure("PAPER_MARKET_RESPONSE_TOO_LARGE", "行情响应超出上限");
                    var root = json.readTree(bytes);
                    if (root == null || !root.path("retCode").isIntegralNumber() || root.path("retCode").asLong() != 0) {
                        throw failure("PAPER_MARKET_REJECTED", "Bybit 未返回成功的公开行情");
                    }
                    return new CachedResponse(root, endpoint, clock.instant());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure("PAPER_MARKET_INTERRUPTED", "行情读取被中断");
            } catch (IOException network) {
                last = failure("PAPER_MARKET_NETWORK", "行情网络或 JSON 错误: " + network.getClass().getSimpleName());
            } catch (MarketDataFetchException rejected) {
                last = rejected;
            }
        }
        throw Objects.requireNonNull(last);
    }

    private static JsonNode rows(JsonNode root) {
        var rows = root.path("result").path("list");
        if (!rows.isArray() || !"linear".equals(root.path("result").path("category").asText())) {
            throw failure("PAPER_MARKET_SCHEMA_INVALID", "Bybit linear 行情结构无效");
        }
        return rows;
    }

    private static JsonNode symbolRow(JsonNode root, String symbol) {
        JsonNode found = null;
        for (var row : rows(root)) {
            if (!symbol.equals(row.path("symbol").asText())) continue;
            if (found != null) throw failure("PAPER_MARKET_SYMBOL_DUPLICATE", "行情标的重复");
            found = row;
        }
        if (found == null) throw failure("PAPER_MARKET_SYMBOL_MISSING", "当前公开行情没有指定标的");
        return found;
    }

    private static BigDecimal decimal(JsonNode value) {
        try {
            if (!value.isTextual() && !value.isNumber()) throw new NumberFormatException();
            var number = new BigDecimal(value.asText());
            if (number.precision() > 38 || Math.abs(number.scale()) > 18) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException invalid) { throw failure("PAPER_MARKET_NUMBER_INVALID", "行情数值缺失或无效"); }
    }

    private static Instant time(JsonNode value) {
        try {
            var milliseconds = Long.parseLong(value.asText());
            if (milliseconds <= 0) throw new NumberFormatException();
            return Instant.ofEpochMilli(milliseconds);
        } catch (RuntimeException invalid) { throw failure("PAPER_MARKET_TIME_INVALID", "行情时间缺失或无效"); }
    }

    private static String encode(String symbol) { return URLEncoder.encode(symbol, StandardCharsets.UTF_8); }
    private static MarketDataFetchException failure(String code, String message) { return new MarketDataFetchException(code, message); }
    private record CachedResponse(JsonNode root, String endpoint, Instant fetchedAt) { }
}
