package io.omnnu.finbot.api.analysis.dto;

import io.omnnu.finbot.application.quant.dto.TradeRiskPreviewCommand;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.ledger.ExchangeEnvironment;
import io.omnnu.finbot.domain.trading.DirectionalAction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TradeRiskPreviewRequest(
        @NotBlank @Size(max = 80) String instrumentId,
        @Size(max = 80) String accountId,
        @NotNull ExchangeVenue exchange,
        @NotNull ExchangeEnvironment environment,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{2,48}$") String symbol,
        @NotNull DirectionalAction action,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal confidence,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal entryPrice,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal targetPrice,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal stopPrice,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal currentPrice,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal contractSize,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantityStep,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal minimumQuantity,
        @NotNull @DecimalMin("1") BigDecimal venueMaximumLeverage,
        @Min(0) @Max(10000) int openPositionCount,
        boolean executionEnabled) {
    public TradeRiskPreviewCommand toCommand() {
        return new TradeRiskPreviewCommand(
                instrumentId,
                accountId,
                exchange,
                environment,
                symbol,
                action,
                confidence,
                entryPrice,
                targetPrice,
                stopPrice,
                currentPrice,
                contractSize,
                quantityStep,
                minimumQuantity,
                venueMaximumLeverage,
                openPositionCount,
                executionEnabled);
    }
}
