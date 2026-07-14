package io.omnnu.finbot.infrastructure.market;

import io.omnnu.finbot.application.market.MarketDataArtifactUriFactory;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.net.URI;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class InternalMarketDataArtifactUriFactory implements MarketDataArtifactUriFactory {
    private final URI internalBaseUri;

    public InternalMarketDataArtifactUriFactory(
            @Value("${finbot.internal.base-url}") URI internalBaseUri) {
        this.internalBaseUri = requireBaseUri(internalBaseUri);
    }

    @Override
    public URI uri(ResearchArtifactId artifactId) {
        return internalBaseUri.resolve("/internal/v1/quant-artifacts/" + artifactId.value());
    }

    private static URI requireBaseUri(URI value) {
        Objects.requireNonNull(value, "internalBaseUri");
        if (!value.isAbsolute() || value.getHost() == null
                || !("http".equalsIgnoreCase(value.getScheme())
                        || "https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException("finbot.internal.base-url must be an absolute HTTP(S) URI");
        }
        return value;
    }
}
