package io.omnnu.finbot.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.domain.operations.WorkerId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.infrastructure.exchange.JdbcExchangeAccountControlRepository;
import io.omnnu.finbot.infrastructure.operations.JdbcBackgroundTaskStore;
import io.omnnu.finbot.infrastructure.operations.TaskPayloadCodec;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class LiquibasePostgresIntegrationTest {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
            .withDatabaseName("finbot")
            .withUsername("finbot")
            .withPassword("finbot-test");

    @Test
    void createsEmptySchemaAndIsIdempotent() throws Exception {
        updateSchema();
        updateSchema();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        select count(*)
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_name in (
                            'workflow_run', 'workflow_event', 'outbox_event',
                            'trade_decision', 'trade_proposal', 'approved_trade_intent',
                            'oms_order', 'oms_order_event',
                            'auth_challenge', 'admin_session', 'system_setting',
                            'ai_provider_profile', 'ai_model_profile', 'canonical_product',
                            'venue_instrument', 'instrument_alias', 'watchlist', 'watchlist_item',
                            'exchange_account', 'exchange_sync_cursor', 'exchange_account_snapshot',
                            'exchange_balance_fact', 'exchange_order_fact', 'exchange_fill_fact',
                            'exchange_position_snapshot', 'realized_pnl_fact',
                            'exchange_reconciliation_run', 'background_task', 'worker_instance',
                            'schedule_definition', 'workflow_definition',
                            'workflow_definition_version', 'agent_role_template',
                            'workflow_node_definition', 'workflow_edge_definition',
                            'workflow_node_checkpoint', 'debate_session', 'agent_message',
                            'agent_message_reply', 'ai_invocation', 'ai_stream_chunk',
                            'ai_budget_reservation', 'network_proxy_route',
                            'information_source', 'source_collection_run', 'raw_evidence',
                            'normalized_document', 'research_artifact', 'ai_compression'
                            , 'market_candle_fact', 'market_data_artifact',
                            'quant_research_run', 'quant_research_event', 'quant_metric_fact'
                            , 'trade_automation_run', 'trade_execution_ai_stage',
                            'trade_execution_ai_review', 'risk_policy', 'risk_assessment',
                            'exchange_submission_attempt'
                            , 'legacy_import_manifest', 'legacy_import_table', 'legacy_archive_row'
                          )
                        """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(63, result.getInt(1));
            }
        }

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        select
                          (select count(*) from system_setting) as setting_count,
                          (select count(*) from canonical_product) as product_count,
                          (select count(*) from exchange_account) as account_count,
                          (select count(*) from schedule_definition) as schedule_count,
                          (select count(*) from agent_role_template) as role_count,
                          (select count(*) from workflow_node_definition) as node_count,
                          (select count(*)
                           from workflow_node_definition node
                           join workflow_definition_version version on version.version_id = node.version_id
                           where version.status = 'PUBLISHED') as published_node_count,
                          (select count(*) from workflow_definition_version) as workflow_version_count,
                          (select version_id from workflow_definition_version
                           where status = 'PUBLISHED') as published_version_id,
                          (select count(*) from information_source) as source_count,
                          (select count(*) from network_proxy_route) as proxy_route_count,
                          (select count(*) from watchlist_item) as watchlist_item_count,
                          (select require_proxy from network_proxy_route
                           where route_type = 'EXCHANGE_GATE') as gate_proxy_required,
                          (select allow_direct from network_proxy_route
                           where route_type = 'EXCHANGE_GATE') as gate_direct_allowed,
                          (select require_proxy from network_proxy_route
                           where route_type = 'EXCHANGE_BYBIT') as bybit_proxy_required,
                          (select allow_direct from network_proxy_route
                           where route_type = 'EXCHANGE_BYBIT') as bybit_direct_allowed,
                          (select default_reasoning_effort from ai_model_profile
                           where model_name = 'gpt-5.6-sol') as sol_effort,
                          (select enabled from ai_provider_profile
                           where profile_id = 'provider_deepseek_default') as deepseek_enabled,
                          (select base_url from ai_provider_profile
                           where profile_id = 'provider_mimo_default') as mimo_base_url,
                          (select provider_profile_id from workflow_node_definition
                           where version_id = 'workflowversion_standard_v3'
                             and node_id = 'node_bull_analyst') as bull_provider,
                          (select fallback_provider_profile_id from workflow_node_definition
                           where version_id = 'workflowversion_standard_v3'
                             and node_id = 'node_bull_analyst') as bull_fallback_provider,
                          (select fallback_model_name from workflow_node_definition
                           where version_id = 'workflowversion_standard_v3'
                             and node_id = 'node_chair_arbiter') as chair_fallback_model,
                          (select preferred_leverage from risk_policy where active = true)
                            as preferred_leverage,
                          (select maximum_leverage from risk_policy where active = true)
                            as maximum_leverage,
                          (select active from workflow_definition
                           where definition_id = 'workflow_standard_product_research')
                            as workflow_active,
                          (select count(*) from venue_instrument
                           where execution_enabled = false) as research_only_instrument_count,
                          (select proxy_route_type from information_source
                           where source_id = 'source_x_market_search') as x_route,
                          (select count(*) from information_schema.columns
                           where table_schema = 'public'
                             and (table_name, column_name) in (
                               ('workflow_node_definition', 'fallback_provider_profile_id'),
                               ('trade_execution_ai_stage', 'fallback_provider_profile_id'),
                               ('risk_policy', 'preferred_leverage'),
                               ('workflow_definition', 'active'),
                               ('venue_instrument', 'execution_enabled'),
                               ('exchange_account', 'version')
                             )) as new_control_column_count
                        """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(15, result.getInt("setting_count"));
                assertEquals(10, result.getInt("product_count"));
                assertEquals(2, result.getInt("account_count"));
                assertEquals(5, result.getInt("schedule_count"));
                assertEquals(6, result.getInt("role_count"));
                assertEquals(36, result.getInt("node_count"));
                assertEquals(12, result.getInt("published_node_count"));
                assertEquals(3, result.getInt("workflow_version_count"));
                assertEquals("workflowversion_standard_v3", result.getString("published_version_id"));
                assertEquals(11, result.getInt("source_count"));
                assertEquals(4, result.getInt("proxy_route_count"));
                assertEquals(5, result.getInt("watchlist_item_count"));
                assertFalse(result.getBoolean("gate_proxy_required"));
                assertTrue(result.getBoolean("gate_direct_allowed"));
                assertFalse(result.getBoolean("bybit_proxy_required"));
                assertTrue(result.getBoolean("bybit_direct_allowed"));
                assertEquals("MAX", result.getString("sol_effort"));
                assertFalse(result.getBoolean("deepseek_enabled"));
                assertEquals(
                        "http://mimo2api.mimo2api.svc.cluster.local:8080/v1",
                        result.getString("mimo_base_url"));
                assertEquals("provider_sub2api_default", result.getString("bull_provider"));
                assertEquals("provider_mimo_default", result.getString("bull_fallback_provider"));
                assertEquals("gpt-5.6-terra", result.getString("chair_fallback_model"));
                assertEquals(0, new java.math.BigDecimal("20")
                        .compareTo(result.getBigDecimal("preferred_leverage")));
                assertEquals(0, new java.math.BigDecimal("20")
                        .compareTo(result.getBigDecimal("maximum_leverage")));
                assertTrue(result.getBoolean("workflow_active"));
                assertEquals(7, result.getInt("research_only_instrument_count"));
                assertEquals("FIRECRAWL", result.getString("x_route"));
                assertEquals(6, result.getInt("new_control_column_count"));
            }
        }
    }

    @Test
    void upgradesLegacyTradFiCatalogWithoutReplacingStableIdentifiers() throws Exception {
        var databaseName = "finbot_legacy_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(databaseName);
        var jdbcUrl = jdbcUrl(databaseName);

        try {
            updateSchema(jdbcUrl, 20);
            seedLegacyTradFiCatalog(jdbcUrl);
            updateSchema(jdbcUrl);

            try (var connection = DriverManager.getConnection(
                            jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = connection.prepareStatement("""
                            SELECT
                              (SELECT count(*) FROM databasechangelog) AS changeset_count,
                              (SELECT count(*) FROM canonical_product) AS product_count,
                              (SELECT count(*)
                               FROM canonical_product
                               WHERE product_id LIKE 'product_legacy_%'
                                 AND quote_asset = 'USDT'
                                 AND (
                                   (base_asset IN ('XAU', 'XAG') AND category = 'COMMODITY')
                                   OR (base_asset IN ('AAPL', 'META', 'MSFT', 'NVDA', 'TSLA')
                                       AND category = 'EQUITY')
                                 )) AS adopted_product_count,
                              (SELECT count(*)
                               FROM canonical_product
                               WHERE product_id IN (
                                 'product_commodity_xau_usdt', 'product_commodity_xag_usdt',
                                 'product_equity_aapl_usdt', 'product_equity_meta_usdt',
                                 'product_equity_msft_usdt', 'product_equity_nvda_usdt',
                                 'product_equity_tsla_usdt'
                               )) AS duplicate_seed_product_count,
                              (SELECT count(*)
                               FROM venue_instrument
                               WHERE instrument_id LIKE 'instrument_legacy_%'
                                 AND exchange = 'BYBIT'
                                 AND market_type = 'LINEAR_PERPETUAL'
                                 AND execution_enabled = FALSE) AS adopted_instrument_count,
                              (SELECT count(*)
                               FROM watchlist_item watchlist
                               JOIN venue_instrument instrument
                                 ON instrument.instrument_id = watchlist.preferred_instrument_id
                                AND instrument.product_id = watchlist.product_id
                               WHERE watchlist.watchlist_id = 'watchlist_admin_default'
                                 AND instrument.symbol IN ('XAUUSDT', 'AAPLUSDT')
                                 AND watchlist.research_mode = 'RESEARCH') AS mapped_watchlist_count
                            """)) {
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertEquals(23, result.getInt("changeset_count"));
                    assertEquals(10, result.getInt("product_count"));
                    assertEquals(7, result.getInt("adopted_product_count"));
                    assertEquals(0, result.getInt("duplicate_seed_product_count"));
                    assertEquals(7, result.getInt("adopted_instrument_count"));
                    assertEquals(2, result.getInt("mapped_watchlist_count"));
                }
            }
        } finally {
            dropDatabase(databaseName);
        }
    }

    @Test
    void bindsDomainInstantsAsPostgresTimestampWithTimeZone() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var store = new JdbcBackgroundTaskStore(
                JdbcClient.create(dataSource),
                new TaskPayloadCodec(new ObjectMapper()));
        var workerId = new WorkerId("worker_temporal_binding");
        var startedAt = Instant.parse("2026-07-14T01:00:00.123456Z");
        var heartbeatAt = startedAt.plusSeconds(30);
        var stoppedAt = heartbeatAt.plusSeconds(30);

        store.registerWorker(workerId, "postgres-temporal-integration", startedAt);
        store.heartbeatWorker(workerId, heartbeatAt);
        store.stopWorker(workerId, stoppedAt);

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        SELECT status, heartbeat_at, stopped_at
                        FROM worker_instance WHERE worker_id = ?
                        """)) {
            statement.setString(1, workerId.value());
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals("STOPPED", result.getString("status"));
                assertEquals(stoppedAt, result.getObject("heartbeat_at", OffsetDateTime.class).toInstant());
                assertEquals(stoppedAt, result.getObject("stopped_at", OffsetDateTime.class).toInstant());
            }
        }
    }

    @Test
    void updatesExchangeAccountControlWithOptimisticVersion() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var repository = new JdbcExchangeAccountControlRepository(JdbcClient.create(dataSource));
        var accountId = new ExchangeAccountId("account_bybit_demo_default");
        var current = repository.find(accountId).orElseThrow();

        var updated = repository.setEnabled(
                        accountId,
                        !current.enabled(),
                        current.version(),
                        Instant.parse("2026-07-14T09:00:00Z"))
                .orElseThrow();

        assertEquals(current.version() + 1, updated.version());
        assertEquals(!current.enabled(), updated.enabled());
        assertTrue(repository.setEnabled(
                        accountId,
                        current.enabled(),
                        current.version(),
                        Instant.parse("2026-07-14T09:01:00Z"))
                .isEmpty());
    }

    private static void updateSchema() throws Exception {
        updateSchema(POSTGRES.getJdbcUrl());
    }

    private static void updateSchema(String jdbcUrl) throws Exception {
        var resourceAccessor = new ClassLoaderResourceAccessor();
        var connection = DriverManager.getConnection(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var liquibase = new Liquibase(CHANGELOG, resourceAccessor, new JdbcConnection(connection))) {
            liquibase.validate();
            liquibase.update();
        }
    }

    private static void updateSchema(String jdbcUrl, int changesetCount) throws Exception {
        var resourceAccessor = new ClassLoaderResourceAccessor();
        var connection = DriverManager.getConnection(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var liquibase = new Liquibase(CHANGELOG, resourceAccessor, new JdbcConnection(connection))) {
            liquibase.validate();
            liquibase.update(changesetCount, new Contexts(), new LabelExpression());
        }
    }

    private static void seedLegacyTradFiCatalog(String jdbcUrl) throws Exception {
        try (var connection = DriverManager.getConnection(
                        jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO canonical_product (
                        product_id, base_asset, quote_asset, display_name, category, status
                    ) VALUES
                        ('product_legacy_xau', 'XAU', 'USDT', 'XAU/USDT', 'CRYPTO', 'ACTIVE'),
                        ('product_legacy_xag', 'XAG', 'USDT', 'XAG/USDT', 'CRYPTO', 'ACTIVE'),
                        ('product_legacy_aapl', 'AAPL', 'USDT', 'AAPL/USDT', 'CRYPTO', 'ACTIVE'),
                        ('product_legacy_meta', 'META', 'USDT', 'META/USDT', 'CRYPTO', 'ACTIVE'),
                        ('product_legacy_msft', 'MSFT', 'USDT', 'MSFT/USDT', 'CRYPTO', 'ACTIVE'),
                        ('product_legacy_nvda', 'NVDA', 'USDT', 'NVDA/USDT', 'CRYPTO', 'ACTIVE'),
                        ('product_legacy_tsla', 'TSLA', 'USDT', 'TSLA/USDT', 'CRYPTO', 'ACTIVE')
                    """);
            statement.executeUpdate("""
                    INSERT INTO venue_instrument (
                        instrument_id, product_id, exchange, market_type, symbol,
                        settlement_asset, contract_size, price_tick, quantity_step,
                        minimum_quantity, maximum_leverage, status, metadata_updated_at
                    ) VALUES
                        ('instrument_legacy_xau', 'product_legacy_xau', 'BYBIT', 'LINEAR_PERPETUAL', 'XAUUSDT', 'USDT', 1, 0.01, 0.001, 0.001, 100, 'ACTIVE', CURRENT_TIMESTAMP),
                        ('instrument_legacy_xag', 'product_legacy_xag', 'BYBIT', 'LINEAR_PERPETUAL', 'XAGUSDT', 'USDT', 1, 0.01, 0.001, 0.001, 100, 'ACTIVE', CURRENT_TIMESTAMP),
                        ('instrument_legacy_aapl', 'product_legacy_aapl', 'BYBIT', 'LINEAR_PERPETUAL', 'AAPLUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP),
                        ('instrument_legacy_meta', 'product_legacy_meta', 'BYBIT', 'LINEAR_PERPETUAL', 'METAUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 25, 'ACTIVE', CURRENT_TIMESTAMP),
                        ('instrument_legacy_msft', 'product_legacy_msft', 'BYBIT', 'LINEAR_PERPETUAL', 'MSFTUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP),
                        ('instrument_legacy_nvda', 'product_legacy_nvda', 'BYBIT', 'LINEAR_PERPETUAL', 'NVDAUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP),
                        ('instrument_legacy_tsla', 'product_legacy_tsla', 'BYBIT', 'LINEAR_PERPETUAL', 'TSLAUSDT', 'USDT', 1, 0.01, 0.01, 0.01, 50, 'ACTIVE', CURRENT_TIMESTAMP)
                    """);
        }
    }

    private static void createDatabase(String databaseName) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + databaseName);
        }
    }

    private static void dropDatabase(String databaseName) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        }
    }

    private static String jdbcUrl(String databaseName) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                POSTGRES.getHost(), POSTGRES.getMappedPort(5432), databaseName);
    }
}
