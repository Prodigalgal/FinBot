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
    private final IngestionRepository repository;
    private final SourceCollectionGateway collectionGateway;
    private final EvidenceNormalizer evidenceNormalizer;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;
    private final Executor executor;

    public IngestionApplicationService(
            IngestionRepository repository,
            SourceCollectionGateway collectionGateway,
            EvidenceNormalizer evidenceNormalizer,
            SortableIdGenerator idGenerator,
            Clock clock,
            Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.collectionGateway = Objects.requireNonNull(collectionGateway, "collectionGateway");
        this.evidenceNormalizer = Objects.requireNonNull(evidenceNormalizer, "evidenceNormalizer");
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
                        payload.metadata(),
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
