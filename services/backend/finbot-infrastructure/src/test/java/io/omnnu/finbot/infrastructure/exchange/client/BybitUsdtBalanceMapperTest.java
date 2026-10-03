package io.omnnu.finbot.infrastructure.exchange.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BybitUsdtBalanceMapperTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsIsolatedMarginWithoutDeprecatedOrAccountWideAvailability() {
        var account = account().put("totalAvailableBalance", "").put("totalInitialMargin", "");
        var usdt = usdt().put("availableToWithdraw", "");

        var result = BybitUsdtBalanceMapper.map(account, usdt, "ISOLATED_MARGIN");

        assertDecimal("970", result.available());
        assertDecimal("25", result.margin());
        assertDecimal("1005", result.equity());
        assertDecimal("1000", result.wallet());
        assertDecimal("5", result.unrealizedPnl());
    }

    @Test
    void subtractsBorrowedAssetsAndDoesNotMakeNegativeAvailabilitySpendable() {
        var usdt = usdt().put("spotBorrow", "980");
        var result = BybitUsdtBalanceMapper.map(account(), usdt, "ISOLATED_MARGIN");
        assertDecimal("0", result.available());
    }

    @Test
    void keepsSignedUnrealizedLossAndZeroFunds() {
        var usdt = usdt().put("walletBalance", "0").put("equity", "-10").put("unrealisedPnl", "-10");
        var result = BybitUsdtBalanceMapper.map(account(), usdt, "ISOLATED_MARGIN");
        assertDecimal("0", result.equity());
        assertDecimal("0", result.available());
        assertDecimal("-10", result.unrealizedPnl());
    }

    @Test
    void usesUnifiedAccountAvailabilityInsteadOfDeprecatedWithdrawAmount() {
        var usdt = usdt().put("availableToWithdraw", "0");
        var result = BybitUsdtBalanceMapper.map(account(), usdt, "REGULAR_MARGIN");
        assertDecimal("850", result.available());
        assertDecimal("150", result.margin());
        assertDecimal("1300", result.equity());
    }

    @Test
    void convertsAccountWideUsdValuesUsingTheReportedUsdtValuation() {
        var usdt = usdt().put("usdValue", "100.5");
        var result = BybitUsdtBalanceMapper.map(account(), usdt, "PORTFOLIO_MARGIN");
        assertDecimal("8500", result.available());
        assertDecimal("1500", result.margin());
        assertDecimal("13000", result.equity());
        assertDecimal("50", result.unrealizedPnl());
    }

    @Test
    void acceptsACompletelyEmptyUnifiedAccount() {
        var account = account().put("totalEquity", "0").put("totalAvailableBalance", "0")
                .put("totalInitialMargin", "0").put("totalPerpUPL", "0");
        var usdt = usdt().put("walletBalance", "0").put("equity", "0").put("usdValue", "0");
        var result = BybitUsdtBalanceMapper.map(account, usdt, "REGULAR_MARGIN");
        assertDecimal("0", result.available());
        assertDecimal("0", result.equity());
    }

    @Test
    void rejectsMissingUsdtValuationWhenOtherCollateralHasValue() {
        var usdt = usdt().put("equity", "0").put("usdValue", "0");
        assertThrows(IllegalStateException.class,
                () -> BybitUsdtBalanceMapper.map(account(), usdt, "REGULAR_MARGIN"));
    }

    @Test
    void rejectsMissingInvalidAndNegativeMarginInputs() {
        for (var value : new String[]{"", "not-a-number", "-1"}) {
            var usdt = usdt().put("totalPositionIM", value);
            assertThrows(IllegalStateException.class,
                    () -> BybitUsdtBalanceMapper.map(account(), usdt, "ISOLATED_MARGIN"));
        }
        var missing = usdt();
        missing.remove("totalOrderIM");
        assertThrows(IllegalStateException.class,
                () -> BybitUsdtBalanceMapper.map(account(), missing, "ISOLATED_MARGIN"));
    }

    @Test
    void rejectsUnknownModesAndMissingCrossMarginAvailability() {
        assertThrows(IllegalStateException.class,
                () -> BybitUsdtBalanceMapper.map(account(), usdt(), ""));
        var missing = account().put("totalAvailableBalance", "");
        assertThrows(IllegalStateException.class,
                () -> BybitUsdtBalanceMapper.map(missing, usdt(), "REGULAR_MARGIN"));
    }

    @Test
    void retainsContractAccountAvailabilityMapping() {
        var account = account().put("accountType", "CONTRACT");
        var usdt = usdt().put("availableToWithdraw", "900");
        var result = BybitUsdtBalanceMapper.map(account, usdt, "");
        assertDecimal("900", result.available());
        assertDecimal("150", result.margin());
        assertDecimal("1005", result.equity());
    }

    @Test
    void retainsLegacyAccountFieldFallbacks() {
        var account = account().put("accountType", "CONTRACT").put("totalWalletBalance", "1200");
        var usdt = usdt();
        usdt.remove(java.util.List.of("equity", "walletBalance", "availableToWithdraw", "unrealisedPnl"));
        var result = BybitUsdtBalanceMapper.map(account, usdt, "");
        assertDecimal("1200", result.wallet());
        assertDecimal("850", result.available());
        assertDecimal("1300", result.equity());
        assertDecimal("5", result.unrealizedPnl());
    }

    private ObjectNode account() {
        return mapper.createObjectNode().put("accountType", "UNIFIED")
                .put("totalEquity", "1300").put("totalAvailableBalance", "850")
                .put("totalInitialMargin", "150").put("totalPerpUPL", "5");
    }

    private ObjectNode usdt() {
        return mapper.createObjectNode().put("walletBalance", "1000").put("equity", "1005")
                .put("usdValue", "1005").put("totalPositionIM", "20").put("totalOrderIM", "5")
                .put("locked", "3").put("bonus", "2").put("spotBorrow", "0").put("unrealisedPnl", "5");
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
