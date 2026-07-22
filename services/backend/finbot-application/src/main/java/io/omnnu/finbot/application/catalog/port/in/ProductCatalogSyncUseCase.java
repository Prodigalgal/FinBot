package io.omnnu.finbot.application.catalog.port.in;

import io.omnnu.finbot.application.catalog.dto.CatalogSyncResult;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncRun;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncScope;

import java.util.List;

public interface ProductCatalogSyncUseCase {
    CatalogSyncResult synchronize(String syncRunId, CatalogSyncScope scope);

    List<CatalogSyncRun> latestRuns();
}
