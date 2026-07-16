package io.omnnu.finbot.application.configuration;

public interface RuntimeSecretManagementUseCase {
    RuntimeSecretStatus put(UpdateRuntimeSecretCommand command);

    RuntimeSecretStatus clear(ClearRuntimeSecretCommand command);
}
