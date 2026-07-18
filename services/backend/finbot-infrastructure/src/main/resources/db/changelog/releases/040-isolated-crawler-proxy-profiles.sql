--liquibase formatted sql

--changeset codex:040-isolated-crawler-proxy-profiles splitStatements:true endDelimiter:;
UPDATE proxy_gateway_profile
SET subscription_url_env = NULL,
    inline_nodes_env = 'FINBOT_PROXY_GATEWAY_SECRETS_JSON',
    preferred_names = '[]'::jsonb,
    maximum_nodes = 4,
    refresh_seconds = 1800,
    allow_insecure_tls = FALSE,
    enabled = TRUE,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE gateway_id = 'proxygateway_firecrawl';

INSERT INTO proxy_gateway_profile (
    gateway_id, display_name, control_url, subscription_url_env, inline_nodes_env,
    preferred_names, maximum_nodes, refresh_seconds, allow_insecure_tls, enabled
) VALUES (
    'proxygateway_web_crawl', '网页采集代理池',
    'http://finbot-web-crawl-proxy:8081', 'FINBOT_PROXY_GATEWAY_SECRETS_JSON', NULL,
    '[]'::jsonb, 32, 1800, FALSE, TRUE
);

--rollback DELETE FROM proxy_gateway_profile WHERE gateway_id = 'proxygateway_web_crawl';
--rollback UPDATE proxy_gateway_profile
--rollback SET subscription_url_env = 'FINBOT_PROXY_GATEWAY_SECRETS_JSON',
--rollback     inline_nodes_env = NULL,
--rollback     maximum_nodes = 32,
--rollback     version = version + 1,
--rollback     updated_at = CURRENT_TIMESTAMP
--rollback WHERE gateway_id = 'proxygateway_firecrawl';
