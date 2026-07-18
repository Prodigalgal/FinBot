--liquibase formatted sql

--changeset codex:038-json-sitemap-protocol splitStatements:true endDelimiter:;
ALTER TABLE information_source
    DROP CONSTRAINT ck_information_source_mode;

UPDATE information_source
SET source_mode = 'JSON_API'
WHERE source_mode = 'STRUCTURED_API';

ALTER TABLE information_source
    ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
        'RSS', 'HTML_DOCUMENT', 'SEARCH_DISCOVERY', 'JSON_API', 'SITEMAP',
        'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
        'FIRECRAWL_SEARCH_THEN_SCRAPE', 'EXCHANGE_PUBLIC_API'
    ));

--rollback ALTER TABLE information_source DROP CONSTRAINT ck_information_source_mode;
--rollback UPDATE information_source SET source_mode = 'STRUCTURED_API'
--rollback WHERE source_mode = 'JSON_API';
--rollback ALTER TABLE information_source ADD CONSTRAINT ck_information_source_mode CHECK (source_mode IN (
--rollback     'RSS', 'HTML_DOCUMENT', 'SEARCH_DISCOVERY', 'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
--rollback     'FIRECRAWL_SEARCH_THEN_SCRAPE', 'STRUCTURED_API', 'EXCHANGE_PUBLIC_API'
--rollback ));
