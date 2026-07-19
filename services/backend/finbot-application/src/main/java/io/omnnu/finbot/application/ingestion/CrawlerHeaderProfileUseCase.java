package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.util.List;

public interface CrawlerHeaderProfileUseCase {
    List<CrawlerHeaderProfile> listProfiles();

    CrawlerHeaderProfile createProfile(CrawlerHeaderProfileDefinition definition);

    CrawlerHeaderProfile updateProfile(
            CrawlerHeaderProfileId profileId,
            CrawlerHeaderProfileDefinition definition,
            long expectedVersion);

    void deleteProfile(CrawlerHeaderProfileId profileId, long expectedVersion);
}
