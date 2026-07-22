package io.omnnu.finbot.application.configuration.port.in;

import io.omnnu.finbot.application.configuration.dto.ProbeProviderCommand;
import io.omnnu.finbot.application.configuration.dto.ProviderModelCatalog;

public interface ProviderModelCatalogUseCase {
    ProviderModelCatalog probe(String providerProfileId);

    ProviderModelCatalog probe(ProbeProviderCommand command);
}
