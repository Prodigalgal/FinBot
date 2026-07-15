package io.omnnu.finbot.application.configuration;

import java.net.URI;
import java.time.Duration;

@FunctionalInterface
public interface ProviderModelCatalogGateway {
    ProviderModelCatalog probe(
            String providerProfileId,
            URI baseUri,
            String apiKey,
            Duration timeout);
}
