package io.omnnu.finbot.api.configuration;

import io.omnnu.finbot.application.configuration.ProviderModelCatalog;
import io.omnnu.finbot.application.configuration.ProviderModelCatalogUseCase;
import java.util.Objects;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/configuration/providers")
public final class ProviderModelCatalogController {
    private final ProviderModelCatalogUseCase useCase;

    public ProviderModelCatalogController(ProviderModelCatalogUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @PostMapping("/{profileId}/probe")
    public ProviderModelCatalog probe(@PathVariable String profileId) {
        return useCase.probe(profileId);
    }
}
