package io.omnnu.finbot.application.workspace.port.in;

import io.omnnu.finbot.application.workspace.dto.IngestionWorkspace;
import io.omnnu.finbot.application.workspace.dto.NetworkWorkspace;
import io.omnnu.finbot.application.workspace.dto.OperationsReport;
import io.omnnu.finbot.application.workspace.dto.PlatformReadiness;
import io.omnnu.finbot.application.workspace.dto.QuantWorkspace;
import io.omnnu.finbot.application.workspace.dto.WorkflowLearning;

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
