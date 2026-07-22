package io.omnnu.finbot.api.trading.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ActivateRiskPolicyRequest(
        @NotBlank @Size(max = 80) String policyVersion,
        boolean testEnvironmentOnly,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal minimumConfidence,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal riskBudgetUsdt,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maximumNotionalUsdt,
        @NotNull @DecimalMin("1") BigDecimal preferredLeverage,
        @NotNull @DecimalMin("1") BigDecimal maximumLeverage,
        @Min(1) @Max(100) int maximumOpenPositions,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = "1", inclusive = false)
        BigDecimal maximumStopDistance,
        @NotNull @DecimalMin("0") @DecimalMax(value = "0.1", inclusive = false) BigDecimal takerFeeRate,
        @NotNull @DecimalMin("0") @DecimalMax(value = "0.1", inclusive = false) BigDecimal slippageRate,
        @NotNull @DecimalMin("0") @DecimalMax(value = "0.1", inclusive = false)
        BigDecimal liquidationBufferRate) {
}
