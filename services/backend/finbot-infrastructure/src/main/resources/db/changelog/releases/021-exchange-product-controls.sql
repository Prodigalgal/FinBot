--liquibase formatted sql

--changeset codex:021-exchange-product-controls splitStatements:true endDelimiter:;
ALTER TABLE venue_instrument
    ADD COLUMN execution_enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE exchange_account
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_exchange_account_version CHECK (version >= 0);

UPDATE venue_instrument
SET execution_enabled = FALSE,
    updated_at = CURRENT_TIMESTAMP
WHERE exchange = 'BYBIT'
  AND market_type = 'LINEAR_PERPETUAL'
  AND symbol IN (
      'XAUUSDT', 'XAGUSDT', 'AAPLUSDT', 'METAUSDT',
      'MSFTUSDT', 'NVDAUSDT', 'TSLAUSDT'
  );

CREATE INDEX ix_venue_instrument_execution
    ON venue_instrument (exchange, status, execution_enabled, symbol)
    WHERE status = 'ACTIVE' AND execution_enabled = TRUE;

--rollback DROP INDEX IF EXISTS ix_venue_instrument_execution;
--rollback ALTER TABLE exchange_account DROP CONSTRAINT IF EXISTS ck_exchange_account_version;
--rollback ALTER TABLE exchange_account DROP COLUMN IF EXISTS version;
--rollback ALTER TABLE venue_instrument DROP COLUMN IF EXISTS execution_enabled;
