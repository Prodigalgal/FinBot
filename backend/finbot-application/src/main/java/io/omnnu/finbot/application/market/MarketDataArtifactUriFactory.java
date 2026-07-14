package io.omnnu.finbot.application.market;

import io.omnnu.finbot.domain.research.ResearchArtifactId;
import java.net.URI;

@FunctionalInterface
public interface MarketDataArtifactUriFactory {
    URI uri(ResearchArtifactId artifactId);
}
