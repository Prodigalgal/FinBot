--liquibase formatted sql

--changeset finbot:051-proxy-gateway-engine
alter table proxy_gateway_profile
    add column engine varchar(16) not null default 'SING_BOX';

alter table proxy_gateway_profile
    add constraint ck_proxy_gateway_engine
    check (engine in ('SING_BOX', 'XRAY'));

--rollback alter table proxy_gateway_profile drop constraint ck_proxy_gateway_engine;
--rollback alter table proxy_gateway_profile drop column engine;
