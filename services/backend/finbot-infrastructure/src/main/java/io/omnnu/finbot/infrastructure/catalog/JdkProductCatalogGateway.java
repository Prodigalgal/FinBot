package io.omnnu.finbot.infrastructure.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.catalog.CatalogInstrumentSnapshot;
import io.omnnu.finbot.application.catalog.CatalogSyncScope;
import io.omnnu.finbot.application.catalog.ProductCatalogGateway;
import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class JdkProductCatalogGateway implements ProductCatalogGateway {
    private static final int MAXIMUM_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final String GATE_API_BASE = "https://api.gateio.ws/api/v4";
    private static final List<String> BYBIT_API_BASES = List.of(
            "https://api.bybit.com",
            "https://api.bytick.com");

    private final RoutedHttpClientFactory httpClients;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdkProductCatalogGateway(
            RoutedHttpClientFactory httpClients,
            ObjectMapper objectMapper,
            Clock clock) {
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<CatalogInstrumentSnapshot> fetch(CatalogSyncScope scope) {
        Objects.requireNonNull(scope, "scope");
        return switch (scope.exchange()) {
            case GATE -> gate(scope.marketType());
            case BYBIT -> bybit(scope.marketType());
        };
    }

    private List<CatalogInstrumentSnapshot> gate(MarketType marketType) {
        return switch (marketType) {
            case SPOT -> gateSpot();
            case LINEAR_PERPETUAL -> gateLinear();
            default -> throw new IllegalArgumentException("Unsupported Gate catalog market");
        };
    }

    private List<CatalogInstrumentSnapshot> gateSpot() {
        var pairs = get(
                URI.create(GATE_API_BASE + "/spot/currency_pairs"),
                OutboundRoute.EXCHANGE_GATE);
        var tickers = prices(
                get(URI.create(GATE_API_BASE + "/spot/tickers"), OutboundRoute.EXCHANGE_GATE),
                "currency_pair");
        requireArray(pairs, "Gate spot instrument catalog");
        var observedAt = clock.instant();
        var snapshots = new ArrayList<CatalogInstrumentSnapshot>();
        pairs.forEach(pair -> snapshot(() -> {
            var symbol = token(pair, "id", 48);
            var quote = token(pair, "quote", 32);
            var quantityStep = precisionStep(integer(pair, "amount_precision", 8));
            return new CatalogInstrumentSnapshot(
                    token(pair, "base", 32),
                    quote,
                    symbol,
                    quote,
                    BigDecimal.ONE,
                    precisionStep(integer(pair, "precision", 8)),
                    quantityStep,
                    positive(pair, "min_base_amount", quantityStep),
                    BigDecimal.ONE,
                    "tradable".equalsIgnoreCase(text(pair, "trade_status"))
                            ? CatalogStatus.ACTIVE
                            : CatalogStatus.INACTIVE,
                    tickers.get(symbol),
                    observedAt);
        }, snapshots));
        return unique(snapshots);
    }

    private List<CatalogInstrumentSnapshot> gateLinear() {
        var contracts = get(
                URI.create(GATE_API_BASE + "/futures/usdt/contracts"),
                OutboundRoute.EXCHANGE_GATE);
        var tickers = prices(
                get(URI.create(GATE_API_BASE + "/futures/usdt/tickers"), OutboundRoute.EXCHANGE_GATE),
                "contract");
        requireArray(contracts, "Gate futures instrument catalog");
        var observedAt = clock.instant();
        var snapshots = new ArrayList<CatalogInstrumentSnapshot>();
        contracts.forEach(contract -> snapshot(() -> {
            var symbol = token(contract, "name", 48);
            var assets = splitSymbol(symbol, "USDT");
            var minimum = positive(contract, "order_size_min", BigDecimal.ONE);
            return new CatalogInstrumentSnapshot(
                    assets.base(),
                    assets.quote(),
                    symbol,
                    token(contract, "settle", 32, "USDT"),
                    positive(contract, "quanto_multiplier", BigDecimal.ONE),
                    positive(contract, "order_price_round", new BigDecimal("0.00000001")),
                    BigDecimal.ONE,
                    minimum,
                    positive(contract, "leverage_max", BigDecimal.ONE),
                    activeGateContract(contract) ? CatalogStatus.ACTIVE : CatalogStatus.INACTIVE,
                    tickers.get(symbol),
                    observedAt);
        }, snapshots));
        return unique(snapshots);
    }

    private List<CatalogInstrumentSnapshot> bybit(MarketType marketType) {
        RuntimeException lastFailure = null;
        for (var base : BYBIT_API_BASES) {
            try {
                return bybit(base, marketType);
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        throw Objects.requireNonNullElseGet(lastFailure, () -> new IllegalStateException(
                "Bybit catalog request failed on every configured host"));
    }

    private List<CatalogInstrumentSnapshot> bybit(String apiBase, MarketType marketType) {
        var category = marketType == MarketType.SPOT ? "spot" : "linear";
        var tickerRoot = get(
                URI.create(apiBase + "/v5/market/tickers?category=" + category),
                OutboundRoute.EXCHANGE_BYBIT);
        requireBybitSuccess(tickerRoot);
        var tickers = prices(tickerRoot.path("result").path("list"), "symbol");
        var rows = new ArrayList<JsonNode>();
        var cursor = "";
        var seenCursors = new HashSet<String>();
        do {
            var endpoint = apiBase + "/v5/market/instruments-info?category=" + category + "&limit=1000";
            if (!cursor.isBlank()) {
                endpoint += "&cursor=" + encode(cursor);
            }
            var root = get(URI.create(endpoint), OutboundRoute.EXCHANGE_BYBIT);
            requireBybitSuccess(root);
            var page = root.path("result").path("list");
            requireArray(page, "Bybit instrument catalog");
            page.forEach(rows::add);
            cursor = root.path("result").path("nextPageCursor").asText("").strip();
        } while (!cursor.isBlank() && seenCursors.add(cursor));

        var observedAt = clock.instant();
        var snapshots = new ArrayList<CatalogInstrumentSnapshot>();
        rows.forEach(instrument -> snapshot(() -> bybitSnapshot(
                instrument,
                marketType,
                tickers,
                observedAt), snapshots));
        return unique(snapshots);
    }

    private static CatalogInstrumentSnapshot bybitSnapshot(
            JsonNode instrument,
            MarketType marketType,
            Map<String, BigDecimal> tickers,
            java.time.Instant observedAt) {
        var symbol = token(instrument, "symbol", 48);
        var base = token(instrument, "baseCoin", 32);
        var quote = token(instrument, "quoteCoin", 32);
        var lot = instrument.path("lotSizeFilter");
        var price = instrument.path("priceFilter");
        var quantityStep = positive(
                lot,
                marketType == MarketType.SPOT ? "basePrecision" : "qtyStep",
                new BigDecimal("0.00000001"));
        var maximumLeverage = marketType == MarketType.SPOT
                ? BigDecimal.ONE
                : positive(instrument.path("leverageFilter"), "maxLeverage", BigDecimal.ONE);
        return new CatalogInstrumentSnapshot(
                base,
                quote,
                symbol,
                token(instrument, "settleCoin", 32, quote),
                BigDecimal.ONE,
                positive(price, "tickSize", new BigDecimal("0.00000001")),
                quantityStep,
                positive(lot, "minOrderQty", quantityStep),
                maximumLeverage,
                "Trading".equalsIgnoreCase(text(instrument, "status"))
                        ? CatalogStatus.ACTIVE
                        : CatalogStatus.INACTIVE,
                tickers.get(symbol),
                observedAt);
    }

    private JsonNode get(URI endpoint, OutboundRoute route) {
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("User-Agent", "FinBot/2.0 product-catalog")
                .GET()
                .build();
        try {
            var response = httpClients.client(route).send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                var bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
                if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                    throw new IllegalStateException("Exchange catalog response exceeded the safety limit");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                            "Exchange catalog endpoint returned HTTP " + response.statusCode());
                }
                return objectMapper.readTree(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Exchange catalog request was interrupted", exception);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Exchange catalog returned invalid JSON", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Exchange catalog request failed: " + exception.getClass().getSimpleName(),
                    exception);
        }
    }

    private static Map<String, BigDecimal> prices(JsonNode rows, String symbolField) {
        if (!rows.isArray()) {
            return Map.of();
        }
        var values = new HashMap<String, BigDecimal>();
        rows.forEach(row -> {
            var symbol = optionalToken(row.path(symbolField).asText(""), 48);
            var price = optionalPositive(row.path("last").asText(row.path("lastPrice").asText("")));
            if (symbol != null && price != null) {
                values.put(symbol, price);
            }
        });
        return Map.copyOf(values);
    }

    private static List<CatalogInstrumentSnapshot> unique(List<CatalogInstrumentSnapshot> values) {
        return values.stream()
                .collect(Collectors.toMap(
                        CatalogInstrumentSnapshot::symbol,
                        Function.identity(),
                        (first, ignored) -> first))
                .values().stream()
                .sorted(java.util.Comparator.comparing(CatalogInstrumentSnapshot::symbol))
                .toList();
    }

    private static void snapshot(
            java.util.function.Supplier<CatalogInstrumentSnapshot> supplier,
            List<CatalogInstrumentSnapshot> target) {
        try {
            target.add(supplier.get());
        } catch (IllegalArgumentException ignored) {
            // Providers occasionally expose transitional rows without usable trading constraints.
        }
    }

    private static boolean activeGateContract(JsonNode contract) {
        return !contract.path("in_delisting").asBoolean(false)
                && !"delisting".equalsIgnoreCase(text(contract, "status"));
    }

    private static AssetPair splitSymbol(String symbol, String quote) {
        var compact = symbol.replace("_", "").replace("-", "");
        if (!compact.endsWith(quote) || compact.length() <= quote.length()) {
            throw new IllegalArgumentException("Unable to derive assets from exchange symbol");
        }
        return new AssetPair(compact.substring(0, compact.length() - quote.length()), quote);
    }

    private static BigDecimal positive(JsonNode node, String field, BigDecimal fallback) {
        var parsed = optionalPositive(node.path(field).asText(""));
        return parsed == null ? fallback : parsed;
    }

    private static BigDecimal optionalPositive(String value) {
        try {
            var parsed = new BigDecimal(value);
            return parsed.signum() > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal precisionStep(int precision) {
        if (precision < 0 || precision > 18) {
            throw new IllegalArgumentException("Exchange precision is outside supported range");
        }
        return BigDecimal.ONE.movePointLeft(precision);
    }

    private static int integer(JsonNode node, String field, int fallback) {
        var value = node.path(field);
        return value.canConvertToInt() ? value.intValue() : fallback;
    }

    private static String token(JsonNode node, String field, int maximumLength) {
        return token(node, field, maximumLength, null);
    }

    private static String token(JsonNode node, String field, int maximumLength, String fallback) {
        var value = node.path(field).asText(Objects.requireNonNullElse(fallback, ""));
        var normalized = optionalToken(value, maximumLength);
        if (normalized == null) {
            throw new IllegalArgumentException("Exchange catalog token is invalid: " + field);
        }
        return normalized;
    }

    private static String optionalToken(String value, int maximumLength) {
        var normalized = value.strip().toUpperCase(Locale.ROOT);
        return !normalized.isEmpty()
                        && normalized.length() <= maximumLength
                        && normalized.matches("[A-Z0-9_-]+")
                ? normalized
                : null;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }

    private static void requireArray(JsonNode node, String label) {
        if (!node.isArray()) {
            throw new IllegalStateException(label + " was not an array");
        }
    }

    private static void requireBybitSuccess(JsonNode root) {
        if (root.path("retCode").asInt(-1) != 0) {
            throw new IllegalStateException("Bybit rejected the public catalog request");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record AssetPair(String base, String quote) {
    }
}
