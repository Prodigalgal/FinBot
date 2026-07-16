package io.omnnu.finbot.application.configuration;

public interface ConfigurationUseCase {
    ConfigurationSnapshot snapshot();

    AiProviderView createProvider(CreateProviderCommand command);

    AiModelProfile createModel(CreateModelCommand command);

    SystemSetting updateSetting(UpdateSettingCommand command);

    AiProviderView updateProvider(UpdateProviderCommand command);

    void deleteProvider(DeleteProviderCommand command);

    AiModelProfile updateModel(UpdateModelCommand command);
}
