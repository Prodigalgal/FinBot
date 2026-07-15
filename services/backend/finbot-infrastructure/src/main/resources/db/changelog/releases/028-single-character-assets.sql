--liquibase formatted sql

--changeset codex:028-single-character-assets splitStatements:true endDelimiter:;
ALTER TABLE canonical_product DROP CONSTRAINT ck_canonical_product_asset;
ALTER TABLE canonical_product ADD CONSTRAINT ck_canonical_product_asset CHECK (
    base_asset ~ '^[A-Z0-9]{1,32}$' AND quote_asset ~ '^[A-Z0-9]{1,32}$'
);

--rollback ALTER TABLE canonical_product DROP CONSTRAINT ck_canonical_product_asset;
--rollback ALTER TABLE canonical_product ADD CONSTRAINT ck_canonical_product_asset CHECK (base_asset ~ '^[A-Z0-9]{2,32}$' AND quote_asset ~ '^[A-Z0-9]{2,32}$');
