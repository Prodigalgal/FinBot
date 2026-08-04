package io.omnnu.finbot.application.workflow.validation;

import io.omnnu.finbot.application.configuration.dto.AiModelProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderUsage;
import io.omnnu.finbot.application.configuration.dto.SystemSetting;
import io.omnnu.finbot.application.configuration.port.out.ConfigurationRepository;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import io.omnnu.finbot.domain.workflow.WorkflowActivationMode;
import io.omnnu.finbot.domain.workflow.WorkflowCanvasPosition;
import io.omnnu.finbot.domain.workflow.WorkflowContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeContextMode;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeId;
import io.omnnu.finbot.domain.workflow.WorkflowFailurePolicy;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowOutputContract;
import io.omnnu.finbot.domain.workflow.WorkflowRetryPolicy;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowAiBindingValidatorTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String PROVIDER_ID = "provider_test_openai";
    private static final String MODEL_NAME = "model-test";

    @Test
    void acceptsEnabledProviderModelAndSupportedReasoning() {
        var validator = validator(provider(true), model(true, ReasoningEffort.MAX));

        assertDoesNotThrow(() -> validator.validate(workflow(ReasoningEffort.XHIGH, true)));
    }

    @Test
    void rejectsMissingOrDisabledProvider() {
        var missing = new WorkflowAiBindingValidator(new FakeConfigurationRepository(List.of(), List.of(model(true, ReasoningEffort.MAX))));
        var disabled = validator(provider(false), model(true, ReasoningEffort.MAX));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> missing.validate(workflow(ReasoningEffort.HIGH, true))).getMessage().contains("does not exist"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> disabled.validate(workflow(ReasoningEffort.HIGH, true))).getMessage().contains("is disabled"));
    }

    @Test
    void rejectsMissingOrDisabledModel() {
        var missing = new WorkflowAiBindingValidator(new FakeConfigurationRepository(List.of(provider(true)), List.of()));
        var disabled = validator(provider(true), model(false, ReasoningEffort.MAX));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> missing.validate(workflow(ReasoningEffort.HIGH, true))).getMessage().contains("does not exist"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> disabled.validate(workflow(ReasoningEffort.HIGH, true))).getMessage().contains("is disabled"));
    }

    @Test
    void rejectsReasoningAboveConfiguredMaximum() {
        var validator = validator(provider(true), model(true, ReasoningEffort.HIGH));

        var error = assertThrows(IllegalArgumentException.class,
                () -> validator.validate(workflow(ReasoningEffort.MAX, true)));

        assertTrue(error.getMessage().contains("exceeds model capability HIGH"));
    }

    @Test
    void ignoresDisabledWorkflowNodes() {
        var validator = new WorkflowAiBindingValidator(new FakeConfigurationRepository(List.of(), List.of()));

        assertDoesNotThrow(() -> validator.validate(workflow(ReasoningEffort.MAX, false)));
    }

    private static WorkflowAiBindingValidator validator(AiProviderProfile provider, AiModelProfile model) {
        return new WorkflowAiBindingValidator(new FakeConfigurationRepository(List.of(provider), List.of(model)));
    }

    private static AiProviderProfile provider(boolean enabled) {
        return new AiProviderProfile(
                PROVIDER_ID,
                "OpenAI-compatible test provider",
                AiProtocol.CHAT,
                ReasoningParameterStyle.FLAT,
                "https://provider.example/v1",
                null,
                "FINBOT_AI_PROVIDER_KEYS_JSON",
                enabled,
                10,
                1800,
                5,
                1800,
                0,
                NOW);
    }

    private static AiModelProfile model(boolean enabled, ReasoningEffort maximumReasoning) {
        return new AiModelProfile(
                "modelprofile_test_openai",
                PROVIDER_ID,
                MODEL_NAME,
                ReasoningEffort.PROVIDER_DEFAULT,
                maximumReasoning,
                TokenLimitParameterStyle.PROTOCOL_DEFAULT,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                enabled,
                0,
                NOW);
    }

    private static WorkflowDefinitionVersion workflow(ReasoningEffort effort, boolean aiNodeEnabled) {
        var input = node("node_input000", WorkflowNodeType.INPUT, null, true);
        var cleaner = node("node_cleaner0", WorkflowNodeType.AI_CLEANER, effort, aiNodeEnabled);
        var output = node("node_output00", WorkflowNodeType.OUTPUT, null, true);
        return new WorkflowDefinitionVersion(
                new WorkflowVersionId("workflowversion_binding_test"),
                new WorkflowDefinitionId("workflow_binding_test"),
                1,
                WorkflowVersionStatus.DRAFT,
                1,
                20,
                Duration.ofMinutes(10),
                100_000,
                BigDecimal.TEN,
                WorkflowFailurePolicy.STOP,
                "a".repeat(64),
                null,
                NOW,
                "test",
                List.of(input, cleaner, output),
                List.of(edge("edge_input_clean", input, cleaner), edge("edge_clean_out", cleaner, output)));
    }

    private static WorkflowNodeDefinition node(
            String id,
            WorkflowNodeType type,
            ReasoningEffort effort,
            boolean enabled) {
        var llm = type.llmBacked();
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                type,
                id,
                llm ? "cleaner" : null,
                null,
                llm ? new AiModelBinding(new AiProviderProfileId(PROVIDER_ID), MODEL_NAME, effort) : null,
                null,
                llm ? "Extract facts." : null,
                llm ? "Process the input." : null,
                llm ? WorkflowOutputContract.RESEARCH_FINDINGS : null,
                llm ? WorkflowContextMode.UPSTREAM : WorkflowContextMode.NONE,
                0,
                8,
                128,
                60,
                new WorkflowRetryPolicy(1, Duration.ZERO),
                type == WorkflowNodeType.INPUT ? "research_input"
                        : type == WorkflowNodeType.OUTPUT ? "research_output" : null,
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                enabled);
    }

    private static WorkflowEdgeDefinition edge(
            String id,
            WorkflowNodeDefinition source,
            WorkflowNodeDefinition target) {
        return new WorkflowEdgeDefinition(
                new WorkflowEdgeId(id),
                source.nodeId(),
                target.nodeId(),
                WorkflowActivationMode.ALL,
                WorkflowEdgeContextMode.INCLUDE,
                null,
                false,
                null);
    }

    private static final class FakeConfigurationRepository implements ConfigurationRepository {
        private final List<AiProviderProfile> providers;
        private final List<AiModelProfile> models;

        private FakeConfigurationRepository(List<AiProviderProfile> providers, List<AiModelProfile> models) {
            this.providers = List.copyOf(providers);
            this.models = List.copyOf(models);
        }

        @Override public List<SystemSetting> listSettings() { return List.of(); }
        @Override public List<AiProviderProfile> listProviders() { return providers; }
        @Override public List<AiModelProfile> listModels() { return models; }
        @Override public Map<String, AiProviderUsage> providerUsages() { return Map.of(); }
        @Override public Optional<AiProviderProfile> createProvider(AiProviderProfile provider, Instant createdAt) { return Optional.empty(); }
        @Override public Optional<AiModelProfile> createModel(AiModelProfile model, Instant createdAt) { return Optional.empty(); }
        @Override public Optional<SystemSetting> updateSetting(String key, String value, long expectedVersion, Instant updatedAt) { return Optional.empty(); }
        @Override public Optional<AiProviderProfile> updateProvider(AiProviderProfile profile, long expectedVersion, Instant updatedAt) { return Optional.empty(); }
        @Override public boolean archiveProvider(String profileId, long expectedVersion, Instant archivedAt) { return false; }
        @Override public Optional<AiModelProfile> updateModel(AiModelProfile profile, long expectedVersion, Instant updatedAt) { return Optional.empty(); }
    }
}
