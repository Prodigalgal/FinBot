--liquibase formatted sql

--changeset codex:042-default-source-catalog-manifest splitStatements:true endDelimiter:;
CREATE TABLE information_source_catalog_manifest (
    catalog_id VARCHAR(80) PRIMARY KEY,
    catalog_version VARCHAR(32) NOT NULL UNIQUE,
    manifest_hash CHAR(64) NOT NULL,
    source_count INTEGER NOT NULL,
    source_ids JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_information_source_catalog_id CHECK (catalog_id ~ '^catalog_[a-z0-9_-]{4,72}$'),
    CONSTRAINT ck_information_source_catalog_version CHECK (catalog_version ~ '^v[0-9]+(\.[0-9]+){0,2}$'),
    CONSTRAINT ck_information_source_catalog_hash CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_information_source_catalog_count CHECK (source_count > 0),
    CONSTRAINT ck_information_source_catalog_ids CHECK (
        jsonb_typeof(source_ids) = 'array' AND jsonb_array_length(source_ids) = source_count
    )
);

INSERT INTO information_source_catalog_manifest (
    catalog_id, catalog_version, manifest_hash, source_count, source_ids
) VALUES (
    'catalog_default_sources',
    'v1',
    'd072d9c03dda10d7005a43906e50dbc0a4eda3d4df3b6bb40a18f868f9ed53c6',
    11,
    '["source_federal_reserve", "source_ecb_official", "source_eia_weekly",
      "source_opec_news", "source_white_house", "source_reuters_search",
      "source_ap_search", "source_gate_announcements", "source_bybit_announcements",
      "source_global_search", "source_x_market_search"]'::jsonb
);

--rollback DELETE FROM information_source_catalog_manifest WHERE catalog_id = 'catalog_default_sources';
--rollback DROP TABLE IF EXISTS information_source_catalog_manifest;
