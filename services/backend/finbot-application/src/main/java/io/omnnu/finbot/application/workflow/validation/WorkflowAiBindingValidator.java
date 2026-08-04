package io.omnnu.finbot.application.workflow.validation;

import io.omnnu.finbot.application.configuration.dto.AiModelProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderProfile;
import io.omnnu.finbot.application.configuration.port.out.ConfigurationRepository;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkflowAiBindingValidator {
    private final ConfigurationRepository configuration;

    public WorkflowAiBindingValidator(ConfigurationRepository configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public void validate(WorkflowDefinitionVersion version) {
        Objects.requireNonNull(version, "version");
        var providers = indexProviders(configuration.listProviders());
        var models = indexModels(configuration.listModels());
        version.nodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .filter(node -> node.nodeType().llmBacked())
                .forEach(node -> validateNode(node, providers, models));
    }

    public void validateRoleDefault(AiModelBinding binding) {
        Objects.requireNonNull(binding, "binding");
        validateBinding(
                "Agent role",
                "default",
                binding,
                indexProviders(configuration.listProviders()),
                indexModels(configuration.listModels()));
    }

    private static void validateNode(
            WorkflowNodeDefinition node,
            Map<String, AiProviderProfile> providers,
            Map<ModelKey, AiModelProfile> models) {
        var owner = "Workflow node " + node.nodeId().value();
        validateBinding(owner, "primary", node.primaryAiBinding(), providers, models);
        if (node.fallbackAiBinding() != null) {
            validateBinding(owner, "fallback", node.fallbackAiBinding(), providers, models);
        }
    }

    private static void validateBinding(
            String owner,
            String bindingKind,
            AiModelBinding binding,
            Map<String, AiProviderProfile> providers,
            Map<ModelKey, AiModelProfile> models) {
        var providerId = binding.providerProfileId().value();
        var provider = providers.get(providerId);
        if (provider == null) {
            throw invalid(owner, bindingKind, "AI provider does not exist: " + providerId);
        }
        if (!provider.enabled()) {
            throw invalid(owner, bindingKind, "AI provider is disabled: " + providerId);
        }
        var model = models.get(new ModelKey(providerId, binding.modelName()));
        if (model == null) {
            throw invalid(owner, bindingKind,
                    "AI model does not exist for provider " + providerId + ": " + binding.modelName());
        }
        if (!model.enabled()) {
            throw invalid(owner, bindingKind,
                    "AI model is disabled for provider " + providerId + ": " + binding.modelName());
        }
        if (!model.maximumReasoningEffort().supports(binding.reasoningEffort())) {
            throw invalid(owner, bindingKind,
                    "reasoning effort " + binding.reasoningEffort()
                            + " exceeds model capability " + model.maximumReasoningEffort());
        }
    }

    private static IllegalArgumentException invalid(
            String owner,
            String bindingKind,
            String reason) {
        return new IllegalArgumentException(
                owner + " has invalid " + bindingKind + " AI binding: " + reason);
    }

    private static Map<String, AiProviderProfile> indexProviders(Iterable<AiProviderProfile> providers) {
        var result = new HashMap<String, AiProviderProfile>();
        for (var provider : providers) {
            result.put(provider.profileId(), provider);
        }
        return Map.copyOf(result);
    }

    private static Map<ModelKey, AiModelProfile> indexModels(Iterable<AiModelProfile> models) {
        var result = new HashMap<ModelKey, AiModelProfile>();
        for (var model : models) {
            result.put(new ModelKey(model.providerProfileId(), model.modelName()), model);
        }
        return Map.copyOf(result);
    }

    private record ModelKey(String providerId, String modelName) {
    }
}
