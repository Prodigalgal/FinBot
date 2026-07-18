--liquibase formatted sql

--changeset codex:041-firecrawl-default-disabled splitStatements:true endDelimiter:;
UPDATE proxy_gateway_profile
SET enabled = FALSE,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE gateway_id = 'proxygateway_firecrawl';

UPDATE information_source
SET enabled = FALSE,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_mode IN (
    'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH', 'FIRECRAWL_SEARCH_THEN_SCRAPE'
)
  AND deleted_at IS NULL;

--rollback UPDATE information_source SET enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_mode IN ('FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH', 'FIRECRAWL_SEARCH_THEN_SCRAPE') AND deleted_at IS NULL;
--rollback UPDATE proxy_gateway_profile SET enabled = TRUE, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE gateway_id = 'proxygateway_firecrawl';
