package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.configuration.dto.AiModelProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderUsage;
import io.omnnu.finbot.application.configuration.dto.SystemSetting;
import io.omnnu.finbot.application.configuration.port.out.ConfigurationRepository;
import io.omnnu.finbot.application.workflow.dto.SaveAgentRoleCommand;
import io.omnnu.finbot.application.workflow.dto.WorkflowDefinitionSummary;
import io.omnnu.finbot.application.workflow.port.out.WorkflowManagementRepository;
import io.omnnu.finbot.application.workflow.validation.WorkflowAiBindingValidator;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.domain.configuration.TokenLimitParameterStyle;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplate;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final WorkflowDefinitionId DEFINITION_ID = new WorkflowDefinitionId("workflow_binding_gate");
    private static final WorkflowVersionId VERSION_ID = new WorkflowVersionId("workflowversion_binding_gate");
    private static final AgentRoleTemplateId ROLE_ID = new AgentRoleTemplateId("role_binding_gate");
    private static final String PROVIDER_ID = "provider_role_test";
    private static final String MODEL_NAME = "model-role-test";

    @Test
    void publishDoesNotReachRepositoryWhenAiBindingIsInvalid() {
        var repository = new RecordingWorkflowRepository(workflow());
        var service = service(repository);

        assertThrows(IllegalArgumentException.class, () -> service.publish(VERSION_ID));

        assertEquals(0, repository.publishCalls);
    }

    @Test
    void rollbackDoesNotCreateDraftWhenAiBindingIsInvalid() {
        var repository = new RecordingWorkflowRepository(workflow());
        var service = service(repository);

        assertThrows(IllegalArgumentException.class, () -> service.rollback(DEFINITION_ID, VERSION_ID));

        assertEquals(0, repository.saveDraftCalls);
        assertEquals(0, repository.publishCalls);
    }

    @Test
    void activationDoesNotChangeStateWhenPublishedBindingIsInvalid() {
        var repository = new RecordingWorkflowRepository(workflow());
        var service = service(repository);

        assertThrows(IllegalArgumentException.class, () -> service.setActive(DEFINITION_ID, true));

        assertEquals(0, repository.setActiveCalls);
    }

    @Test
    void createAndUpdateRoleRejectInvalidDefaultBindingsBeforePersistence() {
        var invalidConfigurations = List.of(
                new InvalidRoleConfiguration(
                        "missing provider",
                        new EmptyConfigurationRepository(
                                List.of(),
                                List.of(model(PROVIDER_ID, true, ReasoningEffort.MAX))),
                        ReasoningEffort.HIGH),
                new InvalidRoleConfiguration(
                        "disabled provider",
                        new EmptyConfigurationRepository(
                                List.of(provider(PROVIDER_ID, false)),
                                List.of(model(PROVIDER_ID, true, ReasoningEffort.MAX))),
                        ReasoningEffort.HIGH),
                new InvalidRoleConfiguration(
                        "missing model",
                        new EmptyConfigurationRepository(
                                List.of(provider(PROVIDER_ID, true)),
                                List.of()),
                        ReasoningEffort.HIGH),
                new InvalidRoleConfiguration(
                        "disabled model",
                        new EmptyConfigurationRepository(
                                List.of(provider(PROVIDER_ID, true)),
                                List.of(model(PROVIDER_ID, false, ReasoningEffort.MAX))),
                        ReasoningEffort.HIGH),
                new InvalidRoleConfiguration(
                        "model belongs to another provider",
                        new EmptyConfigurationRepository(
                                List.of(provider(PROVIDER_ID, true), provider("provider_role_other", true)),
                                List.of(model("provider_role_other", true, ReasoningEffort.MAX))),
                        ReasoningEffort.HIGH),
                new InvalidRoleConfiguration(
                        "reasoning exceeds model maximum",
                        new EmptyConfigurationRepository(
                                List.of(provider(PROVIDER_ID, true)),
                                List.of(model(PROVIDER_ID, true, ReasoningEffort.HIGH))),
                        ReasoningEffort.MAX));

        for (var invalid : invalidConfigurations) {
            var repository = new RecordingWorkflowRepository(workflow(), existingRole());
            var service = service(repository, invalid.configuration());

            var createError = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.saveRole(roleCommand(null, invalid.requestedEffort(), null)),
                    invalid.name());
            var updateError = assertThrows(
                    IllegalArgumentException.class,
                    () -> service.saveRole(roleCommand(ROLE_ID, invalid.requestedEffort(), 0L)),
                    invalid.name());

            assertTrue(createError.getMessage().contains("invalid default AI binding"), invalid.name());
            assertTrue(updateError.getMessage().contains("invalid default AI binding"), invalid.name());
            assertEquals(0, repository.createRoleCalls, invalid.name());
            assertEquals(0, repository.updateRoleCalls, invalid.name());
        }
    }

    @Test
    void createAndUpdateRolePersistValidDefaultBindings() {
        var repository = new RecordingWorkflowRepository(workflow(), existingRole());
        var configuration = new EmptyConfigurationRepository(
                List.of(provider(PROVIDER_ID, true)),
                List.of(model(PROVIDER_ID, true, ReasoningEffort.MAX)));
        var service = service(repository, configuration);

        service.saveRole(roleCommand(null, ReasoningEffort.XHIGH, null));
        service.saveRole(roleCommand(ROLE_ID, ReasoningEffort.MAX, 0L));

        assertEquals(1, repository.createRoleCalls);
        assertEquals(1, repository.updateRoleCalls);
    }

    private static WorkflowManagementService service(RecordingWorkflowRepository repository) {
        return service(repository, new EmptyConfigurationRepository());
    }

    private static WorkflowManagementService service(
            RecordingWorkflowRepository repository,
            ConfigurationRepository configuration) {
        return new WorkflowManagementService(
                repository,
                new WorkflowAiBindingValidator(configuration),
                prefix -> prefix + "generated",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SaveAgentRoleCommand roleCommand(
            AgentRoleTemplateId roleTemplateId,
            ReasoningEffort effort,
            Long expectedVersion) {
        return new SaveAgentRoleCommand(
                roleTemplateId,
                "Role binding gate",
                "Validate the configured AI binding.",
                "Review the supplied evidence.",
                "Evidence: {{evidence}}",
                WorkflowOutputContract.RESEARCH_FINDINGS,
                new AiProviderProfileId(PROVIDER_ID),
                MODEL_NAME,
                effort,
                expectedVersion);
    }

    private static AgentRoleTemplate existingRole() {
        return new AgentRoleTemplate(
                ROLE_ID,
                "Existing role",
                "Existing role objective.",
                "Review evidence.",
                "Evidence: {{evidence}}",
                WorkflowOutputContract.RESEARCH_FINDINGS,
                new AiProviderProfileId(PROVIDER_ID),
                MODEL_NAME,
                ReasoningEffort.HIGH,
                false,
                0,
                NOW,
                NOW);
    }

    private static AiProviderProfile provider(String providerId, boolean enabled) {
        return new AiProviderProfile(
                providerId,
                "OpenAI-compatible role provider",
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

    private static AiModelProfile model(
            String providerId,
            boolean enabled,
            ReasoningEffort maximumReasoningEffort) {
        return new AiModelProfile(
                "modelprofile_" + providerId.substring("provider_".length()),
                providerId,
                MODEL_NAME,
                ReasoningEffort.PROVIDER_DEFAULT,
                maximumReasoningEffort,
                TokenLimitParameterStyle.PROTOCOL_DEFAULT,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                enabled,
                0,
                NOW);
    }

    private static WorkflowDefinitionVersion workflow() {
        var input = node("node_input000", WorkflowNodeType.INPUT, null);
        var collector = node("node_collect0", WorkflowNodeType.COLLECTOR, null);
        var deterministicCleaner = node("node_clean000", WorkflowNodeType.CLEANER, null);
        var cleaner = node("node_cleaner0", WorkflowNodeType.AI_CLEANER, ReasoningEffort.HIGH);
        var output = node("node_output00", WorkflowNodeType.OUTPUT, null);
        return new WorkflowDefinitionVersion(
                VERSION_ID,
                DEFINITION_ID,
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
                List.of(input, collector, deterministicCleaner, cleaner, output),
                List.of(
                        edge("edge_input_collect", input, collector),
                        edge("edge_collect_clean", collector, deterministicCleaner),
                        edge("edge_clean_ai", deterministicCleaner, cleaner),
                        edge("edge_ai_out", cleaner, output)));
    }

    private static WorkflowNodeDefinition node(
            String id,
            WorkflowNodeType type,
            ReasoningEffort effort) {
        var llm = type.llmBacked();
        return new WorkflowNodeDefinition(
                new WorkflowNodeId(id),
                type,
                id,
                llm ? "cleaner" : null,
                null,
                llm ? new AiModelBinding(
                        new AiProviderProfileId("provider_missing_test"),
                        "model-missing",
                        effort) : null,
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
                switch (type) {
                    case INPUT -> "research_input";
                    case COLLECTOR -> "collect_enabled_sources";
                    case CLEANER -> "normalize_and_deduplicate";
                    case OUTPUT -> "research_output";
                    default -> null;
                },
                new WorkflowCanvasPosition(BigDecimal.ZERO, BigDecimal.ZERO),
                true);
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

    private record InvalidRoleConfiguration(
            String name,
            ConfigurationRepository configuration,
            ReasoningEffort requestedEffort) {
    }

    private static final class RecordingWorkflowRepository implements WorkflowManagementRepository {
        private final WorkflowDefinitionVersion version;
        private final AgentRoleTemplate existingRole;
        private int publishCalls;
        private int saveDraftCalls;
        private int setActiveCalls;
        private int createRoleCalls;
        private int updateRoleCalls;

        private RecordingWorkflowRepository(WorkflowDefinitionVersion version) {
            this(version, null);
        }

        private RecordingWorkflowRepository(
                WorkflowDefinitionVersion version,
                AgentRoleTemplate existingRole) {
            this.version = version;
            this.existingRole = existingRole;
        }

        @Override
        public List<WorkflowDefinitionSummary> listDefinitions() {
            return List.of(new WorkflowDefinitionSummary(
                    DEFINITION_ID,
                    "Binding gate",
                    "",
                    false,
                    false,
                    VERSION_ID,
                    1,
                    VERSION_ID,
                    1,
                    NOW));
        }

        @Override public Optional<WorkflowDefinitionVersion> findVersion(WorkflowVersionId versionId) { return Optional.of(version); }
        @Override public List<WorkflowDefinitionVersion> listVersions(WorkflowDefinitionId definitionId) { return List.of(version); }
        @Override public Optional<WorkflowDefinitionVersion> findPublished(WorkflowDefinitionId definitionId) { return Optional.of(version); }
        @Override public boolean setActive(WorkflowDefinitionId definitionId, boolean active, Instant updatedAt) { setActiveCalls++; return true; }
        @Override public int nextVersionNumber(WorkflowDefinitionId definitionId) { return 2; }
        @Override public WorkflowDefinitionVersion saveDraft(String name, String description, boolean builtIn, WorkflowDefinitionVersion value, String expectedChecksum, Instant updatedAt) { saveDraftCalls++; return value; }
        @Override public WorkflowDefinitionVersion publish(WorkflowVersionId versionId, Instant publishedAt) { publishCalls++; return version; }
        @Override public List<AgentRoleTemplate> listRoles() { return existingRole == null ? List.of() : List.of(existingRole); }
        @Override public Optional<AgentRoleTemplate> findRole(AgentRoleTemplateId roleTemplateId) {
            return existingRole != null && existingRole.roleTemplateId().equals(roleTemplateId)
                    ? Optional.of(existingRole)
                    : Optional.empty();
        }
        @Override public AgentRoleTemplate createRole(AgentRoleTemplate role) { createRoleCalls++; return role; }
        @Override public Optional<AgentRoleTemplate> updateRole(AgentRoleTemplate role, long expectedVersion) {
            updateRoleCalls++;
            return Optional.of(role);
        }
        @Override public boolean deleteRole(AgentRoleTemplateId roleTemplateId, long expectedVersion) { return false; }
    }

    private static final class EmptyConfigurationRepository implements ConfigurationRepository {
        private final List<AiProviderProfile> providers;
        private final List<AiModelProfile> models;

        private EmptyConfigurationRepository() {
            this(List.of(), List.of());
        }

        private EmptyConfigurationRepository(
                List<AiProviderProfile> providers,
                List<AiModelProfile> models) {
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
