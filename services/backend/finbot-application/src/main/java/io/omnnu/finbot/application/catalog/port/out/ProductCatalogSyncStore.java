package io.omnnu.finbot.application.catalog.port.out;

import io.omnnu.finbot.application.catalog.dto.CatalogInstrumentSnapshot;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncResult;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncRun;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncScope;

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
