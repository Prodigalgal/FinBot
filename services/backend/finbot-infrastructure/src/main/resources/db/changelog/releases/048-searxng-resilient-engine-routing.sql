--liquibase formatted sql

--changeset codex:048-searxng-resilient-engine-routing splitStatements:true endDelimiter:;
UPDATE information_source
SET endpoint_base_url = 'http://finbot-searxng:8080/search?categories=news&language=en&engine_shortcuts=bi%2Cddg%2Cgon%2Cbin%2Cddn',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id IN ('source_reuters_search', 'source_ap_search', 'source_searxng_news_search')
  AND source_mode = 'SEARCH_DISCOVERY'
  AND provider = 'searxng_internal'
  AND endpoint_base_url = 'http://finbot-searxng:8080/search?categories=news&language=en&engine_shortcuts=gon%2Cbin%2Cddn%2Cbrnews%2Cqwn%2Cspn%2Cyhn'
  AND deleted_at IS NULL;

UPDATE information_source
SET endpoint_base_url = 'http://finbot-searxng:8080/search?categories=general%2Cnews&language=zh-CN&engine_shortcuts=bi%2Cddg%2Cbd%2C360so%2Csogou%2Csogouw',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE source_id IN ('source_searxng_cn_mainstream', 'source_searxng_cn_finance')
  AND source_mode = 'SEARCH_DISCOVERY'
  AND provider = 'searxng_internal'
  AND endpoint_base_url = 'http://finbot-searxng:8080/search?categories=general%2Cnews&language=zh-CN&engine_shortcuts=bd%2C360so%2Csogou%2Csogouw'
  AND deleted_at IS NULL;

--rollback UPDATE information_source SET endpoint_base_url = 'http://finbot-searxng:8080/search?categories=news&language=en&engine_shortcuts=gon%2Cbin%2Cddn%2Cbrnews%2Cqwn%2Cspn%2Cyhn', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id IN ('source_reuters_search', 'source_ap_search', 'source_searxng_news_search') AND source_mode = 'SEARCH_DISCOVERY' AND provider = 'searxng_internal' AND endpoint_base_url = 'http://finbot-searxng:8080/search?categories=news&language=en&engine_shortcuts=bi%2Cddg%2Cgon%2Cbin%2Cddn' AND deleted_at IS NULL;
--rollback UPDATE information_source SET endpoint_base_url = 'http://finbot-searxng:8080/search?categories=general%2Cnews&language=zh-CN&engine_shortcuts=bd%2C360so%2Csogou%2Csogouw', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE source_id IN ('source_searxng_cn_mainstream', 'source_searxng_cn_finance') AND source_mode = 'SEARCH_DISCOVERY' AND provider = 'searxng_internal' AND endpoint_base_url = 'http://finbot-searxng:8080/search?categories=general%2Cnews&language=zh-CN&engine_shortcuts=bi%2Cddg%2Cbd%2C360so%2Csogou%2Csogouw' AND deleted_at IS NULL;
