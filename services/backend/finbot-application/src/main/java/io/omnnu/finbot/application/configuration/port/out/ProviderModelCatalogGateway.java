package io.omnnu.finbot.application.configuration.port.out;

import io.omnnu.finbot.application.configuration.dto.ProviderModelCatalog;

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
