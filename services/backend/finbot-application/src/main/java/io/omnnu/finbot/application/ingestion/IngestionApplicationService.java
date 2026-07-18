package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.CollectionStatus;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class IngestionApplicationService implements IngestionUseCase {
    private static final String INFORMATION_SOURCE_KEYS_JSON =
            "FINBOT_INFORMATION_SOURCE_KEYS_JSON";
    private final IngestionRepository repository;
    private final SourceCollectionGateway collectionGateway;
    private final EvidenceNormalizer evidenceNormalizer;
    private final SourceRuntimeHealthGateway runtimeHealthGateway;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;
    private final Executor executor;

    public IngestionApplicationService(
            IngestionRepository repository,
            SourceCollectionGateway collectionGateway,
            EvidenceNormalizer evidenceNormalizer,
            SourceRuntimeHealthGateway runtimeHealthGateway,
            SortableIdGenerator idGenerator,
            Clock clock,
            Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.collectionGateway = Objects.requireNonNull(collectionGateway, "collectionGateway");
        this.evidenceNormalizer = Objects.requireNonNull(evidenceNormalizer, "evidenceNormalizer");
        this.runtimeHealthGateway = Objects.requireNonNull(runtimeHealthGateway, "runtimeHealthGateway");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public List<InformationSource> listSources(boolean enabledOnly) {
        return repository.listSources(enabledOnly);
    }

    @Override
    public List<NormalizedDocument> listRecentDocuments(SourceId sourceId, int limit) {
        return repository.listRecentDocuments(sourceId, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public SourceRuntimeHealth sourceHealth(SourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        var source = repository.findSource(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Information source does not exist"));
        var runtime = runtimeHealthGateway.inspect(source);
        var history = repository.sourceAttemptHistory(sourceId);
        return new SourceRuntimeHealth(
                sourceId,
                runtime.serviceReady(),
                runtime.egressReady(),
                runtime.routeType(),
                runtime.routeEndpoint(),
                source.enabled() ? runtime.channelStatus() : "DISABLED",
                runtime.firecrawlChannelStatus(),
                runtime.rateLimitStatus(),
                history.lastSuccessAt(),
                history.lastBlockedAt(),
                history.lastAttemptAt(),
                history.latestOutcome(),
                history.latestStatusCode(),
                history.latestErrorCode() == null ? runtime.errorCode() : history.latestErrorCode(),
                history.safeMessage() == null ? runtime.safeMessage() : history.safeMessage());
    }

    @Override
    public InformationSource createSource(CreateSourceCommand command) {
        Objects.requireNonNull(command, "command");
        var source = source(
                new SourceId(idGenerator.next("source_")),
                command.definition(),
                0);
        return repository.createSource(source, clock.instant())
                .orElseThrow(() -> new IngestionConflictException(
                        "信息源标识冲突，请重试"));
    }

    @Override
    public InformationSource updateSource(UpdateSourceCommand command) {
        Objects.requireNonNull(command, "command");
        if (repository.findSource(command.sourceId()).isEmpty()) {
            throw new IllegalArgumentException("Information source does not exist");
        }
        var source = source(
                command.sourceId(),
                command.definition(),
                command.expectedVersion());
        return repository.updateSource(
                        source,
                        command.expectedVersion(),
                        clock.instant())
                .orElseThrow(() -> new IngestionConflictException(
                        "信息源配置已被修改，请刷新后重试"));
    }

    @Override
    public void deleteSource(DeleteSourceCommand command) {
        Objects.requireNonNull(command, "command");
        if (!repository.archiveSource(
                command.sourceId(),
                command.expectedVersion(),
                clock.instant())) {
            throw new IngestionConflictException(
                    "信息源不存在或版本冲突，请刷新后重试");
        }
    }

    @Override
    public InformationSource setSourceEnabled(
            SourceId sourceId,
            boolean enabled,
            long expectedVersion) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (repository.findSource(sourceId).isEmpty()) {
            throw new IllegalArgumentException("Information source does not exist");
        }
        return repository.setSourceEnabled(sourceId, enabled, expectedVersion, clock.instant())
                .orElseThrow(() -> new IngestionConflictException(
                        "信息源配置已被修改，请刷新后重试"));
    }

    @Override
    public CompletionStage<IngestionBatchResult> collectEnabled(
            WorkflowRunId workflowRunId,
            String requestSummary) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        var normalizedRequest = requireText(requestSummary, "requestSummary", 2_000);
        return CompletableFuture.supplyAsync(
                () -> collectEnabledSynchronously(workflowRunId, normalizedRequest),
                executor);
    }

    @Override
    public CompletionStage<SourceCollectionSummary> collectSource(
            WorkflowRunId workflowRunId,
            SourceId sourceId,
            String query) {
        Objects.requireNonNull(sourceId, "sourceId");
        var normalizedQuery = requireText(query, "query", 1_000);
        return CompletableFuture.supplyAsync(() -> {
            var source = repository.findSource(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException("Information source does not exist"));
            return collectOne(workflowRunId, source, normalizedQuery).summary();
        }, executor);
    }

    @Override
    public CompletionStage<SourceCollectionSummary> testSource(SourceId sourceId, String query) {
        Objects.requireNonNull(sourceId, "sourceId");
        var normalizedQuery = requireText(query, "query", 1_000);
        return CompletableFuture.supplyAsync(() -> {
            var source = repository.findSource(sourceId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Information source does not exist"));
            return collectOne(null, source, normalizedQuery).summary();
        }, executor);
    }

    private static InformationSource source(
            SourceId sourceId,
            SourceDefinition definition,
            long version) {
        var source = new InformationSource(
                sourceId,
                definition.displayName(),
                definition.mode(),
                definition.tier(),
                definition.category(),
                definition.provider(),
                definition.trustWeight(),
                definition.pollIntervalSeconds(),
                definition.priority(),
                definition.assetScope(),
                definition.feedUrls(),
                definition.seedUrls(),
                definition.searchQueries(),
                definition.endpointBaseUrl(),
                definition.credentialSupported() ? INFORMATION_SOURCE_KEYS_JSON : null,
                definition.outboundRoute(),
                definition.maximumResults(),
                definition.maximumScrapeTargets(),
                definition.enabled(),
                version,
                definition.aiWebSearchBinding());
        validateModeConfiguration(source);
        return source;
    }

    private static void validateModeConfiguration(InformationSource source) {
        switch (source.mode()) {
            case RSS -> {
                if (source.feedUrls().isEmpty()) {
                    throw new IllegalArgumentException("RSS source requires at least one feed URL");
                }
            }
            case HTML_DOCUMENT -> {
                if (source.seedUrls().isEmpty()) {
                    throw new IllegalArgumentException("HTML source requires at least one seed URL");
                }
            }
            case SEARCH_DISCOVERY -> requireEndpoint(source);
            case AI_WEB_SEARCH -> {
                if (source.searchQueries().isEmpty()) {
                    throw new IllegalArgumentException(
                            "AI web search source requires at least one default search query");
                }
            }
            case FIRECRAWL_SCRAPE -> {
                requireEndpoint(source);
                if (source.seedUrls().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Firecrawl scrape source requires at least one seed URL");
                }
            }
            case FIRECRAWL_SEARCH, FIRECRAWL_SEARCH_THEN_SCRAPE -> requireEndpoint(source);
            case JSON_API, SITEMAP, EXCHANGE_PUBLIC_API -> requireEndpoint(source);
        }
    }

    private static void requireEndpoint(InformationSource source) {
        if (source.endpointBaseUrl() == null) {
            throw new IllegalArgumentException(source.mode() + " source requires an endpoint URL");
        }
    }

    private IngestionBatchResult collectEnabledSynchronously(
            WorkflowRunId workflowRunId,
            String requestSummary) {
        var sources = repository.listSources(true);
        var futures = sources.stream()
                .map(source -> CompletableFuture.supplyAsync(
                        () -> collectOne(workflowRunId, source, source.defaultQuery(requestSummary)),
                        executor))
                .toList();
        awaitAll(futures);
        var outcomes = futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparing(outcome -> outcome.summary().sourceId().value()))
                .toList();
        var collections = outcomes.stream().map(CollectionOutcome::summary).toList();
        var evidence = outcomes.stream().flatMap(outcome -> outcome.evidence().stream()).toList();
        var artifactId = new ResearchArtifactId(idGenerator.next("artifact_"));
        var evidencePackage = new ResearchEvidencePackage(
                artifactId,
                workflowRunId,
                1,
                requestSummary,
                collections,
                evidence,
                clock.instant());
        repository.saveEvidencePackage(evidencePackage, packageHash(evidencePackage));
        return new IngestionBatchResult(
                artifactId,
                collections,
                collections.stream().mapToInt(SourceCollectionSummary::fetchedCount).sum(),
                collections.stream().mapToInt(SourceCollectionSummary::insertedCount).sum(),
                collections.stream().mapToInt(SourceCollectionSummary::duplicateCount).sum());
    }

    private CollectionOutcome collectOne(
            WorkflowRunId workflowRunId,
            InformationSource source,
            String query) {
        var collectionId = new CollectionRunId(idGenerator.next("collection_"));
        var startedAt = clock.instant();
        repository.startCollection(new SourceCollectionRun(
                collectionId,
                workflowRunId,
                source.sourceId(),
                query,
                CollectionStatus.RUNNING,
                0,
                0,
                0,
                null,
                null,
                startedAt,
                null));
        try {
            var payloads = collectionGateway.collect(source, query);
            var inserted = 0;
            var duplicates = 0;
            var excerpts = new ArrayList<EvidenceExcerpt>();
            for (var payload : payloads) {
                repository.recordFetchAttempt(fetchAttempt(
                        collectionId,
                        source,
                        payload,
                        "PREPARED",
                        null));
                var contentHash = hash(payload.rawContent());
                var deduplicationKey = hash(String.join("\u001f",
                        source.sourceId().value(),
                        payload.canonicalUrl() == null ? "" : payload.canonicalUrl().toString(),
                        contentHash));
                var evidenceId = new EvidenceId("evidence_" + deduplicationKey.substring(0, 40));
                var evidence = new RawEvidenceRecord(
                        evidenceId,
                        collectionId,
                        source.sourceId(),
                        payload.requestedUrl(),
                        payload.canonicalUrl(),
                        payload.query(),
                        payload.title(),
                        payload.statusCode(),
                        payload.contentType(),
                        payload.rawContent(),
                        payload.responseHeaders(),
                        payload.evidenceMetadata(),
                        contentHash,
                        deduplicationKey,
                        payload.publishedAt(),
                        payload.fetchedAt());
                var document = evidenceNormalizer.normalize(source, evidenceId, payload);
                var persisted = repository.saveEvidence(evidence, document);
                if (persisted.inserted()) {
                    inserted++;
                } else {
                    duplicates++;
                }
                document.map(EvidenceExcerpt::from).ifPresent(excerpts::add);
            }
            var status = payloads.isEmpty() ? CollectionStatus.PARTIAL : CollectionStatus.COMPLETED;
            var message = payloads.isEmpty() ? "Source returned no usable evidence" : null;
            repository.finishCollection(
                    collectionId,
                    status,
                    payloads.size(),
                    inserted,
                    duplicates,
                    payloads.isEmpty() ? "SOURCE_EMPTY" : null,
                    message,
                    clock.instant());
            return new CollectionOutcome(
                    new SourceCollectionSummary(
                            collectionId,
                            source.sourceId(),
                            status,
                            payloads.size(),
                            inserted,
                            duplicates,
                            payloads.isEmpty() ? "SOURCE_EMPTY" : null,
                            message),
                    List.copyOf(excerpts));
        } catch (SourceCollectionException exception) {
            var status = exception.blocked() ? CollectionStatus.BLOCKED : CollectionStatus.FAILED;
            repository.recordFetchAttempt(failedFetchAttempt(collectionId, source, exception));
            repository.finishCollection(
                    collectionId,
                    status,
                    0,
                    0,
                    0,
                    exception.errorCode(),
                    exception.getMessage(),
                    clock.instant());
            return new CollectionOutcome(
                    new SourceCollectionSummary(
                            collectionId,
                            source.sourceId(),
                            status,
                            0,
                            0,
                            0,
                            exception.errorCode(),
                            exception.getMessage()),
                    List.of());
        } catch (RuntimeException exception) {
            try {
                repository.recordFetchAttempt(failedFetchAttempt(
                        collectionId,
                        source,
                        new SourceCollectionException(
                                "SOURCE_COLLECTION_UNEXPECTED",
                                "Source collection failed unexpectedly",
                                false)));
                repository.finishCollection(
                        collectionId,
                        CollectionStatus.FAILED,
                        0,
                        0,
                        0,
                        "SOURCE_COLLECTION_UNEXPECTED",
                        "Source collection failed unexpectedly",
                        clock.instant());
            } catch (RuntimeException persistenceException) {
                exception.addSuppressed(persistenceException);
            }
            throw exception;
        }
    }

    private SourceFetchAttempt fetchAttempt(
            CollectionRunId collectionId,
            InformationSource source,
            CollectedPayload payload,
            String outcome,
            String errorCode) {
        var metadata = payload.metadata();
        var attemptCount = parsePositive(metadata.get("fetch_attempts"));
        var redirectCount = parseNonNegative(metadata.get("fetch_redirects"));
        return new SourceFetchAttempt(
                idGenerator.next("fetch_"),
                collectionId,
                source.sourceId(),
                payload.requestedUrl(),
                source.outboundRoute() == null ? "DIRECT" : source.outboundRoute().name(),
                payload.statusCode(),
                payload.contentType(),
                payload.rawContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                Math.max(0, attemptCount - 1),
                redirectCount,
                outcome,
                errorCode,
                metadata.getOrDefault("collector", "unknown"),
                payload.fetchedAt(),
                payload.fetchedAt());
    }

    private SourceFetchAttempt failedFetchAttempt(
            CollectionRunId collectionId,
            InformationSource source,
            SourceCollectionException exception) {
        var now = clock.instant();
        var requestedUrl = source.endpointBaseUrl();
        if (requestedUrl == null && !source.seedUrls().isEmpty()) {
            requestedUrl = source.seedUrls().getFirst();
        }
        if (requestedUrl == null && !source.feedUrls().isEmpty()) {
            requestedUrl = source.feedUrls().getFirst();
        }
        return new SourceFetchAttempt(
                idGenerator.next("fetch_"),
                collectionId,
                source.sourceId(),
                requestedUrl,
                source.outboundRoute() == null ? "DIRECT" : source.outboundRoute().name(),
                exception.statusCode(),
                null,
                0,
                0,
                0,
                exception.blocked() ? "BLOCKED" : "FAILED",
                exception.errorCode(),
                "none",
                now,
                now);
    }

    private static int parsePositive(String value) {
        try {
            return value == null ? 1 : Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static int parseNonNegative(String value) {
        try {
            return value == null ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static void awaitAll(List<? extends CompletableFuture<?>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private static String packageHash(ResearchEvidencePackage evidencePackage) {
        var canonical = new StringBuilder()
                .append(evidencePackage.workflowRunId().value()).append('\u001f')
                .append(evidencePackage.requestSummary());
        evidencePackage.collections().forEach(collection -> canonical
                .append('\u001f').append(collection.sourceId().value())
                .append(':').append(collection.status())
                .append(':').append(collection.insertedCount())
                .append(':').append(collection.duplicateCount()));
        evidencePackage.evidence().forEach(evidence -> canonical
                .append('\u001f').append(evidence.documentId().value())
                .append(':').append(evidence.evidenceId().value()));
        return hash(canonical.toString());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }

    private record CollectionOutcome(
            SourceCollectionSummary summary,
            List<EvidenceExcerpt> evidence) {
        private CollectionOutcome {
            evidence = List.copyOf(evidence);
        }
    }
}
