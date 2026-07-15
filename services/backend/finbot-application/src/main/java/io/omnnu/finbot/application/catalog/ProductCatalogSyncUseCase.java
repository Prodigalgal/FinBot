package io.omnnu.finbot.application.catalog;

import java.util.List;

public interface ProductCatalogSyncUseCase {
    CatalogSyncResult synchronize(String syncRunId, CatalogSyncScope scope);

    List<CatalogSyncRun> latestRuns();
}
