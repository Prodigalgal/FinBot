package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.SourceId;
import java.util.Optional;

@FunctionalInterface
public interface CrawlerHeaderProfileResolver {
    Optional<CrawlerHeaderProfile> resolve(SourceId sourceId);
}
