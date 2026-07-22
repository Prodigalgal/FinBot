package io.omnnu.finbot.application.ingestion.port.out;

import io.omnnu.finbot.application.ingestion.dto.CrawlerHeaderProfile;

import io.omnnu.finbot.domain.ingestion.SourceId;
import java.util.Optional;

@FunctionalInterface
public interface CrawlerHeaderProfileResolver {
    Optional<CrawlerHeaderProfile> resolve(SourceId sourceId);
}
