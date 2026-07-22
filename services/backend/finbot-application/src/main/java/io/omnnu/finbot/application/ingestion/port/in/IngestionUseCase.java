package io.omnnu.finbot.application.ingestion.port.in;

import io.omnnu.finbot.application.ingestion.dto.CreateSourceCommand;
import io.omnnu.finbot.application.ingestion.dto.DeleteSourceCommand;
import io.omnnu.finbot.application.ingestion.dto.IngestionBatchResult;
import io.omnnu.finbot.application.ingestion.dto.NormalizedDocument;
import io.omnnu.finbot.application.ingestion.dto.SourceCollectionSummary;
import io.omnnu.finbot.application.ingestion.dto.SourceRuntimeHealth;
import io.omnnu.finbot.application.ingestion.dto.UpdateSourceCommand;

import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface IngestionUseCase {
    List<InformationSource> listSources(boolean enabledOnly);

    List<NormalizedDocument> listRecentDocuments(SourceId sourceId, int limit);

    default SourceRuntimeHealth sourceHealth(SourceId sourceId) {
        throw new UnsupportedOperationException("Source health is not available");
    }

    InformationSource createSource(CreateSourceCommand command);

    InformationSource updateSource(UpdateSourceCommand command);

    void deleteSource(DeleteSourceCommand command);

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
