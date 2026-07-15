package io.omnnu.finbot.application.workspace;

import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.time.Instant;

public interface PlatformWorkspaceUseCase {
    PlatformReadiness readiness();

    IngestionWorkspace ingestion(int limit);

    QuantWorkspace quant(int limit);

    OperationsReport report(Instant fromInclusive, Instant toExclusive);

    NetworkWorkspace network();

    WorkflowLearning workflowLearning(WorkflowVersionId versionId);
}
