package io.omnnu.finbot.application.configuration;

public interface ProviderModelCatalogUseCase {
    ProviderModelCatalog probe(String providerProfileId);

    ProviderModelCatalog probe(ProbeProviderCommand command);
}
