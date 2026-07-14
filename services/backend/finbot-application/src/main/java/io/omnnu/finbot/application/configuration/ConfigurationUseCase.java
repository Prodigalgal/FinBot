package io.omnnu.finbot.application.configuration;

public interface ConfigurationUseCase {
    ConfigurationSnapshot snapshot();

    SystemSetting updateSetting(UpdateSettingCommand command);

    AiProviderView updateProvider(UpdateProviderCommand command);

    AiModelProfile updateModel(UpdateModelCommand command);
}
