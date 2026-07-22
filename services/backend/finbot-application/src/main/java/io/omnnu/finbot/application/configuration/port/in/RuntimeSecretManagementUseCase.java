package io.omnnu.finbot.application.configuration.port.in;

import io.omnnu.finbot.application.configuration.dto.ClearRuntimeSecretCommand;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.dto.UpdateRuntimeSecretCommand;

public interface RuntimeSecretManagementUseCase {
    RuntimeSecretStatus put(UpdateRuntimeSecretCommand command);

    RuntimeSecretStatus clear(ClearRuntimeSecretCommand command);
}
