package io.omnnu.finbot.application.workspace;

import java.time.Instant;
import java.util.List;

public record PlatformReadiness(
        boolean ready,
        int score,
        List<Check> checks,
        LatestResearch latestResearch,
        AccountSummary accountSummary,
        long pendingTaskCount,
        long failedTaskCount,
        Instant generatedAt) {
    public PlatformReadiness {
        checks = List.copyOf(checks);
    }

    public record Check(
            String code,
            String title,
            String status,
            String detail,
            String actionPage,
            Instant observedAt) {
    }

    public record LatestResearch(
            String runId,
            String status,
            String requestSummary,
            String conclusion,
            Instant completedAt) {
    }

    public record AccountSummary(
            int enabledAccounts,
            int synchronizedAccounts,
            String currency,
            java.math.BigDecimal equity,
            java.math.BigDecimal unrealizedPnl,
            java.math.BigDecimal realizedPnl,
            Instant snapshotAt) {
    }
}
