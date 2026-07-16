package io.omnnu.finbot.api.configuration;

import io.omnnu.finbot.application.configuration.ClearRuntimeSecretCommand;
import io.omnnu.finbot.application.configuration.RuntimeSecretManagementUseCase;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.UpdateRuntimeSecretCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/runtime-secrets")
public final class RuntimeSecretController {
    private final RuntimeSecretManagementUseCase useCase;

    public RuntimeSecretController(RuntimeSecretManagementUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @PutMapping("/{scope}/{targetId}/{secretName}")
    public RuntimeSecretStatus put(
            @PathVariable RuntimeSecretScope scope,
            @PathVariable String targetId,
            @PathVariable String secretName,
            @Valid @RequestBody PutRequest request) {
        return useCase.put(new UpdateRuntimeSecretCommand(
                scope,
                targetId,
                secretName,
                request.value(),
                request.expectedVersion()));
    }

    @DeleteMapping("/{scope}/{targetId}/{secretName}")
    public RuntimeSecretStatus clear(
            @PathVariable RuntimeSecretScope scope,
            @PathVariable String targetId,
            @PathVariable String secretName,
            @Valid @RequestBody ClearRequest request) {
        return useCase.clear(new ClearRuntimeSecretCommand(
                scope,
                targetId,
                secretName,
                request.expectedVersion()));
    }

    public record PutRequest(
            @NotBlank @Size(min = 8, max = 16_384) String value,
            @Min(0) long expectedVersion) {
    }

    public record ClearRequest(@Min(0) long expectedVersion) {
    }
}
