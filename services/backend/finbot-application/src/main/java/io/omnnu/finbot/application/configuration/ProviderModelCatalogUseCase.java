package io.omnnu.finbot.application.configuration;

@FunctionalInterface
public interface ProviderModelCatalogUseCase {
    ProviderModelCatalog probe(String providerProfileId);
}
