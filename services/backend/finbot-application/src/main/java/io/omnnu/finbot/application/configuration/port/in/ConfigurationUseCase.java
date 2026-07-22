package io.omnnu.finbot.application.configuration.port.in;

import io.omnnu.finbot.application.configuration.dto.AiModelProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderView;
import io.omnnu.finbot.application.configuration.dto.ConfigurationSnapshot;
import io.omnnu.finbot.application.configuration.dto.CreateModelCommand;
import io.omnnu.finbot.application.configuration.dto.CreateProviderCommand;
import io.omnnu.finbot.application.configuration.dto.DeleteProviderCommand;
import io.omnnu.finbot.application.configuration.dto.SystemSetting;
import io.omnnu.finbot.application.configuration.dto.UpdateModelCommand;
import io.omnnu.finbot.application.configuration.dto.UpdateProviderCommand;
import io.omnnu.finbot.application.configuration.dto.UpdateSettingCommand;

public interface ConfigurationUseCase {
    ConfigurationSnapshot snapshot();

    AiProviderView createProvider(CreateProviderCommand command);

    AiModelProfile createModel(CreateModelCommand command);

    SystemSetting updateSetting(UpdateSettingCommand command);

    AiProviderView updateProvider(UpdateProviderCommand command);

    void deleteProvider(DeleteProviderCommand command);

    AiModelProfile updateModel(UpdateModelCommand command);
}
