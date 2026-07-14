package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.ai.AiInvocationResult;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker.AiInvocationRejectedException;
import io.omnnu.finbot.application.ingestion.NormalizedDocument;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.workflow.WorkflowExecutionStore;
import io.omnnu.finbot.domain.ai.AiInvocationId;
import io.omnnu.finbot.domain.research.CompressionId;
import io.omnnu.finbot.domain.research.ResearchArtifactId;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeType;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class CompressionApplicationService implements CompressionUseCase {
    private static final int DOCUMENT_LIMIT = 12;
    private static final int MAXIMUM_DOCUMENT_CHARACTERS = 9_000;
    private static final String POLICY = "deterministic-cleaning-first-ai-compression-second";

    private final CompressionRepository repository;
    private final WorkflowExecutionStore workflowStore;
    private final WorkflowAiInvoker aiInvoker;
    private final CompressionOutputParser outputParser;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;
    private final Executor executor;

    public CompressionApplicationService(
            CompressionRepository repository,
            WorkflowExecutionStore workflowStore,
            WorkflowAiInvoker aiInvoker,
            CompressionOutputParser outputParser,
            SortableIdGenerator idGenerator,
            Clock clock,
            Executor executor) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.workflowStore = Objects.requireNonNull(workflowStore, "workflowStore");
        this.aiInvoker = Objects.requireNonNull(aiInvoker, "aiInvoker");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<CompressionBatchResult> compress(WorkflowRunId workflowRunId) {
        Objects.requireNonNull(workflowRunId, "workflowRunId");
        return CompletableFuture.supplyAsync(() -> compressSynchronously(workflowRunId), executor);
    }

    private CompressionBatchResult compressSynchronously(WorkflowRunId workflowRunId) {
        var execution = workflowStore.load(workflowRunId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run does not exist"));
        var compressor = execution.definitionVersion().nodes().stream()
                .filter(WorkflowNodeDefinition::enabled)
                .filter(node -> node.nodeType() == WorkflowNodeType.COMPRESSOR)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow has no enabled COMPRESSOR node"));
        var deadline = clock.instant().plus(minimum(
                execution.definitionVersion().maximumDuration(),
                Duration.ofMinutes(20)));
        var items = new ArrayList<CompressionItem>();
        for (var document : repository.listWorkflowDocuments(workflowRunId, DOCUMENT_LIMIT)) {
            items.add(compressDocument(
                    workflowRunId,
                    execution.definitionVersion(),
                    compressor,
                    document,
                    deadline));
        }
        var artifactId = new ResearchArtifactId(idGenerator.next("artifact_"));
        var compressionPackage = new CompressionPackage(
                artifactId,
                workflowRunId,
                1,
                POLICY,
                items,
                clock.instant());
        repository.saveCompressionPackage(compressionPackage, packageHash(compressionPackage));
        return new CompressionBatchResult(
                artifactId,
                (int) items.stream().filter(item -> item.status() == CompressionStatus.COMPLETED).count(),
                (int) items.stream().filter(item -> item.status() == CompressionStatus.FAILED).count(),
                (int) items.stream().filter(item -> item.status() == CompressionStatus.SKIPPED).count());
    }

    private CompressionItem compressDocument(
            WorkflowRunId workflowRunId,
            io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion version,
            WorkflowNodeDefinition compressor,
            NormalizedDocument document,
            Instant deadline) {
        var prompt = prompt(compressor, document);
        var promptHash = hash(compressor.systemPrompt() + '\u001f' + prompt);
        var compressionId = new CompressionId("compression_" + hash(String.join("\u001f",
                workflowRunId.value(),
                document.documentId().value(),
                promptHash)).substring(0, 40));
        AiInvocationId invocationId = null;
        String errorCode = null;
        String errorMessage = null;
        for (var attempt = 1; attempt <= compressor.retryPolicy().maximumAttempts(); attempt++) {
            try {
                AiInvocationResult invocation = aiInvoker.invokeDetailed(
                        workflowRunId,
                        version,
                        compressor,
                        prompt,
                        deadline);
                invocationId = invocation.invocationId();
                var content = withMandatoryCitations(outputParser.parse(invocation.output()), document);
                var record = new AiCompressionRecord(
                        compressionId,
                        workflowRunId,
                        document.documentId(),
                        invocationId,
                        CompressionStatus.COMPLETED,
                        content,
                        promptHash,
                        null,
                        null,
                        clock.instant());
                repository.saveCompression(record);
                return item(record, document);
            } catch (AiInvocationRejectedException exception) {
                errorCode = exception.errorCode();
                errorMessage = exception.getMessage();
                if (!exception.retryable()) {
                    break;
                }
            } catch (IllegalArgumentException exception) {
                errorCode = "COMPRESSION_OUTPUT_INVALID";
                errorMessage = "AI compression output did not match the required contract";
            }
            if (attempt < compressor.retryPolicy().maximumAttempts()) {
                pause(compressor.retryPolicy().backoff().multipliedBy(attempt));
            }
        }
        var fallback = fallback(document, errorMessage);
        var failed = new AiCompressionRecord(
                compressionId,
                workflowRunId,
                document.documentId(),
                invocationId,
                CompressionStatus.FAILED,
                fallback,
                promptHash,
                Objects.requireNonNullElse(errorCode, "COMPRESSION_FAILED"),
                Objects.requireNonNullElse(errorMessage, "AI compression failed"),
                clock.instant());
        repository.saveCompression(failed);
        return item(failed, document);
    }

    private static String prompt(
            WorkflowNodeDefinition compressor,
            NormalizedDocument document) {
        var text = document.normalizedText();
        var boundedText = text.substring(0, Math.min(text.length(), MAXIMUM_DOCUMENT_CHARACTERS));
        return compressor.userPromptTemplate()
                + "\n\ndocument_id: " + document.documentId().value()
                + "\nevidence_id: " + document.evidenceId().value()
                + "\nsource_id: " + document.sourceId().value()
                + "\nsource_tier: " + document.sourceTier()
                + "\ntrust_weight: " + document.trustWeight()
                + "\ntitle: " + document.title()
                + "\ntext:\n" + boundedText
                + "\n\n只返回 JSON：{\"summary\":\"...\",\"key_points\":[\"...\"],"
                + "\"risks\":[\"...\"],\"missing_evidence\":[\"...\"],"
                + "\"citations\":[\"document_id\",\"evidence_id\",\"source_id\"]}。"
                + "不要输出隐藏思维链。";
    }

    private static CompressionContent withMandatoryCitations(
            CompressionContent content,
            NormalizedDocument document) {
        var citations = new LinkedHashSet<>(content.citations());
        citations.add(document.documentId().value());
        citations.add(document.evidenceId().value());
        citations.add(document.sourceId().value());
        return new CompressionContent(
                content.summary(),
                content.keyPoints(),
                content.risks(),
                content.missingEvidence(),
                List.copyOf(citations));
    }

    private static CompressionContent fallback(
            NormalizedDocument document,
            String errorMessage) {
        var text = document.normalizedText();
        var excerpt = text.substring(0, Math.min(text.length(), 700));
        return new CompressionContent(
                excerpt,
                List.of(),
                List.of(),
                List.of(Objects.requireNonNullElse(
                        errorMessage,
                        "AI compression unavailable; deterministic excerpt retained")),
                List.of(
                        document.documentId().value(),
                        document.evidenceId().value(),
                        document.sourceId().value()));
    }

    private static CompressionItem item(
            AiCompressionRecord record,
            NormalizedDocument document) {
        return new CompressionItem(
                record.compressionId(),
                document.documentId(),
                document.evidenceId(),
                document.sourceId(),
                record.status(),
                record.content(),
                record.errorCode());
    }

    private static String packageHash(CompressionPackage compressionPackage) {
        var canonical = new StringBuilder(compressionPackage.workflowRunId().value())
                .append('\u001f').append(compressionPackage.policy());
        compressionPackage.items().forEach(item -> canonical
                .append('\u001f').append(item.compressionId().value())
                .append(':').append(item.status())
                .append(':').append(item.content().summary()));
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

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static void pause(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Compression retry was interrupted", exception);
        }
    }
}
