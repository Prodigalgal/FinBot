package io.omnnu.finbot.infrastructure.trading;

import io.omnnu.finbot.application.trading.TradeAutomationConfigurationConflictException;
import io.omnnu.finbot.application.trading.TradeAutomationConfigurationRepository;
import io.omnnu.finbot.application.trading.TradeAutomationConfigurationSnapshot;
import io.omnnu.finbot.application.trading.TradeExecutionAiStage;
import io.omnnu.finbot.application.trading.TradeExecutionAiStageConfig;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.risk.RiskPolicy;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcTradeAutomationConfigurationRepository
        implements TradeAutomationConfigurationRepository {
    private final JdbcClient jdbcClient;

    public JdbcTradeAutomationConfigurationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public TradeAutomationConfigurationSnapshot snapshot() {
        var stages = jdbcClient.sql("""
                select stage, provider_profile_id, model_name, reasoning_effort,
                       system_prompt, user_prompt_template, maximum_output_tokens,
                       timeout_seconds, enabled, version
                from trade_execution_ai_stage
                order by case stage when 'DRAFT' then 1 else 2 end
                """)
                .query((resultSet, rowNumber) -> new TradeExecutionAiStageConfig(
                        TradeExecutionAiStage.valueOf(resultSet.getString("stage")),
                        new AiProviderProfileId(resultSet.getString("provider_profile_id")),
                        resultSet.getString("model_name"),
                        ReasoningEffort.valueOf(resultSet.getString("reasoning_effort")),
                        resultSet.getString("system_prompt"),
                        resultSet.getString("user_prompt_template"),
                        resultSet.getInt("maximum_output_tokens"),
                        resultSet.getInt("timeout_seconds"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getLong("version")))
                .list();
        var policy = jdbcClient.sql("""
                select policy_version, test_environment_only, minimum_confidence,
                       risk_budget_usdt, maximum_notional_usdt, maximum_leverage,
                       maximum_open_positions, maximum_stop_distance, taker_fee_rate,
                       slippage_rate, liquidation_buffer_rate
                from risk_policy where active = true
                """)
                .query((resultSet, rowNumber) -> new RiskPolicy(
                        resultSet.getString("policy_version"),
                        resultSet.getBoolean("test_environment_only"),
                        resultSet.getBigDecimal("minimum_confidence"),
                        resultSet.getBigDecimal("risk_budget_usdt"),
                        resultSet.getBigDecimal("maximum_notional_usdt"),
                        resultSet.getBigDecimal("maximum_leverage"),
                        resultSet.getInt("maximum_open_positions"),
                        resultSet.getBigDecimal("maximum_stop_distance"),
                        resultSet.getBigDecimal("taker_fee_rate"),
                        resultSet.getBigDecimal("slippage_rate"),
                        resultSet.getBigDecimal("liquidation_buffer_rate")))
                .single();
        return new TradeAutomationConfigurationSnapshot(stages, policy);
    }

    @Override
    @Transactional
    public TradeExecutionAiStageConfig updateAiStage(
            TradeExecutionAiStageConfig config,
            long expectedVersion) {
        var changed = jdbcClient.sql("""
                update trade_execution_ai_stage
                set provider_profile_id = :providerProfileId,
                    model_name = :modelName,
                    reasoning_effort = :reasoningEffort,
                    system_prompt = :systemPrompt,
                    user_prompt_template = :userPromptTemplate,
                    maximum_output_tokens = :maximumOutputTokens,
                    timeout_seconds = :timeoutSeconds,
                    enabled = :enabled,
                    version = version + 1,
                    updated_at = current_timestamp
                where stage = :stage and version = :expectedVersion
                """)
                .param("stage", config.stage().name())
                .param("providerProfileId", config.providerProfileId().value())
                .param("modelName", config.modelName())
                .param("reasoningEffort", config.reasoningEffort().name())
                .param("systemPrompt", config.systemPrompt())
                .param("userPromptTemplate", config.userPromptTemplate())
                .param("maximumOutputTokens", config.maximumOutputTokens())
                .param("timeoutSeconds", config.timeoutSeconds())
                .param("enabled", config.enabled())
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new TradeAutomationConfigurationConflictException(
                    "Execution AI stage changed since it was loaded");
        }
        return jdbcClient.sql("select version from trade_execution_ai_stage where stage = :stage")
                .param("stage", config.stage().name())
                .query(Long.class)
                .optional()
                .map(version -> stageConfig(config.stage(), version))
                .orElseThrow(() -> new TradeAutomationConfigurationConflictException(
                        "Execution AI stage no longer exists"));
    }

    @Override
    @Transactional
    public RiskPolicy activateRiskPolicy(RiskPolicy policy) {
        jdbcClient.sql("update risk_policy set active = false where active = true").update();
        var inserted = jdbcClient.sql("""
                insert into risk_policy (
                  policy_version, active, test_environment_only, minimum_confidence,
                  risk_budget_usdt, maximum_notional_usdt, maximum_leverage,
                  maximum_open_positions, maximum_stop_distance, taker_fee_rate,
                  slippage_rate, liquidation_buffer_rate
                ) values (
                  :version, true, :testEnvironmentOnly, :minimumConfidence,
                  :riskBudgetUsdt, :maximumNotionalUsdt, :maximumLeverage,
                  :maximumOpenPositions, :maximumStopDistance, :takerFeeRate,
                  :slippageRate, :liquidationBufferRate
                ) on conflict (policy_version) do nothing
                """)
                .param("version", policy.version())
                .param("testEnvironmentOnly", policy.testEnvironmentOnly())
                .param("minimumConfidence", policy.minimumConfidence())
                .param("riskBudgetUsdt", policy.riskBudgetUsdt())
                .param("maximumNotionalUsdt", policy.maximumNotionalUsdt())
                .param("maximumLeverage", policy.maximumLeverage())
                .param("maximumOpenPositions", policy.maximumOpenPositions())
                .param("maximumStopDistance", policy.maximumStopDistance())
                .param("takerFeeRate", policy.takerFeeRate())
                .param("slippageRate", policy.slippageRate())
                .param("liquidationBufferRate", policy.liquidationBufferRate())
                .update();
        if (inserted != 1) {
            throw new TradeAutomationConfigurationConflictException(
                    "Risk policy version already exists; use a new immutable version");
        }
        return policy;
    }

    private TradeExecutionAiStageConfig stageConfig(TradeExecutionAiStage stage, long version) {
        return jdbcClient.sql("""
                select provider_profile_id, model_name, reasoning_effort, system_prompt,
                       user_prompt_template, maximum_output_tokens, timeout_seconds, enabled
                from trade_execution_ai_stage where stage = :stage
                """)
                .param("stage", stage.name())
                .query((resultSet, rowNumber) -> new TradeExecutionAiStageConfig(
                        stage,
                        new AiProviderProfileId(resultSet.getString("provider_profile_id")),
                        resultSet.getString("model_name"),
                        ReasoningEffort.valueOf(resultSet.getString("reasoning_effort")),
                        resultSet.getString("system_prompt"),
                        resultSet.getString("user_prompt_template"),
                        resultSet.getInt("maximum_output_tokens"),
                        resultSet.getInt("timeout_seconds"),
                        resultSet.getBoolean("enabled"),
                        version))
                .single();
    }
}
