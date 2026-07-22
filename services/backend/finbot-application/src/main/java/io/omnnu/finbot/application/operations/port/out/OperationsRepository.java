package io.omnnu.finbot.application.operations.port.out;

import io.omnnu.finbot.application.operations.dto.OperationsOverview;

import java.time.Instant;

public interface OperationsRepository {
    OperationsOverview overview(Instant generatedAt);

    OperationsOverview.Schedule updateSchedule(
            String scheduleId,
            boolean enabled,
            int intervalSeconds,
            long expectedVersion,
            Instant updatedAt);
}
