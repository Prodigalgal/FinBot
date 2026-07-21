package io.omnnu.finbot.api.configuration;

import io.omnnu.finbot.application.configuration.AiModelProfile;
import io.omnnu.finbot.application.configuration.AiProviderView;
import io.omnnu.finbot.application.configuration.ConfigurationSnapshot;
import io.omnnu.finbot.application.configuration.ConfigurationUseCase;
import io.omnnu.finbot.application.configuration.CreateModelCommand;
import io.omnnu.finbot.application.configuration.CreateProviderCommand;
import io.omnnu.finbot.application.configuration.DeleteProviderCommand;
import io.omnnu.finbot.application.configuration.SystemSetting;
import io.omnnu.finbot.application.configuration.UpdateModelCommand;
import io.omnnu.finbot.application.configuration.UpdateProviderCommand;
import io.omnnu.finbot.application.configuration.UpdateSettingCommand;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v2/configuration")
public final class ConfigurationController {
    private final ConfigurationUseCase configurationUseCase;

    public ConfigurationController(ConfigurationUseCase configurationUseCase) {
        this.configurationUseCase = Objects.requireNonNull(configurationUseCase, "configurationUseCase");
    }

    @GetMapping
    public ConfigurationSnapshot snapshot() {
        return configurationUseCase.snapshot();
    }

    @PutMapping("/settings/{settingKey}")
    public SystemSetting updateSetting(
            @PathVariable String settingKey,
            @Valid @RequestBody UpdateSettingRequest request) {
        return configurationUseCase.updateSetting(new UpdateSettingCommand(
                settingKey,
                request.value(),
                request.expectedVersion()));
    }

    @PutMapping("/providers/{profileId}")
    public AiProviderView updateProvider(
            @PathVariable String profileId,
            @Valid @RequestBody UpdateProviderRequest request) {
        return configurationUseCase.updateProvider(new UpdateProviderCommand(
                profileId,
                request.displayName(),
                request.protocol(),
                request.reasoningParameterStyle(),
                request.baseUrl(),
                request.enabled(),
                request.connectTimeoutSeconds(),
                request.requestTimeoutSeconds(),
                request.maximumConcurrentRequests(),
                request.acquireTimeoutSeconds(),
                request.expectedVersion()));
    }

    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    public AiProviderView createProvider(@Valid @RequestBody CreateProviderRequest request) {
        return configurationUseCase.createProvider(new CreateProviderCommand(
                request.displayName(),
                request.protocol(),
                request.reasoningParameterStyle(),
                request.baseUrl(),
                request.enabled(),
                request.connectTimeoutSeconds(),
                request.requestTimeoutSeconds(),
                request.maximumConcurrentRequests(),
                request.acquireTimeoutSeconds()));
    }

    @DeleteMapping("/providers/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProvider(
            @PathVariable String profileId,
            @RequestParam long expectedVersion) {
        configurationUseCase.deleteProvider(new DeleteProviderCommand(profileId, expectedVersion));
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    public AiModelProfile createModel(@Valid @RequestBody CreateModelRequest request) {
        return configurationUseCase.createModel(new CreateModelCommand(
                request.providerProfileId(),
                request.modelName(),
                request.defaultReasoningEffort(),
                request.maximumReasoningEffort(),
                request.inputUsdPerMillion(),
                request.outputUsdPerMillion(),
                request.enabled()));
    }

    @PutMapping("/models/{modelProfileId}")
    public AiModelProfile updateModel(
            @PathVariable String modelProfileId,
            @Valid @RequestBody UpdateModelRequest request) {
        return configurationUseCase.updateModel(new UpdateModelCommand(
                modelProfileId,
                request.defaultReasoningEffort(),
                request.maximumReasoningEffort(),
                request.inputUsdPerMillion(),
                request.outputUsdPerMillion(),
                request.enabled(),
                request.expectedVersion()));
    }
}
