package io.omnnu.finbot.application.catalog.port.out;

import io.omnnu.finbot.application.catalog.dto.CatalogInstrumentSnapshot;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncScope;

import java.util.List;

public interface ProductCatalogGateway {
    List<CatalogInstrumentSnapshot> fetch(CatalogSyncScope scope);
}
