package io.omnnu.finbot.application.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.CollectionStatus;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class IngestionApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-16T14:00:00Z");

    @Test
    void createsUpdatesAndArchivesManagedSourceWithGenericCredentialBinding() {
        var repository = new StubRepository();
        var service = service(repository);

        var created = service.createSource(new CreateSourceCommand(rssDefinition(true)));

        assertEquals("source_managed_test01", created.sourceId().value());
        assertEquals("FINBOT_INFORMATION_SOURCE_KEYS_JSON",
                created.credentialEnvironmentVariable());

        var updated = service.updateSource(new UpdateSourceCommand(
                created.sourceId(), rssDefinition(false), 0));
        assertNull(updated.credentialEnvironmentVariable());

        service.deleteSource(new DeleteSourceCommand(created.sourceId(), updated.version()));
        assertEquals(created.sourceId(), repository.archivedSourceId);
    }

    @Test
    void rejectsFirecrawlScrapeWithoutSeedUrl() {
        var repository = new StubRepository();
        var definition = new SourceDefinition(
                "Missing seed",
                SourceMode.FIRECRAWL_SCRAPE,
                SourceTier.T2,
                "news",
                "firecrawl",
                new BigDecimal("0.7"),
                900,
                SourcePriority.P2,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                URI.create("https://api.firecrawl.dev/v2"),
                false,
                OutboundRoute.FIRECRAWL,
                10,
                3,
                true);

        assertThrows(IllegalArgumentException.class,
                () -> service(repository).createSource(new CreateSourceCommand(definition)));
    }

    @Test
    void rejectsFirstPartyHtmlWithoutSeedUrl() {
        var definition = new SourceDefinition(
                "Missing HTML seed",
                SourceMode.HTML_DOCUMENT,
                SourceTier.T1,
                "official_news",
                "first_party_html",
                new BigDecimal("0.9"),
                900,
                SourcePriority.P1,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                OutboundRoute.WEB_CRAWL,
                10,
                0,
                true);

        assertThrows(IllegalArgumentException.class,
                () -> service(new StubRepository()).createSource(new CreateSourceCommand(definition)));
    }

    @Test
    void createsSearchDiscoverySourceWithProviderNeutralCredentialBinding() {
        var definition = new SourceDefinition(
                "Brave search",
                SourceMode.SEARCH_DISCOVERY,
                SourceTier.T2,
                "market_news",
                "brave",
                new BigDecimal("0.8"),
                900,
                SourcePriority.P2,
                List.of("BTCUSDT"),
                List.of(),
                List.of(),
                List.of("bitcoin market latest"),
                URI.create("https://api.search.brave.com/res/v1/web/search"),
                true,
                OutboundRoute.WEB_CRAWL,
                10,
                0,
                false);

        var created = service(new StubRepository()).createSource(new CreateSourceCommand(definition));

        assertEquals(SourceMode.SEARCH_DISCOVERY, created.mode());
        assertEquals("brave", created.provider());
        assertEquals(OutboundRoute.WEB_CRAWL, created.outboundRoute());
        assertEquals("FINBOT_INFORMATION_SOURCE_KEYS_JSON", created.credentialEnvironmentVariable());
    }

    @Test
    void completesCollectionAsFailedBeforePropagatingUnexpectedCollectorFailure() {
        var repository = new StubRepository();
        var service = new IngestionApplicationService(
                repository,
                (source, query) -> {
                    throw new IllegalStateException("provider failed outside the error contract");
                },
                (source, evidenceId, payload) -> Optional.empty(),
                prefix -> prefix + "managed_test01",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);
        service.createSource(new CreateSourceCommand(rssDefinition(false)));

        var exception = assertThrows(CompletionException.class,
                () -> service.testSource(
                                new SourceId("source_managed_test01"),
                                "market update")
                        .toCompletableFuture()
                        .join());

        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertEquals(CollectionStatus.FAILED, repository.completedStatus);
        assertEquals("SOURCE_COLLECTION_UNEXPECTED", repository.completedErrorCode);
    }

    private static IngestionApplicationService service(IngestionRepository repository) {
        return new IngestionApplicationService(
                repository,
                (source, query) -> List.of(),
                (source, evidenceId, payload) -> Optional.empty(),
                prefix -> prefix + "managed_test01",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);
    }

    private static SourceDefinition rssDefinition(boolean credentialSupported) {
        return new SourceDefinition(
                "Managed RSS",
                SourceMode.RSS,
                SourceTier.T2,
                "market_news",
                "managed",
                new BigDecimal("0.75"),
                900,
                SourcePriority.P2,
                List.of("BTCUSDT"),
                List.of(URI.create("https://example.com/feed.xml")),
                List.of(),
                List.of(),
                null,
                credentialSupported,
                OutboundRoute.PUBLIC_DATA,
                10,
                0,
                true);
    }

    private static final class StubRepository implements IngestionRepository {
        private InformationSource source;
        private SourceId archivedSourceId;
        private CollectionStatus completedStatus;
        private String completedErrorCode;

        @Override
        public List<InformationSource> listSources(boolean enabledOnly) {
            return source == null || archivedSourceId != null ? List.of() : List.of(source);
        }

        @Override
        public Optional<InformationSource> findSource(SourceId sourceId) {
            return source != null && source.sourceId().equals(sourceId) && archivedSourceId == null
                    ? Optional.of(source)
                    : Optional.empty();
        }

        @Override
        public Optional<InformationSource> createSource(
                InformationSource candidate,
                Instant createdAt) {
            if (source != null || !NOW.equals(createdAt)) {
                return Optional.empty();
            }
            source = candidate;
            return Optional.of(candidate);
        }

        @Override
        public Optional<InformationSource> updateSource(
                InformationSource candidate,
                long expectedVersion,
                Instant updatedAt) {
            if (source == null || source.version() != expectedVersion || !NOW.equals(updatedAt)) {
                return Optional.empty();
            }
            source = withVersion(candidate, expectedVersion + 1);
            return Optional.of(source);
        }

        @Override
        public boolean archiveSource(
                SourceId sourceId,
                long expectedVersion,
                Instant archivedAt) {
            if (source == null || !source.sourceId().equals(sourceId)
                    || source.version() != expectedVersion || !NOW.equals(archivedAt)) {
                return false;
            }
            archivedSourceId = sourceId;
            return true;
        }

        @Override
        public Optional<InformationSource> setSourceEnabled(
                SourceId sourceId,
                boolean enabled,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }

        @Override
        public void startCollection(SourceCollectionRun collectionRun) {
            assertEquals(CollectionStatus.RUNNING, collectionRun.status());
        }

        @Override
        public void recordFetchAttempt(SourceFetchAttempt attempt) {
            // The application tests assert collection state; persistence is covered by the JDBC adapter.
        }

        @Override
        public PersistEvidenceResult saveEvidence(
                RawEvidenceRecord evidence,
                Optional<NormalizedDocument> normalizedDocument) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void finishCollection(
                CollectionRunId collectionId,
                CollectionStatus status,
                int fetchedCount,
                int insertedCount,
                int duplicateCount,
                String errorCode,
                String errorMessage,
                Instant completedAt) {
            completedStatus = status;
            completedErrorCode = errorCode;
        }

        @Override
        public void saveEvidencePackage(
                ResearchEvidencePackage evidencePackage,
                String contentHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<NormalizedDocument> listRecentDocuments(SourceId sourceId, int limit) {
            return List.of();
        }

        private static InformationSource withVersion(
                InformationSource source,
                long version) {
            return new InformationSource(
                    source.sourceId(),
                    source.displayName(),
                    source.mode(),
                    source.tier(),
                    source.category(),
                    source.provider(),
                    source.trustWeight(),
                    source.pollIntervalSeconds(),
                    source.priority(),
                    source.assetScope(),
                    source.feedUrls(),
                    source.seedUrls(),
                    source.searchQueries(),
                    source.endpointBaseUrl(),
                    source.credentialEnvironmentVariable(),
                    source.outboundRoute(),
                    source.maximumResults(),
                    source.maximumScrapeTargets(),
                    source.enabled(),
                    version);
        }
    }
}
