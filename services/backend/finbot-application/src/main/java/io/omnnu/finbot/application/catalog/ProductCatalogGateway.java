package io.omnnu.finbot.application.catalog;

import java.util.List;

public interface ProductCatalogGateway {
    List<CatalogInstrumentSnapshot> fetch(CatalogSyncScope scope);
}
