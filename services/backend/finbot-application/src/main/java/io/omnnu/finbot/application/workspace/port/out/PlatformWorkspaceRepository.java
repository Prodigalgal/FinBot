package io.omnnu.finbot.application.workspace.port.out;

import io.omnnu.finbot.application.workspace.dto.IngestionWorkspace;
import io.omnnu.finbot.application.workspace.dto.NetworkWorkspace;
import io.omnnu.finbot.application.workspace.dto.OperationsReport;
import io.omnnu.finbot.application.workspace.dto.PlatformReadiness;
import io.omnnu.finbot.application.workspace.dto.QuantWorkspace;
import io.omnnu.finbot.application.workspace.dto.WorkflowLearning;

import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.time.Instant;

public interface PlatformWorkspaceRepository {
    PlatformReadiness readiness(Instant generatedAt);

    IngestionWorkspace ingestion(int limit, Instant generatedAt);

    QuantWorkspace quant(int limit, Instant generatedAt);

    OperationsReport report(Instant fromInclusive, Instant toExclusive, Instant generatedAt);

    NetworkWorkspace network(Instant generatedAt);

    WorkflowLearning workflowLearning(WorkflowVersionId versionId, Instant generatedAt);
}
