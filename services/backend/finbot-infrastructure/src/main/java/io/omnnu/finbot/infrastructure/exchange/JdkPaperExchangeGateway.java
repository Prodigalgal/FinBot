package io.omnnu.finbot.infrastructure.exchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.exchange.ExchangeAccountConfiguration;
import io.omnnu.finbot.application.exchange.ExchangeAccountConfigurationRepository;
import io.omnnu.finbot.application.exchange.ExchangeSubmissionResult;
import io.omnnu.finbot.application.exchange.ExchangeSubmissionStatus;
import io.omnnu.finbot.application.exchange.ExecutableOrder;
import io.omnnu.finbot.application.exchange.PaperExchangeGateway;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.infrastructure.network.RoutedHttpClientFactory;
import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class JdkPaperExchangeGateway implements PaperExchangeGateway {
    private static final URI GATE_BASE = URI.create("https://api-testnet.gateapi.io/api/v4");
    private static final URI BYBIT_BASE = URI.create("https://api-demo.bybit.com");
    private static final int BYBIT_RECEIVE_WINDOW_MILLISECONDS = 5_000;
    private static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final ExchangeAccountConfigurationRepository accountRepository;
    private final EnvironmentValueResolver environment;
    private final RoutedHttpClientFactory httpClients;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdkPaperExchangeGateway(
            ExchangeAccountConfigurationRepository accountRepository,
            EnvironmentValueResolver environment,
            RoutedHttpClientFactory httpClients,
            ObjectMapper objectMapper,
            Clock clock) {
        this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.httpClients = Objects.requireNonNull(httpClients, "httpClients");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<ExchangeSubmissionResult> findByClientOrderId(ExecutableOrder order) {
        var credentials = credentials(order);
        return switch (order.exchange()) {
            case GATE -> findGate(order, credentials);
            case BYBIT -> findBybit(order, credentials);
        };
    }

    @Override
    public ExchangeSubmissionResult submit(ExecutableOrder order) {
        var credentials = credentials(order);
        return switch (order.exchange()) {
            case GATE -> submitGate(order, credentials);
            case BYBIT -> submitBybit(order, credentials);
        };
    }

    private Optional<ExchangeSubmissionResult> findGate(
            ExecutableOrder order,
            Credentials credentials) {
        var now = clock.instant().getEpochSecond();
        var query = new LinkedHashMap<String, String>();
        query.put("contract", order.symbol().value());
        query.put("from", Long.toString(now - Duration.ofDays(7).toSeconds()));
        query.put("to", Long.toString(now));
        query.put("limit", "100");
        var response = gateRequest(
                "GET",
                "/futures/usdt/orders_timerange",
                query,
                "",
                credentials);
        if (!successful(response) || !response.json().isArray()) {
            return Optional.empty();
        }
        for (var item : response.json()) {
            if (order.clientOrderId().equals(item.path("text").asText())) {
                return Optional.of(acknowledged(
                        item.path("id").asText(null),
                        response,
                        "Recovered Gate TestNet order by client order id"));
            }
        }
        return Optional.empty();
    }

    private Optional<ExchangeSubmissionResult> findBybit(
            ExecutableOrder order,
            Credentials credentials) {
        var query = new LinkedHashMap<String, String>();
        query.put("category", "linear");
        query.put("symbol", order.symbol().value().replace("_", ""));
        query.put("orderLinkId", order.clientOrderId());
        var response = bybitRequest("GET", "/v5/order/realtime", query, "", credentials);
        if (!bybitSuccessful(response)) {
            return Optional.empty();
        }
        var rows = response.json().path("result").path("list");
        if (!rows.isArray() || rows.isEmpty()) {
            return Optional.empty();
        }
        var item = rows.get(0);
        return Optional.of(acknowledged(
                item.path("orderId").asText(null),
                response,
                "Recovered Bybit Demo order by client order id"));
    }

    private ExchangeSubmissionResult submitGate(
            ExecutableOrder order,
            Credentials credentials) {
        if (order.environment() != ExchangeEnvironment.TESTNET) {
            return rejected("GATE_ENVIRONMENT_BLOCKED", "Gate private writes require TESTNET", null);
        }
        var leverageQuery = new LinkedHashMap<String, String>();
        leverageQuery.put("leverage", decimal(order.leverage()));
        var leverage = gateRequest(
                "POST",
                "/futures/usdt/positions/" + encode(order.symbol().value()) + "/leverage",
                leverageQuery,
                "",
                credentials);
        if (!successful(leverage)) {
            return providerRejected("GATE_LEVERAGE_REJECTED", leverage);
        }
        final long unsignedQuantity;
        try {
            unsignedQuantity = order.quantity().setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException exception) {
            return rejected(
                    "GATE_QUANTITY_NOT_INTEGRAL",
                    "Gate futures quantity must be an integral contract count",
                    null);
        }
        var body = objectMapper.createObjectNode();
        body.put("contract", order.symbol().value());
        body.put("size", order.side() == io.omnnu.finbot.domain.trading.DirectionalAction.BUY
                ? unsignedQuantity
                : -unsignedQuantity);
        body.put("price", "0");
        body.put("tif", "ioc");
        body.put("reduce_only", false);
        body.put("text", order.clientOrderId());
        var response = gateRequest(
                "POST",
                "/futures/usdt/orders",
                new LinkedHashMap<>(),
                json(body),
                credentials);
        if (!successful(response)) {
            return providerRejected("GATE_ORDER_REJECTED", response);
        }
        return acknowledged(
                response.json().path("id").asText(null),
                response,
                "Gate TestNet accepted the order");
    }

    private ExchangeSubmissionResult submitBybit(
            ExecutableOrder order,
            Credentials credentials) {
        if (order.environment() != ExchangeEnvironment.DEMO) {
            return rejected("BYBIT_ENVIRONMENT_BLOCKED", "Bybit private writes require DEMO", null);
        }
        var symbol = order.symbol().value().replace("_", "");
        var leverageBody = objectMapper.createObjectNode();
        leverageBody.put("category", "linear");
        leverageBody.put("symbol", symbol);
        leverageBody.put("buyLeverage", decimal(order.leverage()));
        leverageBody.put("sellLeverage", decimal(order.leverage()));
        var leverage = bybitRequest(
                "POST",
                "/v5/position/set-leverage",
                new LinkedHashMap<>(),
                json(leverageBody),
                credentials);
        if (!bybitSuccessful(leverage)) {
            return providerRejected("BYBIT_LEVERAGE_REJECTED", leverage);
        }
        var body = objectMapper.createObjectNode();
        body.put("category", "linear");
        body.put("symbol", symbol);
        body.put("side", order.side() == io.omnnu.finbot.domain.trading.DirectionalAction.BUY
                ? "Buy"
                : "Sell");
        body.put("orderType", "Market");
        body.put("qty", decimal(order.quantity()));
        body.put("timeInForce", "IOC");
        body.put("positionIdx", 0);
        body.put("orderLinkId", order.clientOrderId());
        body.put("slippageToleranceType", "Percent");
        body.put("slippageTolerance", "1");
        var response = bybitRequest(
                "POST",
                "/v5/order/create",
                new LinkedHashMap<>(),
                json(body),
                credentials);
        if (!bybitSuccessful(response)) {
            return providerRejected("BYBIT_ORDER_REJECTED", response);
        }
        return acknowledged(
                response.json().path("result").path("orderId").asText(null),
                response,
                "Bybit Demo accepted the order");
    }

    private HttpExchangeResponse gateRequest(
            String method,
            String path,
            LinkedHashMap<String, String> query,
            String body,
            Credentials credentials) {
        var queryString = query(query);
        var timestamp = Long.toString(clock.instant().getEpochSecond());
        var signature = GateRequestSigner.sign(
                credentials.secret(),
                method,
                GATE_BASE.getPath() + path,
                queryString,
                body,
                timestamp);
        var builder = request(GATE_BASE, path, queryString, method, body)
                .header("KEY", credentials.key())
                .header("Timestamp", timestamp)
                .header("SIGN", signature)
                .header("X-Gate-Exptime", Long.toString(clock.instant().plusSeconds(5).toEpochMilli()));
        return send(builder.build(), OutboundRoute.EXCHANGE_GATE);
    }

    private HttpExchangeResponse bybitRequest(
            String method,
            String path,
            LinkedHashMap<String, String> query,
            String body,
            Credentials credentials) {
        var queryString = query(query);
        var payloadText = "GET".equals(method) ? queryString : body;
        var timestamp = Long.toString(clock.instant().toEpochMilli());
        var signature = BybitRequestSigner.sign(
                credentials.secret(),
                timestamp,
                credentials.key(),
                BYBIT_RECEIVE_WINDOW_MILLISECONDS,
                payloadText);
        var builder = request(BYBIT_BASE, path, queryString, method, body)
                .header("X-BAPI-API-KEY", credentials.key())
                .header("X-BAPI-TIMESTAMP", timestamp)
                .header("X-BAPI-SIGN", signature)
                .header("X-BAPI-SIGN-TYPE", "2")
                .header("X-BAPI-RECV-WINDOW", Integer.toString(BYBIT_RECEIVE_WINDOW_MILLISECONDS));
        return send(builder.build(), OutboundRoute.EXCHANGE_BYBIT);
    }

    private HttpRequest.Builder request(
            URI base,
            String path,
            String query,
            String method,
            String body) {
        var endpoint = URI.create(base + path + (query.isEmpty() ? "" : "?" + query));
        var builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "FinBot/2.0 paper-execution");
        if ("GET".equals(method)) {
            return builder.GET();
        }
        return builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private HttpExchangeResponse send(HttpRequest request, OutboundRoute route) {
        try {
            var response = httpClients.client(route).send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                var bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
                if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
                    throw new IllegalStateException("Exchange response exceeded the safety limit");
                }
                var raw = new String(bytes, StandardCharsets.UTF_8);
                JsonNode root;
                try {
                    root = raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
                } catch (JsonProcessingException exception) {
                    root = objectMapper.createObjectNode().put("invalidJson", true);
                }
                return new HttpExchangeResponse(response.statusCode(), root, raw);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Exchange request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Exchange request failed", exception);
        }
    }

    private Credentials credentials(ExecutableOrder order) {
        var account = accountRepository.find(order.accountId())
                .orElseThrow(() -> new IllegalStateException("Exchange account does not exist"));
        requireMatchingAccount(order, account);
        var key = environment.resolve(account.apiKeyEnvironmentVariable())
                .orElseThrow(() -> new IllegalStateException("Exchange API key is not configured"));
        var secret = environment.resolve(account.apiSecretEnvironmentVariable())
                .orElseThrow(() -> new IllegalStateException("Exchange API secret is not configured"));
        return new Credentials(key, secret);
    }

    private static void requireMatchingAccount(
            ExecutableOrder order,
            ExchangeAccountConfiguration account) {
        if (!account.enabled()
                || account.exchange() != order.exchange()
                || account.environment() != order.environment()) {
            throw new IllegalStateException("Exchange account is disabled or does not match the order");
        }
        if ((order.exchange() == ExchangeVenue.GATE && order.environment() != ExchangeEnvironment.TESTNET)
                || (order.exchange() == ExchangeVenue.BYBIT
                        && order.environment() != ExchangeEnvironment.DEMO)) {
            throw new IllegalStateException("Mainnet private exchange writes are permanently blocked");
        }
    }

    private ExchangeSubmissionResult acknowledged(
            String exchangeOrderId,
            HttpExchangeResponse response,
            String message) {
        if (exchangeOrderId == null || exchangeOrderId.isBlank()) {
            return new ExchangeSubmissionResult(
                    ExchangeSubmissionStatus.UNKNOWN,
                    null,
                    response.statusCode(),
                    responseJson(response),
                    "EXCHANGE_ORDER_ID_MISSING",
                    "Exchange accepted a request without returning an order id");
        }
        return new ExchangeSubmissionResult(
                ExchangeSubmissionStatus.ACKNOWLEDGED,
                exchangeOrderId,
                response.statusCode(),
                responseJson(response),
                null,
                message);
    }

    private ExchangeSubmissionResult providerRejected(
            String errorCode,
            HttpExchangeResponse response) {
        var message = response.json().path("message").asText(
                response.json().path("retMsg").asText("Exchange rejected the request"));
        return rejected(errorCode, safe(message), response);
    }

    private ExchangeSubmissionResult rejected(
            String errorCode,
            String message,
            HttpExchangeResponse response) {
        return new ExchangeSubmissionResult(
                ExchangeSubmissionStatus.REJECTED,
                null,
                response == null ? null : response.statusCode(),
                response == null ? null : responseJson(response),
                errorCode,
                message);
    }

    private static boolean successful(HttpExchangeResponse response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static boolean bybitSuccessful(HttpExchangeResponse response) {
        return successful(response) && response.json().path("retCode").asInt(-1) == 0;
    }

    private String responseJson(HttpExchangeResponse response) {
        return json(response.json());
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode exchange JSON", exception);
        }
    }

    private static String query(LinkedHashMap<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + '=' + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decimal(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String safe(String value) {
        var normalized = Objects.requireNonNullElse(value, "Exchange rejected the request")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .strip();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }

    private record Credentials(String key, String secret) {
    }

    private record HttpExchangeResponse(int statusCode, JsonNode json, String rawBody) {
    }
}
