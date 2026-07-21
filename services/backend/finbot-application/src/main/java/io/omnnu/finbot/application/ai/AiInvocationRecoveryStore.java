package io.omnnu.finbot.application.ai;

import java.time.Instant;

public interface AiInvocationRecoveryStore {
    int failOrphanedInvocations(Instant recoveredAt);
}
