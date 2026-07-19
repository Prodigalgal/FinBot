package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.CrawlerHeaderProfileId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CrawlerHeaderProfileRepository {
    List<CrawlerHeaderProfile> listProfiles();

    Optional<CrawlerHeaderProfile> findProfile(CrawlerHeaderProfileId profileId);

    Optional<CrawlerHeaderProfile> createProfile(CrawlerHeaderProfile profile, Instant createdAt);

    Optional<CrawlerHeaderProfile> updateProfile(
            CrawlerHeaderProfile profile,
            long expectedVersion,
            Instant updatedAt);

    boolean archiveProfile(CrawlerHeaderProfileId profileId, long expectedVersion, Instant archivedAt);
}
