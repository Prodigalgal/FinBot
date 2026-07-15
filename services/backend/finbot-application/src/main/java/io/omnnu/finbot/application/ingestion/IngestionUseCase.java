package io.omnnu.finbot.application.ingestion;

import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface IngestionUseCase {
    List<InformationSource> listSources(boolean enabledOnly);

    List<NormalizedDocument> listRecentDocuments(SourceId sourceId, int limit);

    InformationSource setSourceEnabled(
            SourceId sourceId,
            boolean enabled,
            long expectedVersion);

    CompletionStage<IngestionBatchResult> collectEnabled(
            WorkflowRunId workflowRunId,
            String requestSummary);

    CompletionStage<SourceCollectionSummary> collectSource(
            WorkflowRunId workflowRunId,
            SourceId sourceId,
            String query);
}
