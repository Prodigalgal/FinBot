package io.omnnu.finbot.migration;

import java.util.Map;

enum ImportDisposition {
    ARCHIVED("legacy_archive_row"),
    TRANSFORMED_AND_ARCHIVED("domain tables + legacy_archive_row");

    private static final Map<String, ImportDisposition> TRANSFORMED_TABLES = Map.of(
            "canonical_products", TRANSFORMED_AND_ARCHIVED,
            "venue_instruments", TRANSFORMED_AND_ARCHIVED,
            "instrument_aliases", TRANSFORMED_AND_ARCHIVED,
            "market_candles", TRANSFORMED_AND_ARCHIVED);

    private final String targetEntity;

    ImportDisposition(String targetEntity) {
        this.targetEntity = targetEntity;
    }

    static ImportDisposition forTable(String tableName) {
        return TRANSFORMED_TABLES.getOrDefault(tableName, ARCHIVED);
    }

    String targetEntity() {
        return targetEntity;
    }
}
