package io.omnnu.finbot.application.catalog;

import java.time.Instant;
import java.util.List;

public interface ProductCatalogSyncStore {
    void start(String syncRunId, CatalogSyncScope scope, Instant startedAt);

    CatalogSyncResult complete(
            String syncRunId,
            CatalogSyncScope scope,
            List<CatalogInstrumentSnapshot> instruments,
            Instant completedAt);

    void fail(String syncRunId, String errorCode, String safeMessage, Instant failedAt);

    List<CatalogSyncRun> latestRuns();
}
