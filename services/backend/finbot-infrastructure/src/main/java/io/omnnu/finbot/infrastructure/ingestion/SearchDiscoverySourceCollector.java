package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class SearchDiscoverySourceCollector implements SourceCollectorAdapter {
    private final List<SearchDiscoveryProvider> providers;

    SearchDiscoverySourceCollector(List<SearchDiscoveryProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one search discovery provider is required");
        }
    }

    @Override
    public boolean supports(SourceMode mode) {
        return mode == SourceMode.SEARCH_DISCOVERY;
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        var provider = providers.stream()
                .filter(candidate -> candidate.supports(providerName(source)))
                .findFirst()
                .orElseThrow(() -> new SourceCollectionException(
                        "SEARCH_DISCOVERY_PROVIDER_UNSUPPORTED",
                        "No search discovery provider is registered for " + providerName(source),
                        true));
        return provider.search(source, query);
    }

    private static String providerName(InformationSource source) {
        var provider = source.provider();
        return provider == null || provider.isBlank() ? "generic" : provider.strip().toLowerCase(java.util.Locale.ROOT);
    }
}
