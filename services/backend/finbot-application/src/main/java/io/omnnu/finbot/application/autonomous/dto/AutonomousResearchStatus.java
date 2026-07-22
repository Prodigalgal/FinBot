package io.omnnu.finbot.application.autonomous.dto;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;
import io.omnnu.finbot.application.operations.dto.OperationsOverview;
import io.omnnu.finbot.application.research.dto.ResearchHistoryDetail;
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
