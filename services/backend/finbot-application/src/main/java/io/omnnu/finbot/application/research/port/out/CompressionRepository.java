package io.omnnu.finbot.application.research.port.out;

import io.omnnu.finbot.application.research.dto.AiCompressionRecord;
import io.omnnu.finbot.application.research.dto.CompressionPackage;
import io.omnnu.finbot.application.research.dto.EvidenceAiReview;

import io.omnnu.finbot.application.ingestion.dto.NormalizedDocument;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;

public interface CompressionRepository {
    List<NormalizedDocument> listWorkflowDocuments(WorkflowRunId workflowRunId, int limit);

    void saveCompression(AiCompressionRecord compression);

    void saveEvidenceReview(EvidenceAiReview review);

    void saveCompressionPackage(CompressionPackage compressionPackage, String contentHash);
}
