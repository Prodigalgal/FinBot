package io.omnnu.finbot.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.domain.operations.WorkerId;
import io.omnnu.finbot.infrastructure.operations.JdbcBackgroundTaskStore;
import io.omnnu.finbot.infrastructure.operations.TaskPayloadCodec;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
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
                           where version_id = 'workflowversion_standard_v2'
                             and node_id = 'node_bull_analyst') as bull_provider,
                          (select provider_profile_id from workflow_node_definition
                           where version_id = 'workflowversion_standard_v2'
                             and node_id = 'node_risk_controller') as risk_provider
                        """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(15, result.getInt("setting_count"));
                assertEquals(3, result.getInt("product_count"));
                assertEquals(2, result.getInt("account_count"));
                assertEquals(5, result.getInt("schedule_count"));
                assertEquals(6, result.getInt("role_count"));
                assertEquals(24, result.getInt("node_count"));
                assertEquals(12, result.getInt("published_node_count"));
                assertEquals(2, result.getInt("workflow_version_count"));
                assertEquals("workflowversion_standard_v2", result.getString("published_version_id"));
                assertEquals(10, result.getInt("source_count"));
                assertEquals(4, result.getInt("proxy_route_count"));
                assertEquals(3, result.getInt("watchlist_item_count"));
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
                assertEquals("provider_mimo_default", result.getString("risk_provider"));
            }
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

    private static void updateSchema() throws Exception {
        var resourceAccessor = new ClassLoaderResourceAccessor();
        var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (var liquibase = new Liquibase(CHANGELOG, resourceAccessor, new JdbcConnection(connection))) {
            liquibase.validate();
            liquibase.update();
        }
    }
}
