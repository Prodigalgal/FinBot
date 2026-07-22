package io.omnnu.finbot.application.autonomous.port.in;

import io.omnnu.finbot.application.autonomous.dto.AutonomousResearchStatus;

import io.omnnu.finbot.application.operations.dto.BackgroundTask;

public interface AutonomousResearchUseCase {
    AutonomousResearchStatus status();

    BackgroundTask trigger(String idempotencyKey, String requestSummary);
}
