package io.omnnu.finbot.application.quant.dto;

import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import java.math.BigDecimal;
import java.util.Objects;

public record TradeRiskPreviewCommand(
        String instrumentId,
        String accountId,
        ExchangeVenue exchange,
        ExchangeEnvironment environment,
        String symbol,
        DirectionalAction action,
        BigDecimal confidence,
        BigDecimal entryPrice,
        BigDecimal targetPrice,
        BigDecimal stopPrice,
        BigDecimal currentPrice,
        BigDecimal contractSize,
        BigDecimal quantityStep,
        BigDecimal minimumQuantity,
        BigDecimal venueMaximumLeverage,
        int openPositionCount,
        boolean executionEnabled) {
    public TradeRiskPreviewCommand {
        instrumentId = Objects.requireNonNull(instrumentId, "instrumentId").strip();
        accountId = accountId == null || accountId.isBlank()
                ? "account_preview_default"
                : accountId.strip();
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(environment, "environment");
        symbol = Objects.requireNonNull(symbol, "symbol").strip().toUpperCase(java.util.Locale.ROOT);
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(targetPrice, "targetPrice");
        Objects.requireNonNull(stopPrice, "stopPrice");
        Objects.requireNonNull(currentPrice, "currentPrice");
        Objects.requireNonNull(contractSize, "contractSize");
        Objects.requireNonNull(quantityStep, "quantityStep");
        Objects.requireNonNull(minimumQuantity, "minimumQuantity");
        Objects.requireNonNull(venueMaximumLeverage, "venueMaximumLeverage");
        if (openPositionCount < 0) {
            throw new IllegalArgumentException("openPositionCount must not be negative");
        }
    }
}
