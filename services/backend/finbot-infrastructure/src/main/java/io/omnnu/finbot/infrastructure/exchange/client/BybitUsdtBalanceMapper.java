package io.omnnu.finbot.infrastructure.exchange.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;

final class BybitUsdtBalanceMapper {
    private static final String ISOLATED_MARGIN = "ISOLATED_MARGIN";

    private BybitUsdtBalanceMapper() {
    }

    static Balance map(JsonNode account, JsonNode usdt, String marginMode) {
        var unified = "UNIFIED".equals(account.path("accountType").asText());
        var wallet = unified ? required(usdt, "walletBalance")
                : legacyValue(usdt, account, "walletBalance", "totalWalletBalance");
        if (!unified) {
            return new Balance(
                    legacyValue(usdt, account, "equity", "totalEquity").max(BigDecimal.ZERO),
                    wallet.max(BigDecimal.ZERO),
                    legacyValue(usdt, account, "availableToWithdraw", "totalAvailableBalance").max(BigDecimal.ZERO),
                    account.path("totalInitialMargin").asText().isBlank()
                            ? BigDecimal.ZERO : nonNegative(account, "totalInitialMargin"),
                    legacyValue(usdt, account, "unrealisedPnl", "totalPerpUPL"));
        }
        if (ISOLATED_MARGIN.equals(marginMode)) {
            var margin = nonNegative(usdt, "totalPositionIM").add(nonNegative(usdt, "totalOrderIM"));
            // Isolated availability uses coin margins; subtract borrowing from the new gross wallet balance.
            var available = wallet.subtract(nonNegative(usdt, "spotBorrow"))
                    .subtract(margin)
                    .subtract(nonNegative(usdt, "locked"))
                    .subtract(nonNegative(usdt, "bonus"))
                    .max(BigDecimal.ZERO);
            return new Balance(
                    required(usdt, "equity").max(BigDecimal.ZERO),
                    wallet.max(BigDecimal.ZERO),
                    available,
                    margin,
                    required(usdt, "unrealisedPnl"));
        }
        if (!"REGULAR_MARGIN".equals(marginMode) && !"PORTFOLIO_MARGIN".equals(marginMode)) {
            throw new IllegalStateException("Bybit UNIFIED account has an unsupported margin mode");
        }
        // Account-wide values are USD; preserve the ledger's USDT unit using the reported coin valuation.
        var equity = required(usdt, "equity");
        var usdValue = required(usdt, "usdValue");
        if (equity.signum() <= 0 || usdValue.signum() <= 0) {
            if (required(account, "totalEquity").signum() == 0
                    && required(account, "totalAvailableBalance").signum() == 0
                    && required(account, "totalInitialMargin").signum() == 0
                    && required(account, "totalPerpUPL").signum() == 0) {
                return new Balance(BigDecimal.ZERO, wallet.max(BigDecimal.ZERO), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO);
            }
            throw new IllegalStateException("Bybit USDT valuation is missing for account-wide USD balances");
        }
        var usdPerUsdt = usdValue.divide(equity, MathContext.DECIMAL128);
        return new Balance(
                convert(required(account, "totalEquity"), usdPerUsdt).max(BigDecimal.ZERO),
                wallet.max(BigDecimal.ZERO),
                convert(required(account, "totalAvailableBalance"), usdPerUsdt).max(BigDecimal.ZERO),
                convert(nonNegative(account, "totalInitialMargin"), usdPerUsdt),
                convert(required(account, "totalPerpUPL"), usdPerUsdt));
    }

    private static BigDecimal convert(BigDecimal dollars, BigDecimal usdPerUsdt) {
        return dollars.divide(usdPerUsdt, MathContext.DECIMAL128);
    }

    private static BigDecimal legacyValue(JsonNode coin, JsonNode account, String coinField, String accountField) {
        return coin.path(coinField).asText().isBlank() ? required(account, accountField) : required(coin, coinField);
    }

    private static BigDecimal nonNegative(JsonNode row, String field) {
        var value = required(row, field);
        if (value.signum() < 0) {
            throw new IllegalStateException("Bybit wallet field must be nonnegative: " + field);
        }
        return value;
    }

    private static BigDecimal required(JsonNode row, String field) {
        var raw = row.path(field);
        if (raw.isMissingNode() || raw.isNull() || raw.asText().isBlank()) {
            throw new IllegalStateException("Bybit wallet response is missing decimal field " + field);
        }
        try {
            return new BigDecimal(raw.asText());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Bybit wallet response has invalid decimal field " + field, exception);
        }
    }

    record Balance(BigDecimal equity, BigDecimal wallet, BigDecimal available, BigDecimal margin,
                   BigDecimal unrealizedPnl) {
    }
}
