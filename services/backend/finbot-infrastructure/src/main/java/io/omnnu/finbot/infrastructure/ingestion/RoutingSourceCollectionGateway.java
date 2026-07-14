package io.omnnu.finbot.infrastructure.ingestion;

import io.omnnu.finbot.application.ingestion.CollectedPayload;
import io.omnnu.finbot.application.ingestion.SourceCollectionException;
import io.omnnu.finbot.application.ingestion.SourceCollectionGateway;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class RoutingSourceCollectionGateway implements SourceCollectionGateway {
    private final List<SourceCollectorAdapter> adapters;

    public RoutingSourceCollectionGateway(List<SourceCollectorAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    @Override
    public List<CollectedPayload> collect(InformationSource source, String query) {
        Objects.requireNonNull(source, "source");
        var adapter = adapters.stream()
                .filter(candidate -> candidate.supports(source.mode()))
                .findFirst()
                .orElseThrow(() -> new SourceCollectionException(
                        "SOURCE_MODE_UNSUPPORTED",
                        "No collector is registered for source mode " + source.mode(),
                        true));
        return adapter.collect(source, query);
    }
}
