package io.omnnu.finbot.infrastructure.exchange.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore;
import io.omnnu.finbot.application.exchange.dto.ExchangeAccountConfiguration;
import io.omnnu.finbot.application.exchange.port.out.ExchangeAccountConfigurationRepository;
import io.omnnu.finbot.application.exchange.port.out.ExchangeAccountGateway;
import io.omnnu.finbot.application.exchange.dto.ExchangeAccountSyncBatch;
import io.omnnu.finbot.application.ledger.dto.AccountSnapshotFact;
import io.omnnu.finbot.application.ledger.dto.BalanceFact;
import io.omnnu.finbot.application.ledger.dto.FillFact;
import io.omnnu.finbot.application.ledger.dto.OrderFact;
import io.omnnu.finbot.application.ledger.dto.PositionSnapshotFact;
import io.omnnu.finbot.application.ledger.dto.RealizedPnlFact;
import io.omnnu.finbot.domain.ledger.BalanceChangeReason;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.ledger.ExchangeOrderStatus;
import io.omnnu.finbot.domain.ledger.ExchangeOrderType;
import io.omnnu.finbot.domain.ledger.LedgerFactId;
import io.omnnu.finbot.domain.ledger.LedgerOrderSide;
import io.omnnu.finbot.domain.ledger.PnlSourceType;
import io.omnnu.finbot.domain.ledger.PositionSide;
import io.omnnu.finbot.domain.market.InstrumentSymbol;
import io.omnnu.finbot.domain.market.Money;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class JdkExchangeAccountGateway implements ExchangeAccountGateway {
    private static final URI GATE_BASE = URI.create("https://api-testnet.gateapi.io/api/v4");
    private static final URI BYBIT_BASE = URI.create("https://api-demo.bybit.com");
    private static final int BYBIT_RECEIVE_WINDOW_MILLISECONDS = 5_000;
    private static final int PAGE_LIMIT = 1_000;

    private final ExchangeAccountConfigurationRepository accounts;
    private final RuntimeSecretStore runtimeSecrets;
    private final ExchangeAccountHttpTransport transport;
    private final Clock clock;

    public JdkExchangeAccountGateway(
            ExchangeAccountConfigurationRepository accounts,
            RuntimeSecretStore runtimeSecrets,
            ExchangeAccountHttpTransport transport,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExchangeAccountSyncBatch synchronize(
            ExchangeAccountId accountId,
            Instant fromInclusive,
            Instant toExclusive) {
        var account = accounts.find(accountId)
                .filter(ExchangeAccountConfiguration::enabled)
                .orElseThrow(() -> new IllegalStateException("Exchange account is missing or disabled"));
        requirePaperEnvironment(account);
        var credentials = credentials(account);
        return switch (account.exchange()) {
            case GATE -> gate(account, credentials, fromInclusive, toExclusive);
            case BYBIT -> bybit(account, credentials, fromInclusive, toExclusive);
        };
    }

    private ExchangeAccountSyncBatch gate(
            ExchangeAccountConfiguration account,
            Credentials credentials,
            Instant fromInclusive,
            Instant toExclusive) {
        var receivedAt = clock.instant();
        var accountJson = requireGate(gateRequest(
                "GET", "/futures/usdt/accounts", new LinkedHashMap<>(), "", credentials));
        var positionQuery = new LinkedHashMap<String, String>();
        positionQuery.put("holding", "true");
        positionQuery.put("limit", "100");
        positionQuery.put("offset", "0");
        var positionsJson = requireGate(gateRequest(
                "GET", "/futures/usdt/positions", positionQuery, "", credentials));
        var ordersPage = gateHistory(
                "/futures/usdt/orders_timerange", credentials, fromInclusive, toExclusive);
        var fillsPage = gateHistory(
                "/futures/usdt/my_trades_timerange", credentials, fromInclusive, toExclusive);
        var ledgerPage = gateHistory(
                "/futures/usdt/account_book", credentials, fromInclusive, toExclusive);
        var sourceSnapshotId = "gate-account:" + receivedAt.toEpochMilli();
        var currency = text(accountJson, "currency", "USDT").toUpperCase(Locale.ROOT);
        var wallet = requiredDecimal(accountJson, "total").max(BigDecimal.ZERO);
        var available = requiredDecimal(accountJson, "available").max(BigDecimal.ZERO);
        var unrealized = decimal(accountJson, "unrealised_pnl", BigDecimal.ZERO);
        var equity = wallet.add(unrealized).max(BigDecimal.ZERO);
        var margin = firstDecimal(
                accountJson,
                "position_margin",
                "position_initial_margin",
                "cross_initial_margin",
                "isolated_position_margin").max(BigDecimal.ZERO)
                .add(decimal(accountJson, "order_margin", BigDecimal.ZERO).max(BigDecimal.ZERO));
        var snapshot = new AccountSnapshotFact(
                id("snapshot_", sourceSnapshotId),
                account.accountId(),
                sourceSnapshotId,
                new Money(equity, currency),
                new Money(available, currency),
                new Money(margin, currency),
                new Money(unrealized, currency),
                receivedAt,
                receivedAt);
        var balances = List.of(new BalanceFact(
                id("balance_", sourceSnapshotId),
                account.accountId(),
                sourceSnapshotId,
                currency,
                wallet,
                available,
                null,
                BalanceChangeReason.SNAPSHOT,
                receivedAt,
                receivedAt));
        var positions = mapGatePositions(account.accountId(), positionsJson, receivedAt);
        var orders = mapGateOrders(account.accountId(), ordersPage.rows(), receivedAt);
        var fills = mapGateFills(account.accountId(), fillsPage.rows(), receivedAt);
        var pnl = mapGatePnl(account.accountId(), ledgerPage.rows(), currency, receivedAt);
        var complete = ordersPage.complete() && fillsPage.complete() && ledgerPage.complete();
        var warnings = complete
                ? List.<String>of()
                : List.of("Gate history reached the configured page limit; watermark was not advanced");
        return new ExchangeAccountSyncBatch(
                snapshot,
                balances,
                positions,
                orders,
                fills,
                pnl,
                toExclusive,
                complete,
                warnings);
    }

    private ExchangeAccountSyncBatch bybit(
            ExchangeAccountConfiguration account,
            Credentials credentials,
            Instant fromInclusive,
            Instant toExclusive) {
        var receivedAt = clock.instant();
        var walletQuery = new LinkedHashMap<String, String>();
        walletQuery.put("accountType", "UNIFIED");
        walletQuery.put("coin", "USDT");
        var walletResponse = bybitRequest(
                "GET", "/v5/account/wallet-balance", walletQuery, "", credentials);
        if (!bybitSuccessful(walletResponse)
                && java.util.Set.of(10008, 10028, 100028).contains(
                        walletResponse.json().path("retCode").asInt())) {
            walletQuery.put("accountType", "CONTRACT");
            walletResponse = bybitRequest(
                    "GET", "/v5/account/wallet-balance", walletQuery, "", credentials);
        }
        var walletRoot = requireBybit(walletResponse).path("result").path("list");
        if (!walletRoot.isArray() || walletRoot.isEmpty()) {
            throw new IllegalStateException("Bybit wallet response contains no account");
        }
        var accountJson = walletRoot.get(0);
        var coins = accountJson.path("coin");
        var usdt = firstCoin(coins, "USDT");
        var positionQuery = new LinkedHashMap<String, String>();
        positionQuery.put("category", "linear");
        positionQuery.put("settleCoin", "USDT");
        positionQuery.put("limit", "200");
        var positionsJson = requireBybit(bybitRequest(
                "GET", "/v5/position/list", positionQuery, "", credentials))
                .path("result").path("list");
        var ordersPage = bybitHistory(
                "/v5/order/history", credentials, fromInclusive, toExclusive);
        var fillsPage = bybitHistory(
                "/v5/execution/list", credentials, fromInclusive, toExclusive);
        var pnlPage = bybitHistory(
                "/v5/position/closed-pnl", credentials, fromInclusive, toExclusive);
        var sourceSnapshotId = "bybit-account:" + receivedAt.toEpochMilli();
        var equity = firstAvailableDecimal(usdt, accountJson, "equity", "totalEquity")
                .max(BigDecimal.ZERO);
        var wallet = firstAvailableDecimal(usdt, accountJson, "walletBalance", "totalWalletBalance")
                .max(BigDecimal.ZERO);
        var available = firstAvailableDecimal(
                usdt,
                accountJson,
                "availableToWithdraw",
                "totalAvailableBalance").max(BigDecimal.ZERO);
        var margin = decimal(accountJson, "totalInitialMargin", BigDecimal.ZERO).max(BigDecimal.ZERO);
        var unrealized = firstAvailableDecimal(usdt, accountJson, "unrealisedPnl", "totalPerpUPL");
        var snapshot = new AccountSnapshotFact(
                id("snapshot_", sourceSnapshotId),
                account.accountId(),
                sourceSnapshotId,
                new Money(equity, "USDT"),
                new Money(available, "USDT"),
                new Money(margin, "USDT"),
                new Money(unrealized, "USDT"),
                receivedAt,
                receivedAt);
        var balances = List.of(new BalanceFact(
                id("balance_", sourceSnapshotId),
                account.accountId(),
                sourceSnapshotId,
                "USDT",
                wallet,
                available,
                null,
                BalanceChangeReason.SNAPSHOT,
                receivedAt,
                receivedAt));
        var positions = mapBybitPositions(account.accountId(), positionsJson, receivedAt);
        var orders = mapBybitOrders(account.accountId(), ordersPage.rows(), receivedAt);
        var fills = mapBybitFills(account.accountId(), fillsPage.rows(), receivedAt);
        var pnl = mapBybitPnl(account.accountId(), pnlPage.rows(), receivedAt);
        var complete = ordersPage.complete() && fillsPage.complete() && pnlPage.complete();
        var warnings = complete
                ? List.<String>of()
                : List.of("Bybit history reached the configured page limit; watermark was not advanced");
        return new ExchangeAccountSyncBatch(
                snapshot,
                balances,
                positions,
                orders,
                fills,
                pnl,
                toExclusive,
                complete,
                warnings);
    }

    private Page gateHistory(
            String path,
            Credentials credentials,
            Instant fromInclusive,
            Instant toExclusive) {
        var rows = new ArrayList<JsonNode>();
        var offset = 0;
        while (rows.size() < PAGE_LIMIT) {
            var query = new LinkedHashMap<String, String>();
            query.put("from", Long.toString(fromInclusive.getEpochSecond()));
            query.put("to", Long.toString(toExclusive.getEpochSecond()));
            query.put("limit", "100");
            query.put("offset", Integer.toString(offset));
            var page = requireGate(gateRequest("GET", path, query, "", credentials));
            if (!page.isArray()) {
                throw new IllegalStateException("Gate history response is not an array");
            }
            page.forEach(rows::add);
            if (page.size() < 100) {
                return new Page(List.copyOf(rows), true);
            }
            offset += 100;
        }
        return new Page(List.copyOf(rows), false);
    }

    private Page bybitHistory(
            String path,
            Credentials credentials,
            Instant fromInclusive,
            Instant toExclusive) {
        var rows = new ArrayList<JsonNode>();
        String cursor = null;
        do {
            var query = new LinkedHashMap<String, String>();
            query.put("category", "linear");
            query.put("startTime", Long.toString(fromInclusive.toEpochMilli()));
            query.put("endTime", Long.toString(toExclusive.toEpochMilli()));
            query.put("limit", "100");
            if (cursor != null) {
                query.put("cursor", cursor);
            }
            var response = requireBybit(bybitRequest("GET", path, query, "", credentials));
            var result = response.path("result");
            var list = result.path("list");
            if (!list.isArray()) {
                throw new IllegalStateException("Bybit history response contains no result list");
            }
            list.forEach(rows::add);
            var next = result.path("nextPageCursor").asText("");
            cursor = next.isBlank() ? null : next;
        } while (cursor != null && rows.size() < PAGE_LIMIT);
        return new Page(List.copyOf(rows), cursor == null);
    }

    private static List<PositionSnapshotFact> mapGatePositions(
            ExchangeAccountId accountId,
            JsonNode rows,
            Instant receivedAt) {
        var result = new ArrayList<PositionSnapshotFact>();
        if (!rows.isArray()) {
            return List.of();
        }
        rows.forEach(row -> {
            var signed = decimal(row, "size", BigDecimal.ZERO);
            if (signed.signum() == 0) {
                return;
            }
            var sourceId = "gate-position:" + text(row, "contract", "UNKNOWN") + ':'
                    + text(row, "update_time", Long.toString(receivedAt.getEpochSecond()));
            result.add(new PositionSnapshotFact(
                    id("position_", sourceId),
                    accountId,
                    sourceId,
                    symbol(row, "contract"),
                    signed.signum() > 0 ? PositionSide.LONG : PositionSide.SHORT,
                    signed.abs(),
                    optionalPositive(row, "entry_price"),
                    optionalPositive(row, "mark_price"),
                    optionalPositive(row, "liq_price"),
                    positiveOrOne(row, "leverage"),
                    decimal(row, "unrealised_pnl", BigDecimal.ZERO),
                    decimal(row, "margin", BigDecimal.ZERO).abs(),
                    occurred(row, "update_time", false, receivedAt),
                    receivedAt));
        });
        return List.copyOf(result);
    }

    private static List<PositionSnapshotFact> mapBybitPositions(
            ExchangeAccountId accountId,
            JsonNode rows,
            Instant receivedAt) {
        var result = new ArrayList<PositionSnapshotFact>();
        if (!rows.isArray()) {
            return List.of();
        }
        rows.forEach(row -> {
            var size = decimal(row, "size", BigDecimal.ZERO).abs();
            if (size.signum() == 0) {
                return;
            }
            var sourceId = "bybit-position:" + text(row, "symbol", "UNKNOWN") + ':'
                    + text(row, "updatedTime", Long.toString(receivedAt.toEpochMilli()));
            var side = "Buy".equalsIgnoreCase(row.path("side").asText())
                    ? PositionSide.LONG
                    : PositionSide.SHORT;
            result.add(new PositionSnapshotFact(
                    id("position_", sourceId),
                    accountId,
                    sourceId,
                    symbol(row, "symbol"),
                    side,
                    size,
                    optionalPositive(row, "avgPrice"),
                    optionalPositive(row, "markPrice"),
                    optionalPositive(row, "liqPrice"),
                    positiveOrOne(row, "leverage"),
                    decimal(row, "unrealisedPnl", BigDecimal.ZERO),
                    firstDecimal(row, "positionIM", "positionBalance").abs(),
                    occurred(row, "updatedTime", true, receivedAt),
                    receivedAt));
        });
        return List.copyOf(result);
    }

    private static List<OrderFact> mapGateOrders(
            ExchangeAccountId accountId,
            List<JsonNode> rows,
            Instant receivedAt) {
        var result = new ArrayList<OrderFact>();
        rows.forEach(row -> {
            var exchangeId = requiredText(row, "id");
            var signed = decimal(row, "size", BigDecimal.ZERO);
            var quantity = signed.abs();
            if (quantity.signum() == 0) {
                return;
            }
            var left = decimal(row, "left", BigDecimal.ZERO).abs();
            result.add(new OrderFact(
                    id("orderfact_", "gate-order:" + exchangeId),
                    accountId,
                    "gate-order:" + exchangeId,
                    exchangeId,
                    nullableText(row, "text"),
                    symbol(row, "contract"),
                    signed.signum() >= 0 ? LedgerOrderSide.BUY : LedgerOrderSide.SELL,
                    "0".equals(row.path("price").asText())
                            ? ExchangeOrderType.MARKET
                            : ExchangeOrderType.LIMIT,
                    gateOrderStatus(row, quantity.subtract(left).max(BigDecimal.ZERO)),
                    quantity,
                    quantity.subtract(left).max(BigDecimal.ZERO),
                    optionalPositive(row, "price"),
                    optionalPositive(row, "fill_price"),
                    row.path("reduce_only").asBoolean(false),
                    occurred(row, "update_time", false, receivedAt),
                    receivedAt));
        });
        return List.copyOf(result);
    }

    private static List<OrderFact> mapBybitOrders(
            ExchangeAccountId accountId,
            List<JsonNode> rows,
            Instant receivedAt) {
        var result = new ArrayList<OrderFact>();
        rows.forEach(row -> {
            var exchangeId = requiredText(row, "orderId");
            var quantity = requiredPositive(row, "qty");
            result.add(new OrderFact(
                    id("orderfact_", "bybit-order:" + exchangeId),
                    accountId,
                    "bybit-order:" + exchangeId,
                    exchangeId,
                    nullableText(row, "orderLinkId"),
                    symbol(row, "symbol"),
                    side(row, "side"),
                    orderType(row.path("orderType").asText()),
                    bybitOrderStatus(row.path("orderStatus").asText()),
                    quantity,
                    decimal(row, "cumExecQty", BigDecimal.ZERO).abs(),
                    optionalPositive(row, "price"),
                    optionalPositive(row, "avgPrice"),
                    row.path("reduceOnly").asBoolean(false),
                    occurred(row, "updatedTime", true, receivedAt),
                    receivedAt));
        });
        return List.copyOf(result);
    }

    private static List<FillFact> mapGateFills(
            ExchangeAccountId accountId,
            List<JsonNode> rows,
            Instant receivedAt) {
        var result = new ArrayList<FillFact>();
        rows.forEach(row -> {
            var exchangeOrderId = requiredText(row, "order_id");
            var fillId = text(row, "trade_id", hash(row.toString()).substring(0, 24));
            var signed = requiredDecimal(row, "size");
            result.add(new FillFact(
                    id("fill_", "gate-fill:" + fillId),
                    accountId,
                    "gate-fill:" + fillId,
                    fillId,
                    exchangeOrderId,
                    nullableText(row, "text"),
                    symbol(row, "contract"),
                    signed.signum() >= 0 ? LedgerOrderSide.BUY : LedgerOrderSide.SELL,
                    signed.abs(),
                    requiredPositive(row, "price"),
                    decimal(row, "fee", BigDecimal.ZERO).abs(),
                    "USDT",
                    null,
                    occurred(row, "create_time", false, receivedAt),
                    receivedAt));
        });
        return List.copyOf(result);
    }

    private static List<FillFact> mapBybitFills(
            ExchangeAccountId accountId,
            List<JsonNode> rows,
            Instant receivedAt) {
        var result = new ArrayList<FillFact>();
        rows.forEach(row -> {
            var exchangeOrderId = requiredText(row, "orderId");
            var fillId = requiredText(row, "execId");
            result.add(new FillFact(
                    id("fill_", "bybit-fill:" + fillId),
                    accountId,
                    "bybit-fill:" + fillId,
                    fillId,
                    exchangeOrderId,
                    nullableText(row, "orderLinkId"),
                    symbol(row, "symbol"),
                    side(row, "side"),
                    requiredPositive(row, "execQty"),
                    requiredPositive(row, "execPrice"),
                    decimal(row, "execFee", BigDecimal.ZERO).abs(),
                    text(row, "feeCurrency", "USDT"),
                    null,
                    occurred(row, "execTime", true, receivedAt),
                    receivedAt));
        });
        return List.copyOf(result);
    }

    private static List<RealizedPnlFact> mapGatePnl(
            ExchangeAccountId accountId,
            List<JsonNode> rows,
            String currency,
            Instant receivedAt) {
        var result = new ArrayList<RealizedPnlFact>();
        rows.stream()
                .filter(row -> java.util.Set.of("pnl", "fee", "fund")
                        .contains(row.path("type").asText().toLowerCase(Locale.ROOT)))
                .filter(row -> row.hasNonNull("contract") && !row.path("contract").asText().isBlank())
                .forEach(row -> {
                    var nativeId = text(row, "id", hash(row.toString()).substring(0, 24));
                    var type = row.path("type").asText().toLowerCase(Locale.ROOT);
                    result.add(new RealizedPnlFact(
                            id("pnl_", "gate-pnl:" + nativeId),
                            accountId,
                            "gate-pnl:" + nativeId,
                            symbol(row, "contract"),
                            new Money(decimal(row, "change", BigDecimal.ZERO), currency),
                            switch (type) {
                                case "fee" -> PnlSourceType.FEE;
                                case "fund" -> PnlSourceType.FUNDING;
                                default -> PnlSourceType.TRADE;
                            },
                            null,
                            nullableText(row, "trade_id"),
                            occurred(row, "time", false, receivedAt),
                            receivedAt));
                });
        return List.copyOf(result);
    }

    private static List<RealizedPnlFact> mapBybitPnl(
            ExchangeAccountId accountId,
            List<JsonNode> rows,
            Instant receivedAt) {
        var result = new ArrayList<RealizedPnlFact>();
        rows.forEach(row -> {
            var exchangeOrderId = requiredText(row, "orderId");
            result.add(new RealizedPnlFact(
                    id("pnl_", "bybit-pnl:" + exchangeOrderId),
                    accountId,
                    "bybit-pnl:" + exchangeOrderId,
                    symbol(row, "symbol"),
                    new Money(decimal(row, "closedPnl", BigDecimal.ZERO), "USDT"),
                    PnlSourceType.TRADE,
                    exchangeOrderId,
                    null,
                    occurred(row, "updatedTime", true, receivedAt),
                    receivedAt));
        });
        return List.copyOf(result);
    }

    private ExchangeAccountHttpTransport.Response gateRequest(
            String method,
            String path,
            LinkedHashMap<String, String> query,
            String body,
            Credentials credentials) {
        return transport.send(OutboundRoute.EXCHANGE_GATE, () -> {
            var queryString = query(query);
            var timestamp = Long.toString(clock.instant().getEpochSecond());
            var signature = GateRequestSigner.sign(
                    credentials.secret(),
                    method,
                    GATE_BASE.getPath() + path,
                    queryString,
                    body,
                    timestamp);
            return request(GATE_BASE, path, queryString, method, body)
                    .header("KEY", credentials.key())
                    .header("Timestamp", timestamp)
                    .header("SIGN", signature)
                    .header("X-Gate-Exptime", Long.toString(clock.instant().plusSeconds(5).toEpochMilli()))
                    .build();
        });
    }

    private ExchangeAccountHttpTransport.Response bybitRequest(
            String method,
            String path,
            LinkedHashMap<String, String> query,
            String body,
            Credentials credentials) {
        return transport.send(OutboundRoute.EXCHANGE_BYBIT, () -> {
            var queryString = query(query);
            var payload = "GET".equals(method) ? queryString : body;
            var timestamp = Long.toString(clock.instant().toEpochMilli());
            var signature = BybitRequestSigner.sign(
                    credentials.secret(),
                    timestamp,
                    credentials.key(),
                    BYBIT_RECEIVE_WINDOW_MILLISECONDS,
                    payload);
            return request(BYBIT_BASE, path, queryString, method, body)
                    .header("X-BAPI-API-KEY", credentials.key())
                    .header("X-BAPI-TIMESTAMP", timestamp)
                    .header("X-BAPI-SIGN", signature)
                    .header("X-BAPI-SIGN-TYPE", "2")
                    .header("X-BAPI-RECV-WINDOW", Integer.toString(BYBIT_RECEIVE_WINDOW_MILLISECONDS))
                    .build();
        });
    }

    private static HttpRequest.Builder request(
            URI base,
            String path,
            String query,
            String method,
            String body) {
        var uri = URI.create(base + path + (query.isBlank() ? "" : "?" + query));
        var builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .header("User-Agent", "FinBot/2.0 account-sync");
        return "GET".equals(method)
                ? builder.GET()
                : builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    private Credentials credentials(ExchangeAccountConfiguration account) {
        return new Credentials(
                runtimeSecrets.resolve(
                                RuntimeSecretScope.EXCHANGE_ACCOUNT,
                                account.accountId().value(),
                                "API_KEY",
                                account.apiKeyEnvironmentVariable())
                        .orElseThrow(() -> new IllegalStateException("Exchange API key is not configured")),
                runtimeSecrets.resolve(
                                RuntimeSecretScope.EXCHANGE_ACCOUNT,
                                account.accountId().value(),
                                "API_SECRET",
                                account.apiSecretEnvironmentVariable())
                        .orElseThrow(() -> new IllegalStateException("Exchange API secret is not configured")));
    }

    private static void requirePaperEnvironment(ExchangeAccountConfiguration account) {
        if ((account.exchange() == io.omnnu.finbot.domain.catalog.ExchangeVenue.GATE
                && account.environment() != ExchangeEnvironment.TESTNET)
                || (account.exchange() == io.omnnu.finbot.domain.catalog.ExchangeVenue.BYBIT
                        && account.environment() != ExchangeEnvironment.DEMO)) {
            throw new IllegalStateException("Mainnet private account reads are not configured in this service");
        }
    }

    private static JsonNode requireGate(ExchangeAccountHttpTransport.Response response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Gate account API returned HTTP " + response.statusCode());
        }
        return response.json();
    }

    private static JsonNode requireBybit(ExchangeAccountHttpTransport.Response response) {
        if (!bybitSuccessful(response)) {
            throw new IllegalStateException("Bybit account API rejected the request");
        }
        return response.json();
    }

    private static boolean bybitSuccessful(ExchangeAccountHttpTransport.Response response) {
        return response.statusCode() >= 200
                && response.statusCode() < 300
                && response.json().path("retCode").asInt(-1) == 0;
    }

    private static JsonNode firstCoin(JsonNode values, String currency) {
        if (values.isArray()) {
            for (var value : values) {
                if (currency.equalsIgnoreCase(value.path("coin").asText())) {
                    return value;
                }
            }
        }
        throw new IllegalStateException("Bybit wallet response contains no " + currency + " balance");
    }

    private static ExchangeOrderStatus gateOrderStatus(JsonNode row, BigDecimal filled) {
        var status = row.path("status").asText().toLowerCase(Locale.ROOT);
        var finish = row.path("finish_as").asText().toLowerCase(Locale.ROOT);
        if ("open".equals(status)) {
            return filled.signum() > 0
                    ? ExchangeOrderStatus.PARTIALLY_FILLED
                    : ExchangeOrderStatus.SUBMITTED;
        }
        if ("filled".equals(finish)) {
            return ExchangeOrderStatus.FILLED;
        }
        if (java.util.Set.of("cancelled", "ioc", "stp", "reduce_only").contains(finish)) {
            return ExchangeOrderStatus.CANCELLED;
        }
        return ExchangeOrderStatus.UNKNOWN;
    }

    private static ExchangeOrderStatus bybitOrderStatus(String status) {
        return switch (status) {
            case "New", "Untriggered" -> ExchangeOrderStatus.SUBMITTED;
            case "PartiallyFilled", "PartiallyFilledCanceled" -> ExchangeOrderStatus.PARTIALLY_FILLED;
            case "Filled" -> ExchangeOrderStatus.FILLED;
            case "Cancelled", "Deactivated" -> ExchangeOrderStatus.CANCELLED;
            case "Rejected" -> ExchangeOrderStatus.REJECTED;
            default -> ExchangeOrderStatus.UNKNOWN;
        };
    }

    private static ExchangeOrderType orderType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "market" -> ExchangeOrderType.MARKET;
            case "limit" -> ExchangeOrderType.LIMIT;
            case "stop", "stopmarket" -> ExchangeOrderType.STOP;
            case "takeprofit", "takeprofitmarket" -> ExchangeOrderType.TAKE_PROFIT;
            default -> ExchangeOrderType.UNKNOWN;
        };
    }

    private static LedgerOrderSide side(JsonNode row, String field) {
        return "Buy".equalsIgnoreCase(row.path(field).asText())
                ? LedgerOrderSide.BUY
                : LedgerOrderSide.SELL;
    }

    private static InstrumentSymbol symbol(JsonNode row, String field) {
        return new InstrumentSymbol(requiredText(row, field).toUpperCase(Locale.ROOT));
    }

    private static Instant occurred(
            JsonNode row,
            String field,
            boolean milliseconds,
            Instant receivedAt) {
        var raw = decimal(row, field, null);
        if (raw == null || raw.signum() <= 0) {
            return receivedAt;
        }
        var instant = milliseconds
                ? Instant.ofEpochMilli(raw.longValue())
                : Instant.ofEpochSecond(raw.longValue());
        return instant.isAfter(receivedAt) ? receivedAt : instant;
    }

    private static BigDecimal firstAvailableDecimal(
            JsonNode primary,
            JsonNode secondary,
            String primaryField,
            String secondaryField) {
        var first = decimal(primary, primaryField, null);
        if (first != null) {
            return first;
        }
        var second = decimal(secondary, secondaryField, null);
        if (second == null) {
            throw new IllegalStateException("Exchange account response is missing a required balance");
        }
        return second;
    }

    private static BigDecimal firstDecimal(JsonNode row, String... fields) {
        for (var field : fields) {
            var value = decimal(row, field, null);
            if (value != null) {
                return value;
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal requiredDecimal(JsonNode row, String field) {
        var value = decimal(row, field, null);
        if (value == null) {
            throw new IllegalStateException("Exchange response is missing decimal field " + field);
        }
        return value;
    }

    private static BigDecimal requiredPositive(JsonNode row, String field) {
        var value = requiredDecimal(row, field).abs();
        if (value.signum() <= 0) {
            throw new IllegalStateException("Exchange response field must be positive: " + field);
        }
        return value;
    }

    private static BigDecimal positiveOrOne(JsonNode row, String field) {
        var value = decimal(row, field, BigDecimal.ONE);
        return value.signum() > 0 ? value : BigDecimal.ONE;
    }

    private static BigDecimal optionalPositive(JsonNode row, String field) {
        var value = decimal(row, field, null);
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static BigDecimal decimal(JsonNode row, String field, BigDecimal fallback) {
        var value = row.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String requiredText(JsonNode row, String field) {
        var value = row.path(field).asText("").strip();
        if (value.isEmpty()) {
            throw new IllegalStateException("Exchange response is missing text field " + field);
        }
        return value;
    }

    private static String nullableText(JsonNode row, String field) {
        var value = row.path(field).asText("").strip();
        return value.isEmpty() ? null : value;
    }

    private static String text(JsonNode row, String field, String fallback) {
        var value = nullableText(row, field);
        return value == null ? fallback : value;
    }

    private static String query(LinkedHashMap<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + '=' + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static LedgerFactId id(String prefix, String source) {
        return new LedgerFactId(prefix + hash(source).substring(0, 40));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Credentials(String key, String secret) {
    }

    private record Page(List<JsonNode> rows, boolean complete) {
    }
}
