package io.omnnu.finbot.application.operations;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BackgroundTaskTest {
    @Test
    void rejectsPayloadThatDoesNotMatchTaskType() {
        var now = Instant.parse("2026-07-14T08:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new BackgroundTask(
                new BackgroundTaskId("task_01j0000000001"),
                BackgroundTaskType.SCHEDULED_RESEARCH,
                BackgroundTaskStatus.PENDING,
                50,
                "idempotency-key",
                new AccountTaskPayload(new ExchangeAccountId("account_gate_testnet_default")),
                0,
                3,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now));
    }
}
