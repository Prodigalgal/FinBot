--liquibase formatted sql

--changeset codex:010-exchange-route-recovery splitStatements:true endDelimiter:;
UPDATE network_proxy_route
SET require_proxy = FALSE,
    allow_direct = TRUE,
    display_name = CASE route_type
        WHEN 'EXCHANGE_GATE' THEN 'Gate TestNet 官方直连路由'
        WHEN 'EXCHANGE_BYBIT' THEN 'Bybit Demo 官方直连路由'
        ELSE display_name
    END,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE route_type IN ('EXCHANGE_GATE', 'EXCHANGE_BYBIT');

--rollback UPDATE network_proxy_route SET require_proxy = CASE WHEN route_type = 'EXCHANGE_BYBIT' THEN TRUE ELSE FALSE END, allow_direct = CASE WHEN route_type = 'EXCHANGE_GATE' THEN TRUE ELSE FALSE END, display_name = CASE WHEN route_type = 'EXCHANGE_GATE' THEN 'Gate TestNet 网络路由' WHEN route_type = 'EXCHANGE_BYBIT' THEN 'Bybit Demo IPv4 代理路由' ELSE display_name END, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE route_type IN ('EXCHANGE_GATE', 'EXCHANGE_BYBIT');
