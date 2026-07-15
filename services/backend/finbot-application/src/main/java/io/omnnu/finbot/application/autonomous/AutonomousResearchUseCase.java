package io.omnnu.finbot.application.autonomous;

import io.omnnu.finbot.application.operations.BackgroundTask;

public interface AutonomousResearchUseCase {
    AutonomousResearchStatus status();

    BackgroundTask trigger(String idempotencyKey, String requestSummary);
}
