package io.omnnu.finbot.application.autonomous;

import io.omnnu.finbot.application.operations.BackgroundTask;
import io.omnnu.finbot.application.operations.OperationsOverview;
import io.omnnu.finbot.application.research.ResearchHistoryDetail;
import java.time.Instant;

public record AutonomousResearchStatus(
        boolean enabled,
        boolean workerOnline,
        OperationsOverview.Schedule schedule,
        BackgroundTask activeTask,
        ResearchHistoryDetail.Summary latestRun,
        String latestConclusion,
        Instant generatedAt) {
}
