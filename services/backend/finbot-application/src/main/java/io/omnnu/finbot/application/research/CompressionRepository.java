package io.omnnu.finbot.application.research;

import io.omnnu.finbot.application.ingestion.NormalizedDocument;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;

public interface CompressionRepository {
    List<NormalizedDocument> listWorkflowDocuments(WorkflowRunId workflowRunId, int limit);

    void saveCompression(AiCompressionRecord compression);

    void saveCompressionPackage(CompressionPackage compressionPackage, String contentHash);
}
