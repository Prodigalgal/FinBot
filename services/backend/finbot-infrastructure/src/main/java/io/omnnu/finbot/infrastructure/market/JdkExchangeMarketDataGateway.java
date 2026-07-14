package io.omnnu.finbot.infrastructure.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.market.MarketCandle;
import io.omnnu.finbot.application.market.MarketDataFetchException;
import io.omnnu.finbot.application.market.MarketDataGateway;
import io.omnnu.finbot.application.market.ResearchInstrument;
import io.omnnu.finbot.application.network.ProxyRouteUnavailableException;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class JdkExchangeMarketDataGateway implements MarketDataGateway {
    private static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final String GATE_API_BASE = "https://api.gateio.ws/api/v4";
    private static final List<String> BYBIT_API_BASES = List.of(
            "https://api.bybit.com",
            "https://api.bytick.com");

    private final RoutedHttpClientFactory httpClients;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdkExchangeMarketDataGateway(
            RoutedHttpClientFactory httpClients,
            ObjectMapper objectMapper,
            Clock clock) {
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<MarketCandle> fetchCandles(
            ResearchInstrument instrument,
            int intervalSeconds,
            int limit) {
        var safeLimit = Math.max(2, Math.min(limit, 500));
        try {
            return switch (instrument.exchange()) {
                case GATE -> gate(instrument, intervalSeconds, safeLimit);
                case BYBIT -> bybit(instrument, intervalSeconds, safeLimit);
            };
        } catch (ProxyRouteUnavailableException exception) {
            throw new MarketDataFetchException(
                    "MARKET_PROXY_UNAVAILABLE",
                    exception.getMessage());
        }
    }

    private List<MarketCandle> gate(
            ResearchInstrument instrument,
            int intervalSeconds,
            int limit) {
        var endpoint = URI.create(GATE_API_BASE
                + "/futures/usdt/candlesticks?contract=" + encode(instrument.symbol())
                + "&interval=" + encode(interval(intervalSeconds, ExchangeVenue.GATE))
                + "&limit=" + limit);
        var root = get(endpoint, OutboundRoute.EXCHANGE_GATE);
        if (!root.isArray()) {
            throw new MarketDataFetchException(
                    "GATE_CANDLE_RESPONSE_INVALID",
                    "Gate candle response was not an array");
        }
        var observedAt = clock.instant();
        var candles = new ArrayList<MarketCandle>();
        root.forEach(row -> {
            if (!row.isArray() || row.size() < 6) {
                return;
            }
            candles.add(new MarketCandle(
                    instrument.instrumentId(),
                    ExchangeVenue.GATE,
                    instrument.symbol(),
                    intervalSeconds,
                    Instant.ofEpochSecond(row.get(0).asLong()),
                    decimal(row.get(5)),
                    decimal(row.get(3)),
                    decimal(row.get(4)),
                    decimal(row.get(2)),
                    decimal(row.get(1)),
                    row.size() > 6 ? decimal(row.get(6)) : null,
                    BigDecimal.ZERO,
                    endpoint.toString(),
                    observedAt));
        });
        return ordered(candles);
    }

    private List<MarketCandle> bybit(
            ResearchInstrument instrument,
            int intervalSeconds,
            int limit) {
        MarketDataFetchException lastFailure = null;
        for (var base : BYBIT_API_BASES) {
            var endpoint = URI.create(base
                    + "/v5/market/kline?category=linear&symbol=" + encode(instrument.symbol())
                    + "&interval=" + encode(interval(intervalSeconds, ExchangeVenue.BYBIT))
                    + "&limit=" + limit);
            try {
                var root = get(endpoint, OutboundRoute.EXCHANGE_BYBIT);
                if (root.path("retCode").asInt(-1) != 0) {
                    throw new MarketDataFetchException(
                            "BYBIT_CANDLE_PROVIDER_ERROR",
                            "Bybit rejected the public candle request");
                }
                var rows = root.path("result").path("list");
                if (!rows.isArray()) {
                    throw new MarketDataFetchException(
                            "BYBIT_CANDLE_RESPONSE_INVALID",
                            "Bybit candle response did not contain a result list");
                }
                var observedAt = clock.instant();
                var candles = new ArrayList<MarketCandle>();
                rows.forEach(row -> {
                    if (!row.isArray() || row.size() < 6) {
                        return;
                    }
                    candles.add(new MarketCandle(
                            instrument.instrumentId(),
                            ExchangeVenue.BYBIT,
                            instrument.symbol(),
                            intervalSeconds,
                            Instant.ofEpochMilli(row.get(0).asLong()),
                            decimal(row.get(1)),
                            decimal(row.get(2)),
                            decimal(row.get(3)),
                            decimal(row.get(4)),
                            decimal(row.get(5)),
                            row.size() > 6 ? decimal(row.get(6)) : null,
                            BigDecimal.ZERO,
                            endpoint.toString(),
                            observedAt));
                });
                return ordered(candles);
            } catch (MarketDataFetchException exception) {
                lastFailure = exception;
            }
        }
        throw Objects.requireNonNullElseGet(lastFailure, () -> new MarketDataFetchException(
                "BYBIT_CANDLE_FETCH_FAILED",
                "Bybit public candle request failed on every configured host"));
    }

    private JsonNode get(URI endpoint, OutboundRoute route) {
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "FinBot/2.0 market-data")
                .GET()
                .build();
        try {
            var response = httpClients.client(route).send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                var bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
                if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                    throw new MarketDataFetchException(
                            "MARKET_RESPONSE_TOO_LARGE",
                            "Exchange market response exceeded the safety limit");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    var category = response.statusCode() == 403
                            ? "MARKET_REGION_BLOCKED"
                            : "MARKET_HTTP_" + response.statusCode();
                    throw new MarketDataFetchException(
                            category,
                            "Exchange market endpoint returned HTTP " + response.statusCode());
                }
                return objectMapper.readTree(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MarketDataFetchException(
                    "MARKET_REQUEST_INTERRUPTED",
                    "Exchange market request was interrupted");
        } catch (JsonProcessingException exception) {
            throw new MarketDataFetchException(
                    "MARKET_RESPONSE_JSON_INVALID",
                    "Exchange market endpoint returned invalid JSON");
        } catch (IOException exception) {
            throw new MarketDataFetchException(
                    "MARKET_NETWORK_FAILURE",
                    "Exchange market request failed: " + exception.getClass().getSimpleName());
        }
    }

    private static List<MarketCandle> ordered(List<MarketCandle> candles) {
        return candles.stream()
                .sorted(Comparator.comparing(MarketCandle::openTime))
                .distinct()
                .toList();
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || (!value.isNumber() && !value.isTextual())) {
            throw new MarketDataFetchException(
                    "MARKET_CANDLE_VALUE_INVALID",
                    "Exchange candle contained a non-numeric value");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw new MarketDataFetchException(
                    "MARKET_CANDLE_VALUE_INVALID",
                    "Exchange candle contained an invalid decimal value");
        }
    }

    private static String interval(int seconds, ExchangeVenue exchange) {
        return switch (seconds) {
            case 60 -> exchange == ExchangeVenue.BYBIT ? "1" : "1m";
            case 180 -> exchange == ExchangeVenue.BYBIT ? "3" : "3m";
            case 300 -> exchange == ExchangeVenue.BYBIT ? "5" : "5m";
            case 900 -> exchange == ExchangeVenue.BYBIT ? "15" : "15m";
            case 1_800 -> exchange == ExchangeVenue.BYBIT ? "30" : "30m";
            case 3_600 -> exchange == ExchangeVenue.BYBIT ? "60" : "1h";
            case 7_200 -> exchange == ExchangeVenue.BYBIT ? "120" : "2h";
            case 14_400 -> exchange == ExchangeVenue.BYBIT ? "240" : "4h";
            case 86_400 -> exchange == ExchangeVenue.BYBIT ? "D" : "1d";
            case 604_800 -> exchange == ExchangeVenue.BYBIT ? "W" : "7d";
            default -> throw new IllegalArgumentException("Unsupported market candle interval");
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
