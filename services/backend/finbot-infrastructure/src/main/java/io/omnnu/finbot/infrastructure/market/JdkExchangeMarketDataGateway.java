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
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
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
    private static final String GATE_LIVE_API_BASE = "https://api.gateio.ws/api/v4";
    private static final String GATE_TESTNET_API_BASE = "https://fx-api-testnet.gateio.ws/api/v4";
    private static final List<String> BYBIT_LIVE_API_BASES = List.of(
            "https://api.bybit.com",
            "https://api.bytick.com");
    private static final List<String> BYBIT_DEMO_API_BASES = List.of("https://api-demo.bybit.com");

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
            ExchangeEnvironment environment,
            int intervalSeconds,
            int limit) {
        var safeLimit = Math.max(2, Math.min(limit, 500));
        try {
            return switch (instrument.exchange()) {
                case GATE -> gate(instrument, environment, intervalSeconds, safeLimit);
                case BYBIT -> bybit(instrument, environment, intervalSeconds, safeLimit);
            };
        } catch (ProxyRouteUnavailableException exception) {
            throw new MarketDataFetchException(
                    "MARKET_PROXY_UNAVAILABLE",
                    exception.getMessage());
        }
    }

    private List<MarketCandle> gate(
            ResearchInstrument instrument,
            ExchangeEnvironment environment,
            int intervalSeconds,
            int limit) {
        var base = switch (environment) {
            case LIVE -> GATE_LIVE_API_BASE;
            case TESTNET -> GATE_TESTNET_API_BASE;
            case DEMO -> throw new MarketDataFetchException(
                    "GATE_MARKET_ENVIRONMENT_UNSUPPORTED",
                    "Gate does not expose a DEMO market environment");
        };
        if (instrument.marketType() == MarketType.SPOT) {
            if (environment != ExchangeEnvironment.LIVE) {
                throw unsupported(instrument, environment);
            }
            var endpoint = URI.create(base
                    + "/spot/candlesticks?currency_pair=" + encode(instrument.symbol())
                    + "&interval=" + encode(interval(intervalSeconds, ExchangeVenue.GATE))
                    + "&limit=" + limit);
            return parseGateSpotCandles(
                    get(endpoint, OutboundRoute.EXCHANGE_GATE),
                    instrument,
                    environment,
                    intervalSeconds,
                    endpoint,
                    clock.instant());
        }
        if (instrument.marketType() != MarketType.LINEAR_PERPETUAL
                && instrument.marketType() != MarketType.FUTURE) {
            throw unsupported(instrument, environment);
        }
        var endpoint = URI.create(base
                + "/futures/usdt/candlesticks?contract=" + encode(instrument.symbol())
                + "&interval=" + encode(interval(intervalSeconds, ExchangeVenue.GATE))
                + "&limit=" + limit);
        var root = get(endpoint, OutboundRoute.EXCHANGE_GATE);
        return parseGateCandles(
                root,
                instrument,
                environment,
                intervalSeconds,
                endpoint,
                clock.instant());
    }

    static List<MarketCandle> parseGateSpotCandles(
            JsonNode root,
            ResearchInstrument instrument,
            ExchangeEnvironment environment,
            int intervalSeconds,
            URI endpoint,
            Instant observedAt) {
        if (!root.isArray()) {
            throw new MarketDataFetchException(
                    "GATE_SPOT_CANDLE_RESPONSE_INVALID",
                    "Gate spot candle response was not an array");
        }
        var candles = new ArrayList<MarketCandle>();
        root.forEach(row -> {
            if (!row.isArray() || row.size() < 7) {
                throw new MarketDataFetchException(
                        "GATE_SPOT_CANDLE_ROW_INVALID",
                        "Gate spot candle response contained an invalid row");
            }
            candles.add(new MarketCandle(
                    instrument.instrumentId(),
                    ExchangeVenue.GATE,
                    environment,
                    instrument.symbol(),
                    intervalSeconds,
                    gateOpenTime(row.get(0)),
                    decimal(row.get(5)),
                    decimal(row.get(3)),
                    decimal(row.get(4)),
                    decimal(row.get(2)),
                    decimal(row.get(6)),
                    decimal(row.get(1)),
                    BigDecimal.ZERO,
                    endpoint.toString(),
                    observedAt));
        });
        return ordered(candles);
    }

    static List<MarketCandle> parseGateCandles(
            JsonNode root,
            ResearchInstrument instrument,
            ExchangeEnvironment environment,
            int intervalSeconds,
            URI endpoint,
            Instant observedAt) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!root.isArray()) {
            throw new MarketDataFetchException(
                    "GATE_CANDLE_RESPONSE_INVALID",
                    "Gate candle response was not an array");
        }
        var candles = new ArrayList<MarketCandle>();
        root.forEach(row -> {
            if (!row.isObject()) {
                throw new MarketDataFetchException(
                        "GATE_CANDLE_ROW_INVALID",
                        "Gate candle response contained a non-object row");
            }
            candles.add(new MarketCandle(
                    instrument.instrumentId(),
                    ExchangeVenue.GATE,
                    environment,
                    instrument.symbol(),
                    intervalSeconds,
                    gateOpenTime(row.get("t")),
                    decimal(row.get("o")),
                    decimal(row.get("h")),
                    decimal(row.get("l")),
                    decimal(row.get("c")),
                    decimal(row.get("v")),
                    row.hasNonNull("sum") ? decimal(row.get("sum")) : null,
                    BigDecimal.ZERO,
                    endpoint.toString(),
                    observedAt));
        });
        return ordered(candles);
    }

    private List<MarketCandle> bybit(
            ResearchInstrument instrument,
            ExchangeEnvironment environment,
            int intervalSeconds,
            int limit) {
        MarketDataFetchException lastFailure = null;
        var bases = switch (environment) {
            case LIVE -> BYBIT_LIVE_API_BASES;
            case DEMO -> BYBIT_DEMO_API_BASES;
            case TESTNET -> throw new MarketDataFetchException(
                    "BYBIT_MARKET_ENVIRONMENT_UNSUPPORTED",
                    "Bybit Demo accounts do not use the TESTNET environment");
        };
        var category = switch (instrument.marketType()) {
            case SPOT -> "spot";
            case LINEAR_PERPETUAL, FUTURE -> "linear";
            case INVERSE_PERPETUAL -> "inverse";
        };
        for (var base : bases) {
            var endpoint = URI.create(base
                    + "/v5/market/kline?category=" + category + "&symbol=" + encode(instrument.symbol())
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
                            environment,
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

    private static MarketDataFetchException unsupported(
            ResearchInstrument instrument,
            ExchangeEnvironment environment) {
        return new MarketDataFetchException(
                "MARKET_CAPABILITY_UNSUPPORTED",
                instrument.exchange() + " " + instrument.marketType()
                        + " does not support market data in " + environment);
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

    private static Instant gateOpenTime(JsonNode value) {
        if (value == null || (!value.isNumber() && !value.isTextual())) {
            throw new MarketDataFetchException(
                    "GATE_CANDLE_TIME_INVALID",
                    "Gate candle contained a non-numeric timestamp");
        }
        try {
            var epochSecond = Long.parseLong(value.asText());
            if (epochSecond <= 0) {
                throw new NumberFormatException("Timestamp must be positive");
            }
            return Instant.ofEpochSecond(epochSecond);
        } catch (NumberFormatException exception) {
            throw new MarketDataFetchException(
                    "GATE_CANDLE_TIME_INVALID",
                    "Gate candle contained an invalid timestamp");
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
