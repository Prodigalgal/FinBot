package io.omnnu.finbot.infrastructure.ingestion;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.ingestion.IngestionRepository;
import io.omnnu.finbot.application.ingestion.NormalizedDocument;
import io.omnnu.finbot.application.ingestion.PersistEvidenceResult;
import io.omnnu.finbot.application.ingestion.RawEvidenceRecord;
import io.omnnu.finbot.application.ingestion.ResearchEvidencePackage;
import io.omnnu.finbot.application.ingestion.SourceCollectionRun;
import io.omnnu.finbot.application.research.AiCompressionRecord;
import io.omnnu.finbot.application.research.CompressionPackage;
import io.omnnu.finbot.application.research.CompressionRepository;
import io.omnnu.finbot.application.research.EvidenceAiReview;
import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.CollectionStatus;
import io.omnnu.finbot.domain.ingestion.DocumentId;
import io.omnnu.finbot.domain.ingestion.EvidenceId;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.network.OutboundRoute;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcIngestionRepository implements IngestionRepository, CompressionRepository {
    private static final String SOURCE_COLUMNS = """
            source_id, display_name, source_mode, source_tier, category, provider,
            trust_weight, poll_interval_seconds, priority, asset_scope::text as asset_scope,
            feed_urls::text as feed_urls, seed_urls::text as seed_urls,
            search_queries::text as search_queries, endpoint_base_url,
            credential_env, proxy_route_type, maximum_results,
            maximum_scrape_targets, enabled, version
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcIngestionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<InformationSource> listSources(boolean enabledOnly) {
        var sql = "select " + SOURCE_COLUMNS + " from information_source";
        if (enabledOnly) {
            sql += " where enabled = true";
        }
        sql += " order by priority, source_tier, source_id";
        return jdbcClient.sql(sql).query(this::source).list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InformationSource> findSource(SourceId sourceId) {
        return jdbcClient.sql("select " + SOURCE_COLUMNS
                        + " from information_source where source_id = :sourceId")
                .param("sourceId", sourceId.value())
                .query(this::source)
                .optional();
    }

    @Override
    @Transactional
    public Optional<InformationSource> setSourceEnabled(
            SourceId sourceId,
            boolean enabled,
            long expectedVersion,
            Instant updatedAt) {
        var changed = jdbcClient.sql("""
                update information_source
                set enabled = :enabled,
                    version = version + 1,
                    updated_at = :updatedAt
                where source_id = :sourceId and version = :expectedVersion
                """)
                .param("sourceId", sourceId.value())
                .param("enabled", enabled)
                .param("expectedVersion", expectedVersion)
                .param("updatedAt", timestamp(updatedAt))
                .update();
        return changed == 1 ? findSource(sourceId) : Optional.empty();
    }

    @Override
    public void startCollection(SourceCollectionRun collectionRun) {
        jdbcClient.sql("""
                insert into source_collection_run (
                  collection_id, workflow_run_id, source_id, query, status,
                  fetched_count, inserted_count, duplicate_count, started_at
                ) values (
                  :collectionId, :workflowRunId, :sourceId, :query, 'RUNNING',
                  0, 0, 0, :startedAt
                )
                """)
                .param("collectionId", collectionRun.collectionId().value())
                .param("workflowRunId", collectionRun.workflowRunId() == null
                        ? null
                        : collectionRun.workflowRunId().value())
                .param("sourceId", collectionRun.sourceId().value())
                .param("query", collectionRun.query())
                .param("startedAt", timestamp(collectionRun.startedAt()))
                .update();
    }

    @Override
    @Transactional
    public PersistEvidenceResult saveEvidence(
            RawEvidenceRecord evidence,
            Optional<NormalizedDocument> normalizedDocument) {
        var inserted = jdbcClient.sql("""
                insert into raw_evidence (
                  evidence_id, collection_id, source_id, requested_url,
                  canonical_url, query, title, status_code, content_type,
                  raw_content, response_headers, metadata, content_hash,
                  deduplication_key, published_at, fetched_at
                ) values (
                  :evidenceId, :collectionId, :sourceId, :requestedUrl,
                  :canonicalUrl, :query, :title, :statusCode, :contentType,
                  :rawContent, cast(:responseHeaders as jsonb), cast(:metadata as jsonb),
                  :contentHash, :deduplicationKey, :publishedAt, :fetchedAt
                ) on conflict (deduplication_key) do nothing
                """)
                .param("evidenceId", evidence.evidenceId().value())
                .param("collectionId", evidence.collectionId().value())
                .param("sourceId", evidence.sourceId().value())
                .param("requestedUrl", uri(evidence.requestedUrl()))
                .param("canonicalUrl", uri(evidence.canonicalUrl()))
                .param("query", evidence.query())
                .param("title", evidence.title())
                .param("statusCode", evidence.statusCode())
                .param("contentType", evidence.contentType())
                .param("rawContent", evidence.rawContent())
                .param("responseHeaders", json(evidence.responseHeaders()))
                .param("metadata", json(evidence.metadata()))
                .param("contentHash", evidence.contentHash())
                .param("deduplicationKey", evidence.deduplicationKey())
                .param("publishedAt", timestamp(evidence.publishedAt()))
                .param("fetchedAt", timestamp(evidence.fetchedAt()))
                .update();
        if (inserted == 1 && normalizedDocument.isPresent()) {
            insertDocument(normalizedDocument.orElseThrow());
        }
        return new PersistEvidenceResult(inserted == 1);
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
        var changed = jdbcClient.sql("""
                update source_collection_run
                set status = :status,
                    fetched_count = :fetchedCount,
                    inserted_count = :insertedCount,
                    duplicate_count = :duplicateCount,
                    error_code = :errorCode,
                    error_message = :errorMessage,
                    completed_at = :completedAt
                where collection_id = :collectionId and status = 'RUNNING'
                """)
                .param("collectionId", collectionId.value())
                .param("status", status.name())
                .param("fetchedCount", fetchedCount)
                .param("insertedCount", insertedCount)
                .param("duplicateCount", duplicateCount)
                .param("errorCode", safe(errorCode, 80))
                .param("errorMessage", safe(errorMessage, 2_000))
                .param("completedAt", timestamp(completedAt))
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Source collection run is already terminal or missing");
        }
    }

    @Override
    public void saveEvidencePackage(
            ResearchEvidencePackage evidencePackage,
            String contentHash) {
        var provenance = objectMapper.createObjectNode()
                .put("policy", "raw-and-normalized-evidence-remain-authoritative")
                .put("collection_count", evidencePackage.collections().size())
                .put("evidence_count", evidencePackage.evidence().size());
        jdbcClient.sql("""
                insert into research_artifact (
                  artifact_id, workflow_run_id, artifact_type, schema_version,
                  content, provenance, content_hash, created_at
                ) values (
                  :artifactId, :workflowRunId, 'EVIDENCE_PACKAGE', :schemaVersion,
                  cast(:content as jsonb), cast(:provenance as jsonb), :contentHash, :createdAt
                ) on conflict (artifact_id) do nothing
                """)
                .param("artifactId", evidencePackage.artifactId().value())
                .param("workflowRunId", evidencePackage.workflowRunId().value())
                .param("schemaVersion", evidencePackage.schemaVersion())
                .param("content", json(evidencePackage))
                .param("provenance", json(provenance))
                .param("contentHash", contentHash)
                .param("createdAt", timestamp(evidencePackage.createdAt()))
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NormalizedDocument> listRecentDocuments(SourceId sourceId, int limit) {
        var safeLimit = Math.max(1, Math.min(limit, 200));
        var sql = """
                select document_id, evidence_id, source_id, source_tier, category,
                       trust_weight, canonical_url, title, title_key, language,
                       normalized_text, content_hash, asset_scope::text as asset_scope,
                       published_at, fetched_at, created_at
                from normalized_document
                """;
        if (sourceId != null) {
            sql += " where source_id = :sourceId";
        }
        sql += " order by fetched_at desc, id desc limit :limit";
        var statement = jdbcClient.sql(sql).param("limit", safeLimit);
        if (sourceId != null) {
            statement = statement.param("sourceId", sourceId.value());
        }
        return statement.query(this::document).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NormalizedDocument> listWorkflowDocuments(
            io.omnnu.finbot.domain.workflow.WorkflowRunId workflowRunId,
            int limit) {
        return jdbcClient.sql("""
                select document.document_id, document.evidence_id, document.source_id,
                       document.source_tier, document.category, document.trust_weight,
                       document.canonical_url, document.title, document.title_key,
                       document.language, document.normalized_text, document.content_hash,
                       document.asset_scope::text as asset_scope, document.published_at,
                       document.fetched_at, document.created_at
                from normalized_document document
                join raw_evidence evidence on evidence.evidence_id = document.evidence_id
                join source_collection_run collection
                  on collection.collection_id = evidence.collection_id
                where collection.workflow_run_id = :workflowRunId
                order by document.trust_weight desc, document.fetched_at desc, document.id desc
                limit :limit
                """)
                .param("workflowRunId", workflowRunId.value())
                .param("limit", Math.max(1, Math.min(limit, 100)))
                .query(this::document)
                .list();
    }

    @Override
    public void saveCompression(AiCompressionRecord compression) {
        jdbcClient.sql("""
                insert into ai_compression (
                  compression_id, workflow_run_id, document_id, invocation_id,
                  status, summary, key_points, risks, missing_evidence,
                  evidence_refs, prompt_hash, error_code, error_message, created_at
                ) values (
                  :compressionId, :workflowRunId, :documentId, :invocationId,
                  :status, :summary, cast(:keyPoints as jsonb), cast(:risks as jsonb),
                  cast(:missingEvidence as jsonb), cast(:evidenceRefs as jsonb),
                  :promptHash, :errorCode, :errorMessage, :createdAt
                ) on conflict (workflow_run_id, document_id, prompt_hash) do nothing
                """)
                .param("compressionId", compression.compressionId().value())
                .param("workflowRunId", compression.workflowRunId().value())
                .param("documentId", compression.documentId().value())
                .param("invocationId", compression.invocationId() == null
                        ? null
                        : compression.invocationId().value())
                .param("status", compression.status().name())
                .param("summary", compression.content().summary())
                .param("keyPoints", json(compression.content().keyPoints()))
                .param("risks", json(compression.content().risks()))
                .param("missingEvidence", json(compression.content().missingEvidence()))
                .param("evidenceRefs", json(compression.content().citations()))
                .param("promptHash", compression.promptHash())
                .param("errorCode", safe(compression.errorCode(), 80))
                .param("errorMessage", safe(compression.errorMessage(), 2_000))
                .param("createdAt", timestamp(compression.createdAt()))
                .update();
    }

    @Override
    public void saveEvidenceReview(EvidenceAiReview review) {
        jdbcClient.sql("""
                insert into evidence_ai_review (
                  review_id, workflow_run_id, workflow_version_id, document_id, node_id, invocation_id,
                  stage, status, content, prompt_hash, error_code, error_message, created_at
                ) values (
                  :reviewId, :workflowRunId, :workflowVersionId, :documentId, :nodeId, :invocationId,
                  :stage, :status, cast(:content as jsonb), :promptHash,
                  :errorCode, :errorMessage, :createdAt
                ) on conflict (workflow_run_id, document_id, node_id, prompt_hash) do nothing
                """)
                .param("reviewId", review.reviewId())
                .param("workflowRunId", review.workflowRunId().value())
                .param("workflowVersionId", review.workflowVersionId().value())
                .param("documentId", review.documentId().value())
                .param("nodeId", review.nodeId().value())
                .param("invocationId", review.invocationId() == null
                        ? null
                        : review.invocationId().value())
                .param("stage", review.stage().name())
                .param("status", review.status().name())
                .param("content", json(review.content()))
                .param("promptHash", review.promptHash())
                .param("errorCode", safe(review.errorCode(), 80))
                .param("errorMessage", safe(review.errorMessage(), 2_000))
                .param("createdAt", timestamp(review.createdAt()))
                .update();
    }

    @Override
    public void saveCompressionPackage(
            CompressionPackage compressionPackage,
            String contentHash) {
        var provenance = objectMapper.createObjectNode()
                .put("policy", compressionPackage.policy())
                .put("item_count", compressionPackage.items().size())
                .put("authority", "context-only; raw_evidence and normalized_document remain authoritative");
        jdbcClient.sql("""
                insert into research_artifact (
                  artifact_id, workflow_run_id, artifact_type, schema_version,
                  content, provenance, content_hash, created_at
                ) values (
                  :artifactId, :workflowRunId, 'COMPRESSION_PACKAGE', :schemaVersion,
                  cast(:content as jsonb), cast(:provenance as jsonb), :contentHash, :createdAt
                ) on conflict (artifact_id) do nothing
                """)
                .param("artifactId", compressionPackage.artifactId().value())
                .param("workflowRunId", compressionPackage.workflowRunId().value())
                .param("schemaVersion", compressionPackage.schemaVersion())
                .param("content", json(compressionPackage))
                .param("provenance", json(provenance))
                .param("contentHash", contentHash)
                .param("createdAt", timestamp(compressionPackage.createdAt()))
                .update();
    }

    private void insertDocument(NormalizedDocument document) {
        jdbcClient.sql("""
                insert into normalized_document (
                  document_id, evidence_id, source_id, source_tier, category,
                  trust_weight, canonical_url, title, title_key, language,
                  normalized_text, content_hash, asset_scope, published_at,
                  fetched_at, created_at
                ) values (
                  :documentId, :evidenceId, :sourceId, :sourceTier, :category,
                  :trustWeight, :canonicalUrl, :title, :titleKey, :language,
                  :normalizedText, :contentHash, cast(:assetScope as jsonb), :publishedAt,
                  :fetchedAt, :createdAt
                ) on conflict (evidence_id) do nothing
                """)
                .param("documentId", document.documentId().value())
                .param("evidenceId", document.evidenceId().value())
                .param("sourceId", document.sourceId().value())
                .param("sourceTier", document.sourceTier().name())
                .param("category", document.category())
                .param("trustWeight", document.trustWeight())
                .param("canonicalUrl", uri(document.canonicalUrl()))
                .param("title", document.title())
                .param("titleKey", document.titleKey())
                .param("language", document.language())
                .param("normalizedText", document.normalizedText())
                .param("contentHash", document.contentHash())
                .param("assetScope", json(document.assetScope()))
                .param("publishedAt", timestamp(document.publishedAt()))
                .param("fetchedAt", timestamp(document.fetchedAt()))
                .param("createdAt", timestamp(document.createdAt()))
                .update();
    }

    private InformationSource source(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InformationSource(
                new SourceId(resultSet.getString("source_id")),
                resultSet.getString("display_name"),
                SourceMode.valueOf(resultSet.getString("source_mode")),
                SourceTier.valueOf(resultSet.getString("source_tier")),
                resultSet.getString("category"),
                resultSet.getString("provider"),
                resultSet.getBigDecimal("trust_weight"),
                resultSet.getInt("poll_interval_seconds"),
                SourcePriority.valueOf(resultSet.getString("priority")),
                strings(resultSet.getString("asset_scope")),
                uris(resultSet.getString("feed_urls")),
                uris(resultSet.getString("seed_urls")),
                strings(resultSet.getString("search_queries")),
                nullableUri(resultSet.getString("endpoint_base_url")),
                resultSet.getString("credential_env"),
                resultSet.getString("proxy_route_type") == null
                        ? null
                        : OutboundRoute.valueOf(resultSet.getString("proxy_route_type")),
                resultSet.getInt("maximum_results"),
                resultSet.getInt("maximum_scrape_targets"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("version"));
    }

    private NormalizedDocument document(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NormalizedDocument(
                new DocumentId(resultSet.getString("document_id")),
                new EvidenceId(resultSet.getString("evidence_id")),
                new SourceId(resultSet.getString("source_id")),
                SourceTier.valueOf(resultSet.getString("source_tier")),
                resultSet.getString("category"),
                resultSet.getBigDecimal("trust_weight"),
                nullableUri(resultSet.getString("canonical_url")),
                resultSet.getString("title"),
                resultSet.getString("title_key"),
                resultSet.getString("language"),
                resultSet.getString("normalized_text"),
                resultSet.getString("content_hash"),
                strings(resultSet.getString("asset_scope")),
                instant(resultSet.getObject("published_at", OffsetDateTime.class)),
                instant(resultSet.getObject("fetched_at", OffsetDateTime.class)),
                instant(resultSet.getObject("created_at", OffsetDateTime.class)));
    }

    private List<String> strings(String json) {
        try {
            return List.of(objectMapper.readValue(json, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode ingestion string collection", exception);
        }
    }

    private List<URI> uris(String json) {
        return strings(json).stream().map(URI::create).toList();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode ingestion JSON", exception);
        }
    }

    private static String uri(URI value) {
        return value == null ? null : value.toString();
    }

    private static URI nullableUri(String value) {
        return value == null ? null : URI.create(value);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String safe(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.substring(0, Math.min(normalized.length(), maximumLength));
    }
}
