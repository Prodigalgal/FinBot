package io.omnnu.finbot.api.trading.controller;

import io.omnnu.finbot.api.trading.dto.ActivateRiskPolicyRequest;
import io.omnnu.finbot.api.trading.dto.UpdateExecutionAiStageRequest;

import io.omnnu.finbot.application.trading.port.out.TradeAutomationConfigurationRepository;
import io.omnnu.finbot.application.trading.dto.TradeAutomationConfigurationSnapshot;
import io.omnnu.finbot.application.trading.dto.TradeExecutionAiStage;
import io.omnnu.finbot.application.trading.dto.TradeExecutionAiStageConfig;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import java.time.Duration;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/trading/automation-configuration")
public final class TradeAutomationConfigurationController {
    private final TradeAutomationConfigurationRepository repository;

    public TradeAutomationConfigurationController(TradeAutomationConfigurationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @GetMapping
    public TradeAutomationConfigurationSnapshot snapshot() {
        return repository.snapshot();
    }

    @PutMapping("/ai-stages/{stage}")
    public TradeExecutionAiStageConfig updateStage(
            @PathVariable TradeExecutionAiStage stage,
            @Valid @RequestBody UpdateExecutionAiStageRequest request) {
        var config = new TradeExecutionAiStageConfig(
                stage,
                request.primaryAiBinding().toDomain(),
                request.fallbackAiBinding() == null ? null : request.fallbackAiBinding().toDomain(),
                request.systemPrompt(),
                request.userPromptTemplate(),
                request.maximumOutputTokens(),
                request.timeoutSeconds(),
                new WorkflowRetryPolicy(
                        request.retryMaximumAttempts(),
                        Duration.ofSeconds(request.retryBackoffSeconds())),
                request.enabled(),
                request.expectedVersion());
        return repository.updateAiStage(config, request.expectedVersion());
    }

    @PostMapping("/risk-policies")
    public RiskPolicy activatePolicy(@Valid @RequestBody ActivateRiskPolicyRequest request) {
        return repository.activateRiskPolicy(new RiskPolicy(
                request.policyVersion(),
                request.testEnvironmentOnly(),
                request.minimumConfidence(),
                request.riskBudgetUsdt(),
                request.maximumNotionalUsdt(),
                request.preferredLeverage(),
                request.maximumLeverage(),
                request.maximumOpenPositions(),
                request.maximumStopDistance(),
                request.takerFeeRate(),
                request.slippageRate(),
                request.liquidationBufferRate()));
    }
}
