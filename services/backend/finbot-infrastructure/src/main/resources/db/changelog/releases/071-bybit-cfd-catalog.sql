--liquibase formatted sql

--changeset codex:071-bybit-cfd-catalog splitStatements:true endDelimiter:;
ALTER TABLE venue_instrument DROP CONSTRAINT ck_venue_instrument_market;
ALTER TABLE venue_instrument DROP CONSTRAINT ck_venue_instrument_symbol;
ALTER TABLE venue_instrument ADD CONSTRAINT ck_venue_instrument_market CHECK (
    market_type IN ('SPOT','LINEAR_PERPETUAL','INVERSE_PERPETUAL','FUTURE','CFD'));
ALTER TABLE venue_instrument ADD CONSTRAINT ck_venue_instrument_symbol CHECK (
    (market_type <> 'CFD' AND symbol ~ '^[A-Z0-9_-]{2,48}$') OR
    (market_type = 'CFD' AND exchange = 'BYBIT' AND symbol ~ '^[A-Z0-9_-]{2,46}\.s$'));

-- Metadata verified from the public CFD page on 2026-10-03. No current quote is seeded.
INSERT INTO canonical_product(product_id,base_asset,quote_asset,display_name,category,status)
VALUES ('product_index_nas100_usd','NAS100','USD','纳斯达克 100 指数 CFD','INDEX','ACTIVE');
INSERT INTO venue_instrument(instrument_id,product_id,exchange,market_type,symbol,settlement_asset,
    contract_size,price_tick,quantity_step,minimum_quantity,maximum_leverage,status,metadata_updated_at,execution_enabled)
VALUES ('instrument_bybit_nas100_cfd','product_index_nas100_usd','BYBIT','CFD','NAS100.s','USD',
    1,0.01,0.1,0.1,500,'ACTIVE',CURRENT_TIMESTAMP,FALSE);

-- Correct legacy classifications while preserving every product and instrument identifier.
UPDATE canonical_product product SET category = CASE WHEN instrument.symbol = 'QQQUSDT' THEN 'INDEX' ELSE 'EQUITY' END,
    updated_at = CURRENT_TIMESTAMP
FROM venue_instrument instrument
WHERE instrument.product_id = product.product_id AND instrument.exchange = 'BYBIT'
  AND instrument.market_type = 'LINEAR_PERPETUAL' AND instrument.symbol IN ('SKHYNIXUSDT','SNDKUSDT','MUUSDT','QQQUSDT')
  AND product.category = 'CRYPTO'
  AND NOT EXISTS (SELECT 1 FROM canonical_product other WHERE other.product_id <> product.product_id
    AND other.base_asset = product.base_asset AND other.quote_asset = product.quote_asset
    AND other.category = CASE WHEN instrument.symbol = 'QQQUSDT' THEN 'INDEX' ELSE 'EQUITY' END);

-- Operational rollback retains NAS100.s and all audit references. Revert the application after disabling new tasks.
--rollback SELECT 1;
