package io.omnnu.finbot.application.ai.port.out;

import java.time.Instant;

public interface AiInvocationRecoveryStore {
    int failOrphanedInvocations(Instant recoveredAt);
}
