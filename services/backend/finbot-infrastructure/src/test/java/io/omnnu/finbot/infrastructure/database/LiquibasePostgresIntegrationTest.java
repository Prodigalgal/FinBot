package io.omnnu.finbot.infrastructure.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnnu.finbot.application.configuration.dto.AiModelProfile;
import io.omnnu.finbot.application.configuration.dto.AiProviderProfile;
import io.omnnu.finbot.domain.operations.WorkerId;
import io.omnnu.finbot.domain.configuration.AiModelBinding;
import io.omnnu.finbot.domain.configuration.AiProtocol;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.configuration.ReasoningEffort;
import io.omnnu.finbot.domain.configuration.ReasoningParameterStyle;
import io.omnnu.finbot.application.ai.exception.AiProviderUnavailableException;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretSource;
import io.omnnu.finbot.application.configuration.dto.RuntimeSecretStatus;
import io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore;
import io.omnnu.finbot.domain.operations.BackgroundTaskId;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import io.omnnu.finbot.domain.operations.BackgroundTaskStatus;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.infrastructure.exchange.persistence.JdbcExchangeAccountControlRepository;
import io.omnnu.finbot.infrastructure.catalog.persistence.JdbcProductCatalogSyncStore;
import io.omnnu.finbot.infrastructure.ingestion.persistence.JdbcIngestionRepository;
import io.omnnu.finbot.infrastructure.ai.persistence.JdbcAiRuntimeProfileResolver;
import io.omnnu.finbot.infrastructure.ai.persistence.JdbcAiInvocationRecoveryStore;
import io.omnnu.finbot.infrastructure.identity.persistence.JdbcAdminApiTokenStore;
import io.omnnu.finbot.infrastructure.identity.adapter.SecureAuthenticationCryptography;
import io.omnnu.finbot.infrastructure.configuration.persistence.AesGcmRuntimeSecretCipher;
import io.omnnu.finbot.infrastructure.configuration.persistence.JdbcConfigurationRepository;
import io.omnnu.finbot.infrastructure.configuration.persistence.JdbcEncryptedRuntimeSecretStore;
import io.omnnu.finbot.infrastructure.network.persistence.JdbcNetworkDiagnosticStore;
import io.omnnu.finbot.infrastructure.setup.persistence.JdbcSetupProfileRepository;
import io.omnnu.finbot.infrastructure.operations.persistence.JdbcBackgroundTaskStore;
import io.omnnu.finbot.infrastructure.operations.persistence.TaskPayloadCodec;
import io.omnnu.finbot.infrastructure.workflow.persistence.JdbcWorkflowStore;
import io.omnnu.finbot.infrastructure.workflow.persistence.WorkflowEventCodec;
import io.omnnu.finbot.application.workflow.dto.StartWorkflowCommand;
import io.omnnu.finbot.application.catalog.dto.CatalogInstrumentSnapshot;
import io.omnnu.finbot.application.catalog.dto.CatalogSyncScope;
import io.omnnu.finbot.application.setup.dto.SetupProfileId;
import io.omnnu.finbot.domain.catalog.CatalogStatus;
import io.omnnu.finbot.domain.catalog.ExchangeVenue;
import io.omnnu.finbot.domain.catalog.MarketType;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.ingestion.CollectionRunId;
import io.omnnu.finbot.domain.ingestion.CollectionStatus;
import io.omnnu.finbot.domain.ingestion.InformationSource;
import io.omnnu.finbot.domain.ingestion.SourceMode;
import io.omnnu.finbot.domain.ingestion.SourcePriority;
import io.omnnu.finbot.domain.ingestion.SourceTier;
import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import io.omnnu.finbot.application.ingestion.dto.SourceCollectionRun;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.domain.workflow.WorkflowAccepted;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import io.omnnu.finbot.domain.workflow.WorkflowTrigger;
import io.omnnu.finbot.domain.workflow.WorkflowType;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.sql.DriverManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LiquibasePostgresIntegrationTest {
    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    @Test
    void encryptsHotRuntimeSecretsAndFallsBackToEnvironmentAfterClear() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var masterKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        io.omnnu.finbot.application.configuration.port.out.EnvironmentValueResolver environment = name -> switch (name) {
            case "FINBOT_RUNTIME_SECRET_MASTER_KEY" -> Optional.of(masterKey);
            case "FINBOT_AI_PROVIDER_KEYS_JSON" -> Optional.of(
                    "{\"provider_grok_sub2api\":\"environment-fallback-key\"}");
            case "FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON" -> Optional.of("""
                    {"account_gate_testnet_default":{
                      "API_KEY":"exchange-fallback-key",
                      "API_SECRET":"exchange-fallback-secret"
                    }}
                    """);
            case "FINBOT_INFORMATION_SOURCE_KEYS_JSON" -> Optional.of(
                    "{\"source_test\":\"source-fallback-key\"}");
            case "FINBOT_PROXY_ROUTE_URLS_JSON" -> Optional.of(
                    "{\"WEB_CRAWL\":\"http://proxy.example:8080\",\"FIRECRAWL\":\"http://proxy.example:8080\"}");
            case "FINBOT_PROXY_GATEWAY_SECRETS_JSON" -> Optional.of("""
                    {"proxygateway_firecrawl":{
                      "SUBSCRIPTION_URL":"https://subscription.example/list"
                    }}
                    """);
            default -> Optional.empty();
        };
        var store = new JdbcEncryptedRuntimeSecretStore(
                JdbcClient.create(dataSource),
                environment,
                new AesGcmRuntimeSecretCipher(environment),
                new ObjectMapper());
        var scope = RuntimeSecretScope.AI_PROVIDER;
        var targetId = "provider_grok_sub2api";

        var stored = store.put(
                scope,
                targetId,
                "API_KEY",
                "database-hot-key",
                "FINBOT_AI_PROVIDER_KEYS_JSON",
                0,
                Instant.parse("2026-07-16T08:00:00Z")).orElseThrow();

        assertEquals(RuntimeSecretSource.DATABASE_OVERRIDE, stored.source());
        assertEquals("database-hot-key", store.resolve(
                scope, targetId, "API_KEY", "FINBOT_AI_PROVIDER_KEYS_JSON").orElseThrow());
        assertTrue(store.put(
                scope,
                targetId,
                "API_KEY",
                "stale-write-key",
                "FINBOT_AI_PROVIDER_KEYS_JSON",
                0,
                Instant.parse("2026-07-16T08:01:00Z")).isEmpty());
        var rotated = store.put(
                scope,
                targetId,
                "API_KEY",
                "database-rotated-key",
                "FINBOT_AI_PROVIDER_KEYS_JSON",
                stored.version(),
                Instant.parse("2026-07-16T08:01:30Z")).orElseThrow();
        assertEquals(stored.version() + 1, rotated.version());
        assertEquals("database-rotated-key", store.resolve(
                scope, targetId, "API_KEY", "FINBOT_AI_PROVIDER_KEYS_JSON").orElseThrow());

        var cleared = store.clear(
                scope,
                targetId,
                "API_KEY",
                "FINBOT_AI_PROVIDER_KEYS_JSON",
                rotated.version(),
                Instant.parse("2026-07-16T08:02:00Z")).orElseThrow();

        assertEquals(RuntimeSecretSource.ENVIRONMENT_FALLBACK, cleared.source());
        assertEquals("environment-fallback-key", store.resolve(
                scope, targetId, "API_KEY", "FINBOT_AI_PROVIDER_KEYS_JSON").orElseThrow());
        var jdbc = JdbcClient.create(dataSource);
        assertEquals(3L, jdbc.sql("""
                select count(*) from runtime_secret_audit
                where scope_type = 'AI_PROVIDER' and target_id = 'provider_grok_sub2api'
                """).query(Long.class).single());
        assertEquals(0L, jdbc.sql("""
                select count(*) from runtime_secret_audit
                where cast(fingerprint as text) like '%database-hot-key%'
                """).query(Long.class).single());
        assertEquals("exchange-fallback-key", store.resolve(
                RuntimeSecretScope.EXCHANGE_ACCOUNT,
                "account_gate_testnet_default",
                "API_KEY",
                "FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON").orElseThrow());
        assertEquals("exchange-fallback-secret", store.resolve(
                RuntimeSecretScope.EXCHANGE_ACCOUNT,
                "account_gate_testnet_default",
                "API_SECRET",
                "FINBOT_EXCHANGE_ACCOUNT_CREDENTIALS_JSON").orElseThrow());
        assertEquals("source-fallback-key", store.resolve(
                RuntimeSecretScope.INFORMATION_SOURCE,
                "source_test",
                "API_KEY",
                "FINBOT_INFORMATION_SOURCE_KEYS_JSON").orElseThrow());
        assertEquals("http://proxy.example:8080", store.resolve(
                RuntimeSecretScope.PROXY_ROUTE,
                "FIRECRAWL",
                "PROXY_URL",
                "FINBOT_PROXY_ROUTE_URLS_JSON").orElseThrow());
        assertEquals("http://proxy.example:8080", store.resolve(
                RuntimeSecretScope.PROXY_ROUTE,
                "WEB_CRAWL",
                "PROXY_URL",
                "FINBOT_PROXY_ROUTE_URLS_JSON").orElseThrow());
        assertEquals("https://subscription.example/list", store.resolve(
                RuntimeSecretScope.PROXY_GATEWAY,
                "proxygateway_firecrawl",
                "SUBSCRIPTION_URL",
                "FINBOT_PROXY_GATEWAY_SECRETS_JSON").orElseThrow());
    }

    @Test
    void persistsOnlyApiTokenDigestAndEnforcesExpiryAndRevocation() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        var store = new JdbcAdminApiTokenStore(jdbcClient);
        var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var rawToken = "finbot_pat_" + "A".repeat(43);
        var digest = new SecureAuthenticationCryptography().digest(rawToken);
        var createdAt = Instant.parse("2026-07-21T08:00:00Z");
        var token = new AdminApiToken(
                new AdminApiTokenId("apitoken_runtime_" + suffix),
                "Runtime token " + suffix,
                digest.substring(0, 16),
                "admin",
                createdAt.plus(Duration.ofDays(90)),
                null,
                null,
                createdAt,
                createdAt,
                0);

        store.createToken(token, digest);

        assertEquals(1, store.countActiveTokens(createdAt.plusSeconds(1)));
        assertEquals(token.tokenId(), store.findActiveToken(digest, createdAt.plusSeconds(1))
                .orElseThrow().tokenId());
        assertEquals(digest, jdbcClient.sql("""
                select token_digest from admin_api_token where token_id = :tokenId
                """).param("tokenId", token.tokenId().value()).query(String.class).single());
        assertEquals(0L, jdbcClient.sql("""
                select count(*) from admin_api_token
                where token_digest = :rawToken or cast(token_digest as text) like :rawPattern
                """)
                .param("rawToken", rawToken)
                .param("rawPattern", "%" + rawToken + "%")
                .query(Long.class)
                .single());

        var usedAt = createdAt.plusSeconds(60);
        store.touchToken(digest, usedAt);
        assertEquals(usedAt, store.listTokens().stream()
                .filter(candidate -> candidate.tokenId().equals(token.tokenId()))
                .findFirst()
                .orElseThrow()
                .lastUsedAt());

        var revokedAt = createdAt.plusSeconds(120);
        var revoked = store.revokeToken(token.tokenId(), 0, revokedAt).orElseThrow();
        assertEquals(revokedAt, revoked.revokedAt());
        assertEquals(1, revoked.version());
        assertEquals(0, store.countActiveTokens(revokedAt.plusSeconds(1)));
        assertTrue(store.findActiveToken(digest, revokedAt.plusSeconds(1)).isEmpty());
    }

    @Test
    void resolvesProviderAndModelCapabilitiesWithoutVendorSpecificApplicationBranches() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var resolver = new JdbcAiRuntimeProfileResolver(
                JdbcClient.create(dataSource),
                new EmptyRuntimeSecretStore(),
                ignored -> java.util.Optional.empty());

        var resolved = resolver.resolve(new AiModelBinding(
                new AiProviderProfileId("provider_grok_sub2api"),
                "grok-4.5",
                ReasoningEffort.XHIGH));

        assertEquals(AiProtocol.RESPONSES, resolved.protocol());
        assertEquals(ReasoningEffort.XHIGH, resolved.maximumReasoningEffort());
        assertEquals(Duration.ofMinutes(30), resolved.capacityWaitTimeout());
        assertThrows(AiProviderUnavailableException.class, () -> resolver.resolve(new AiModelBinding(
                new AiProviderProfileId("provider_grok_sub2api"),
                "grok-4.5",
                ReasoningEffort.MAX)));
    }

    @Test
    void createsModelsAndSoftDeletesOnlyUnreferencedAiProviders() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        var repository = new JdbcConfigurationRepository(jdbcClient);
        var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var providerId = "provider_runtime_" + suffix;
        var now = Instant.parse("2026-07-16T09:00:00Z");
        var provider = new AiProviderProfile(
                providerId,
                "Runtime provider " + suffix,
                AiProtocol.RESPONSES,
                ReasoningParameterStyle.NESTED,
                "https://provider.example/v1",
                null,
                "FINBOT_AI_PROVIDER_KEYS_JSON",
                true,
                10,
                1800,
                5,
                3600,
                0,
                now);
        var primaryModel = new AiModelProfile(
                "model_runtime_primary_" + suffix,
                providerId,
                "provider-test-primary-" + suffix,
                ReasoningEffort.MAX,
                ReasoningEffort.MAX,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                0,
                now);

        assertTrue(repository.createProvider(provider, now).isPresent());
        var persistedProvider = repository.listProviders().stream()
                .filter(candidate -> candidate.profileId().equals(providerId))
                .findFirst()
                .orElseThrow();
        assertEquals(5, persistedProvider.maximumConcurrentRequests());
        assertEquals(3600, persistedProvider.acquireTimeoutSeconds());
        assertTrue(repository.createModel(primaryModel, now).isPresent());
        assertTrue(repository.createModel(new AiModelProfile(
                "model_runtime_secondary_" + suffix,
                providerId,
                "provider-test-secondary-" + suffix,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                true,
                0,
                now), now).isPresent());
        assertFalse(repository.providerUsages().get(providerId).inUse());
        assertTrue(repository.archiveProvider(providerId, 0, now.plusSeconds(60)));
        assertFalse(repository.listProviders().stream()
                .anyMatch(candidate -> candidate.profileId().equals(providerId)));
        assertFalse(repository.listModels().stream()
                .anyMatch(candidate -> candidate.providerProfileId().equals(providerId)));
        assertEquals(1L, jdbcClient.sql("""
                select count(*) from ai_provider_profile
                where profile_id = :profileId and deleted_at is not null and enabled = false
                """).param("profileId", providerId).query(Long.class).single());

        var usages = repository.providerUsages();
        var referenced = repository.listProviders().stream()
                .filter(candidate -> usages.getOrDefault(
                        candidate.profileId(),
                        io.omnnu.finbot.application.configuration.dto.AiProviderUsage.NONE).inUse())
                .findFirst()
                .orElseThrow();
        assertFalse(repository.archiveProvider(
                referenced.profileId(), referenced.version(), now.plusSeconds(120)));
    }

    private static final PostgresTestDatabase POSTGRES = new PostgresTestDatabase(
            "postgres:17.5-alpine",
            "finbot",
            "finbot",
            "finbot-test");

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void catalogSyncReusesPersistedInstrumentIdentityForQuoteFacts() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcTemplate = new JdbcTemplate(dataSource);
        var jdbcClient = JdbcClient.create(dataSource);
        var scope = new CatalogSyncScope(ExchangeVenue.GATE, MarketType.SPOT);
        var now = Instant.parse("2026-07-15T09:45:00Z");
        var productId = "product_catalog_identity_test";
        var persistedInstrumentId = "instrument_legacy_catalog_identity_test";
        var syncRunId = "catalogsync_identity_regression";

        var store = new JdbcProductCatalogSyncStore(jdbcClient, jdbcTemplate);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(transaction -> {
            jdbcTemplate.update("""
                    insert into canonical_product (
                      product_id, base_asset, quote_asset, display_name,
                      category, status, created_at, updated_at
                    ) values (?, 'ZID', 'USDT', 'ZID/USDT', 'CRYPTO', 'ACTIVE', ?, ?)
                    """, productId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            jdbcTemplate.update("""
                    insert into venue_instrument (
                      instrument_id, product_id, exchange, market_type, symbol,
                      settlement_asset, contract_size, price_tick, quantity_step,
                      minimum_quantity, maximum_leverage, execution_enabled, status,
                      metadata_updated_at, created_at, updated_at
                    ) values (?, ?, 'GATE', 'SPOT', 'ZID_USDT', 'USDT',
                      1, 0.01, 0.1, 0.1, 500, false, 'ACTIVE', ?, ?, ?)
                    """, persistedInstrumentId, productId,
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
            store.start(syncRunId, scope, now);
            store.complete(syncRunId, scope, List.of(new CatalogInstrumentSnapshot(
                        "ZID", "USDT", "ZID_USDT", "USDT",
                        BigDecimal.ONE, new BigDecimal("0.01"), new BigDecimal("0.1"),
                        new BigDecimal("0.1"), new BigDecimal("500"), CatalogStatus.ACTIVE,
                        new BigDecimal("123.45"), now)), now);

            assertEquals(persistedInstrumentId, jdbcTemplate.queryForObject(
                    "select instrument_id from instrument_quote_snapshot where instrument_id = ?",
                    String.class,
                    persistedInstrumentId));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "select count(*) from venue_instrument where exchange = 'GATE' and market_type = 'SPOT' and symbol = 'ZID_USDT'",
                    Integer.class));
            transaction.setRollbackOnly();
        });
    }

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
                            'normalized_document', 'research_artifact', 'ai_compression',
                            'evidence_ai_review'
                            , 'market_candle_fact', 'market_data_artifact',
                            'quant_research_run', 'quant_research_event', 'quant_metric_fact'
                            , 'trade_automation_run', 'trade_execution_ai_stage',
                            'trade_execution_ai_review', 'risk_policy', 'risk_assessment',
                            'exchange_submission_attempt', 'estimated_trade_projection'
                            , 'research_feedback', 'setup_profile_application', 'ai_experiment'
                            , 'network_diagnostic_batch', 'network_diagnostic_run'
                            , 'runtime_secret_override', 'runtime_secret_audit'
                            , 'proxy_gateway_profile'
                            , 'product_catalog_sync_run', 'instrument_quote_snapshot'
                            , 'research_market_scope', 'research_forecast'
                            , 'legacy_import_manifest'
                            , 'legacy_import_table', 'legacy_archive_row'
                            , 'research_case', 'research_segment', 'workflow_evidence_binding'
                          )
                        """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(80, result.getInt(1));
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
                          (select operation from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_type = 'QUANT') as published_quant_operation,
                          (select count(*) from information_source where deleted_at is null)
                            as source_count,
                          (select count(*) from information_source
                           where enabled = true and deleted_at is null) as enabled_source_count,
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
                          (select default_reasoning_effort from ai_model_profile
                           where model_name = 'mimo-v2.5-pro') as mimo_effort,
                          (select default_reasoning_effort from ai_model_profile
                           where model_name = 'gemini-3.5-flash') as gemini_effort,
                          (select default_reasoning_effort from ai_model_profile
                           where model_name = 'grok-4.5') as grok_effort,
                          (select count(*) from ai_provider_profile
                           where profile_id in ('provider_gemini_default', 'provider_grok_sub2api')
                             and enabled = true) as new_ai_provider_count,
                          (select display_name from ai_provider_profile
                           where profile_id = 'provider_grok_sub2api') as grok_provider_name,
                          (select display_name from ai_provider_profile
                           where profile_id = 'provider_gemini_default') as gemini_provider_name,
                          (select enabled from ai_provider_profile
                           where profile_id = 'provider_deepseek_default') as deepseek_enabled,
                          (select base_url from ai_provider_profile
                           where profile_id = 'provider_mimo_default') as mimo_base_url,
                          (select count(*) from ai_provider_profile
                           where profile_id in (
                             'provider_sub2api_default',
                             'provider_grok_sub2api',
                             'provider_gemini_default'
                           )
                             and base_url = 'https://sub2api-direct.mnnu.eu.org/v1'
                             and base_url_env is null) as sub2api_direct_endpoint_count,
                          (select provider_profile_id from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_id = 'node_bull_analyst') as bull_provider,
                          (select fallback_provider_profile_id from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_id = 'node_bull_analyst') as bull_fallback_provider,
                          (select fallback_model_name from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_id = 'node_chair_arbiter') as chair_fallback_model,
                          (select count(*) from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_type = 'AI_CLEANER') as ai_cleaner_count,
                          (select count(*) from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_type = 'COMPRESSOR') as compressor_count,
                          (select count(*) from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_type = 'COMPRESSION_VALIDATOR') as compression_validator_count,
                          (select count(*) from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_type in ('AI_CLEANER', 'COMPRESSOR', 'COMPRESSION_VALIDATOR',
                                               'AGENT', 'CHAIR', 'EXECUTION_REVIEW')
                             and (provider_profile_id in ('provider_gemini_default', 'provider_mimo_default')
                                  or fallback_provider_profile_id in ('provider_gemini_default', 'provider_mimo_default')))
                            as unavailable_default_binding_count,
                          (select count(*) from (
                             select role_template_id
                             from workflow_node_definition
                             where version_id = 'workflowversion_standard_v8'
                               and node_type = 'AGENT'
                             group by role_template_id
                             having count(*) = 2
                           ) grouped_roles) as two_seat_role_count,
                          (select count(*) from (
                             select role_template_id
                             from workflow_node_definition
                             where version_id = 'workflowversion_standard_v8'
                               and node_type = 'AI_CLEANER'
                             group by role_template_id
                             having count(*) = 2 and count(distinct provider_profile_id) = 2
                           ) grouped_roles) as cleaner_two_seat_role_count,
                          (select count(*) from (
                             select role_template_id
                             from workflow_node_definition
                             where version_id = 'workflowversion_standard_v8'
                               and node_type = 'COMPRESSOR'
                             group by role_template_id
                             having count(*) = 2 and count(distinct provider_profile_id) = 2
                           ) grouped_roles) as compressor_two_seat_role_count,
                          (select count(*) from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_type = 'COMPRESSION_VALIDATOR'
                             and role_template_id = 'role_compression_validator')
                            as validator_role_count,
                          (select count(*) from workflow_node_definition
                           where version_id = 'workflowversion_standard_v8'
                             and node_type in ('AI_CLEANER', 'COMPRESSOR', 'COMPRESSION_VALIDATOR')
                             and system_prompt like '%事实抽取%'
                             and system_prompt not like '%候选摘要%'
                             and user_prompt_template not like '%最终摘要%')
                            as fact_extraction_prompt_count,
                          (select count(*) from information_schema.columns
                           where table_schema = 'public'
                             and table_name = 'ai_model_profile'
                             and column_name = 'maximum_reasoning_effort') as model_capability_column_count,
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
                          (select source_mode from information_source
                           where source_id = 'source_x_market_search') as x_mode,
                          (select enabled from information_source
                           where source_id = 'source_x_market_search') as x_enabled,
                          (select enabled from proxy_gateway_profile
                           where gateway_id = 'proxygateway_firecrawl')
                            as firecrawl_gateway_enabled,
                          (select count(*) from information_source
                           where source_mode in (
                             'FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH',
                             'FIRECRAWL_SEARCH_THEN_SCRAPE'
                           ) and enabled = true and deleted_at is null)
                            as enabled_firecrawl_source_count,
                          (select count(*) from trade_execution_ai_stage
                           where version = 2
                             and user_prompt_template like '%UNSPECIFIED%')
                            as execution_contract_stage_count,
                          (select count(*) from information_schema.columns
                           where table_schema = 'public'
                             and table_name = 'estimated_trade_projection'
                             and column_name in (
                               'quantity', 'entry_reference', 'target_price', 'stop_price',
                               'leverage', 'initial_margin_usdt', 'estimated_profit_usdt',
                               'estimated_loss_usdt', 'risk_reward_ratio'
                             )) as projection_value_column_count,
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
                          , (select value_text from system_setting
                             where setting_key = 'ai.max_cost_usd_per_run') as default_ai_cost_limit
                          , (select count(*) from information_schema.columns
                             where table_schema = 'public'
                               and table_name = 'workflow_run'
                               and column_name in (
                                 'requested_workflow_version_id', 'ai_experiment_id',
                                 'ai_experiment_variant'
                               )) as experiment_assignment_column_count
                          , (select count(*) from information_schema.columns
                             where table_schema = 'public'
                               and table_name = 'network_diagnostic_run'
                               and column_name = 'batch_idempotency_key') as network_idempotency_column_count
                          , (select count(*) from information_schema.views
                             where table_schema = 'public'
                               and table_name = 'trading_activity_projection') as activity_view_count
                          , (select catalog_version from information_source_catalog_manifest
                             where catalog_id = 'catalog_default_sources'
                             order by created_at desc, catalog_version desc limit 1) as source_catalog_version
                          , (select manifest_hash from information_source_catalog_manifest
                             where catalog_id = 'catalog_default_sources'
                             order by created_at desc, catalog_version desc limit 1) as source_catalog_hash
                          , (select source_count from information_source_catalog_manifest
                             where catalog_id = 'catalog_default_sources'
                             order by created_at desc, catalog_version desc limit 1) as source_catalog_manifest_count
                          , (select count(*) from information_source_catalog_manifest
                             where catalog_id = 'catalog_default_sources') as source_catalog_history_count
                          , (select count(*) from information_source
                             where source_id in (
                               'source_sec_edgar','source_world_bank_macro','source_bls_labor',
                               'source_cftc_cot','source_fred_macro'
                             )) as free_structured_source_count
                          , (select count(*) from information_source
                             where source_id = 'source_sec_edgar'
                               and source_mode = 'RSS' and proxy_route_type = 'WEB_CRAWL'
                               and enabled = true) as sec_source_ready
                          , (select count(*) from information_source
                             where source_id = 'source_global_search'
                               and source_mode = 'SEARCH_DISCOVERY' and provider = 'gdelt'
                               and proxy_route_type = 'WEB_CRAWL' and enabled = true) as gdelt_source_ready
                          , (select count(*) from information_source
                             where source_id = 'source_cftc_cot'
                               and endpoint_base_url like '%/resource/gpe5-46if.json%24limit%'
                               and endpoint_base_url like '%24order%') as cftc_source_ready
                          , (select count(*) from information_source
                             where source_id = 'source_fred_macro'
                               and credential_env = 'FINBOT_INFORMATION_SOURCE_KEYS_JSON'
                               and enabled = false) as fred_key_bound_disabled
                          , (select count(*) from information_schema.columns
                             where table_schema = 'public'
                               and table_name = 'source_fetch_attempt'
                               and column_name = 'redirect_count') as fetch_redirect_column_count
                          , (select count(*) from information_source_ai_web_search)
                            as ai_web_search_binding_count
                          , (select count(*) from information_source
                             where source_mode = 'AI_WEB_SEARCH' and enabled = false
                               and deleted_at is null) as disabled_ai_web_search_count
                          , (select count(*) from information_source
                             where provider = 'searxng_internal' and enabled = true
                               and deleted_at is null) as searxng_source_count
                          , (select count(*) from information_source
                             where provider = 'searxng_internal'
                               and endpoint_base_url like '%engine_shortcuts=%'
                               and deleted_at is null) as searxng_shortcut_source_count
                          , (select count(*) from information_source
                             where provider = 'searxng_internal'
                               and endpoint_base_url like '%&engines=%'
                               and deleted_at is null) as searxng_legacy_engine_source_count
                          , (select count(*) from information_source
                             where source_id in (
                               'source_reuters_search','source_ap_search','source_searxng_news_search'
                             ) and endpoint_base_url =
                               'http://finbot-searxng:8080/search?categories=news&language=en&engine_shortcuts=bi%2Cddg%2Cgon%2Cbin%2Cddn'
                               and deleted_at is null) as searxng_news_shortcut_mapping_count
                          , (select count(*) from information_source
                             where source_id = 'source_searxng_global_search'
                               and endpoint_base_url =
                               'http://finbot-searxng:8080/search?categories=general&language=auto&engine_shortcuts=go%2Cbi%2Cddg%2Cbr%2Cqw%2Csp%2Cyh'
                               and deleted_at is null) as searxng_global_shortcut_mapping_count
                          , (select count(*) from information_source
                             where source_id in (
                               'source_searxng_cn_mainstream','source_searxng_cn_finance'
                             ) and endpoint_base_url =
                               'http://finbot-searxng:8080/search?categories=general%2Cnews&language=zh-CN&engine_shortcuts=bi%2Cddg%2Cbd%2C360so%2Csogou%2Csogouw'
                               and deleted_at is null) as searxng_china_shortcut_mapping_count
                          , (select count(*) from information_source
                             where category in ('broad_news','finance_news','crypto_news','asia_news')
                               and enabled = true and deleted_at is null) as international_news_source_count
                          , (select count(*) from information_source
                             where source_id in (
                               'source_chinanews_cn','source_people_cn','source_sina_cn','source_ifeng_cn',
                               'source_36kr_cn','source_ithome_cn','source_oschina_cn','source_sspai_cn',
                               'source_cnbeta_cn','source_searxng_cn_mainstream','source_searxng_cn_finance'
                             ) and enabled = true and deleted_at is null) as china_news_source_count
                          , (select count(*) from information_source
                             where category = 'exchange_announcements'
                               and enabled = true and deleted_at is null) as exchange_news_source_count
                          , (select provider from information_source
                             where source_id = 'source_gate_announcements') as gate_source_provider
                          , (select count(*) from information_schema.tables
                             where table_schema = 'public'
                               and table_name in (
                                 'information_source_ai_web_search','ai_web_search_invocation'
                               )) as ai_web_search_table_count
                           , (select count(*) from information_source
                              where source_id = 'source_searxng_public_pool'
                                and source_mode = 'SEARCH_DISCOVERY'
                                and source_tier = 'T4'
                                and category = 'broad_news_discovery'
                                and provider = 'searxng_public_pool'
                                and trust_weight = 0.42
                                and poll_interval_seconds = 21600
                                and priority = 'P3'
                                and endpoint_base_url = 'https://searx.space/data/instances.json'
                                and credential_env is null
                                and proxy_route_type = 'WEB_CRAWL'
                                and maximum_results = 20
                                and maximum_scrape_targets = 0
                                and enabled = true
                                and deleted_at is null) as public_searxng_source_ready
                        """)) {
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(15, result.getInt("setting_count"));
                assertEquals(10, result.getInt("product_count"));
                assertEquals(2, result.getInt("account_count"));
                assertEquals(10, result.getInt("schedule_count"));
                assertEquals(9, result.getInt("role_count"));
                assertEquals(133, result.getInt("node_count"));
                assertEquals(23, result.getInt("published_node_count"));
                assertEquals(8, result.getInt("workflow_version_count"));
                assertEquals("workflowversion_standard_v8", result.getString("published_version_id"));
                assertEquals("multi_strategy_ensemble", result.getString("published_quant_operation"));
                assertEquals(62, result.getInt("source_count"));
                assertEquals(57, result.getInt("enabled_source_count"));
                assertEquals(5, result.getInt("proxy_route_count"));
                assertEquals(5, result.getInt("watchlist_item_count"));
                assertFalse(result.getBoolean("gate_proxy_required"));
                assertTrue(result.getBoolean("gate_direct_allowed"));
                assertFalse(result.getBoolean("bybit_proxy_required"));
                assertTrue(result.getBoolean("bybit_direct_allowed"));
                assertEquals("MAX", result.getString("sol_effort"));
                assertEquals("MAX", result.getString("mimo_effort"));
                assertEquals("MAX", result.getString("gemini_effort"));
                assertEquals("XHIGH", result.getString("grok_effort"));
                assertEquals(2, result.getInt("new_ai_provider_count"));
                assertEquals("sub2api-grok", result.getString("grok_provider_name"));
                assertEquals("sub2api-gemini", result.getString("gemini_provider_name"));
                assertFalse(result.getBoolean("deepseek_enabled"));
                assertEquals(
                        "https://mimo2api-direct.mnnu.eu.org/v1",
                        result.getString("mimo_base_url"));
                assertEquals(3, result.getInt("sub2api_direct_endpoint_count"));
                assertEquals("provider_grok_sub2api", result.getString("bull_provider"));
                assertEquals("provider_sub2api_default", result.getString("bull_fallback_provider"));
                assertEquals("gpt-5.6-terra", result.getString("chair_fallback_model"));
                assertEquals(2, result.getInt("ai_cleaner_count"));
                assertEquals(2, result.getInt("compressor_count"));
                assertEquals(1, result.getInt("compression_validator_count"));
                assertEquals(0, result.getInt("unavailable_default_binding_count"));
                assertEquals(5, result.getInt("two_seat_role_count"));
                assertEquals(1, result.getInt("cleaner_two_seat_role_count"));
                assertEquals(1, result.getInt("compressor_two_seat_role_count"));
                assertEquals(1, result.getInt("validator_role_count"));
                assertEquals(5, result.getInt("fact_extraction_prompt_count"));
                assertEquals(1, result.getInt("model_capability_column_count"));
                assertEquals(0, new java.math.BigDecimal("20")
                        .compareTo(result.getBigDecimal("preferred_leverage")));
                assertEquals(0, new java.math.BigDecimal("20")
                        .compareTo(result.getBigDecimal("maximum_leverage")));
                assertTrue(result.getBoolean("workflow_active"));
                assertEquals(7, result.getInt("research_only_instrument_count"));
                assertEquals("WEB_CRAWL", result.getString("x_route"));
                assertEquals("SEARCH_DISCOVERY", result.getString("x_mode"));
                assertFalse(result.getBoolean("x_enabled"));
                assertFalse(result.getBoolean("firecrawl_gateway_enabled"));
                assertEquals(0, result.getInt("enabled_firecrawl_source_count"));
                assertEquals(2, result.getInt("execution_contract_stage_count"));
                assertEquals(9, result.getInt("projection_value_column_count"));
                assertEquals(6, result.getInt("new_control_column_count"));
                assertEquals("25.00", result.getString("default_ai_cost_limit"));
                assertEquals(3, result.getInt("experiment_assignment_column_count"));
                assertEquals(1, result.getInt("network_idempotency_column_count"));
                assertEquals(1, result.getInt("activity_view_count"));
                assertEquals("v4", result.getString("source_catalog_version"));
                assertEquals(
                        "24a5f8c50a624f60789b1c8dbe17bac0048017695b931750e6a4e5251276ed46",
                        result.getString("source_catalog_hash"));
                assertEquals(62, result.getInt("source_catalog_manifest_count"));
                assertEquals(4, result.getInt("source_catalog_history_count"));
                assertEquals(5, result.getInt("free_structured_source_count"));
                assertEquals(1, result.getInt("sec_source_ready"));
                assertEquals(1, result.getInt("gdelt_source_ready"));
                assertEquals(1, result.getInt("cftc_source_ready"));
                assertEquals(1, result.getInt("fred_key_bound_disabled"));
                assertEquals(1, result.getInt("fetch_redirect_column_count"));
                assertEquals(2, result.getInt("ai_web_search_binding_count"));
                assertEquals(2, result.getInt("disabled_ai_web_search_count"));
                assertEquals(6, result.getInt("searxng_source_count"));
                assertEquals(6, result.getInt("searxng_shortcut_source_count"));
                assertEquals(0, result.getInt("searxng_legacy_engine_source_count"));
                assertEquals(3, result.getInt("searxng_news_shortcut_mapping_count"));
                assertEquals(1, result.getInt("searxng_global_shortcut_mapping_count"));
                assertEquals(2, result.getInt("searxng_china_shortcut_mapping_count"));
                assertEquals(12, result.getInt("international_news_source_count"));
                assertEquals(11, result.getInt("china_news_source_count"));
                assertEquals(8, result.getInt("exchange_news_source_count"));
                assertEquals("gate", result.getString("gate_source_provider"));
                assertEquals(2, result.getInt("ai_web_search_table_count"));
                assertEquals(1, result.getInt("public_searxng_source_ready"));
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
                    assertEquals(62, result.getInt("changeset_count"));
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
    void claimsOnlyAllowedTaskTypesAndRecoversExpiredLease() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        var store = new JdbcBackgroundTaskStore(jdbcClient, new TaskPayloadCodec(new ObjectMapper()));
        var now = Instant.parse("2026-07-16T02:00:00Z");
        jdbcClient.sql("""
                insert into background_task (
                  task_id, task_type, status, priority, idempotency_key, payload,
                  attempt_count, maximum_attempts, available_at, created_at, updated_at
                ) values
                  ('task_allowed_account', 'ACCOUNT_SYNC', 'PENDING', 50, 'test:allowed:account',
                   '{"accountId":"account_bybit_demo_default"}'::jsonb, 0, 3, :now, :now, :now),
                  ('task_blocked_research', 'SCHEDULED_RESEARCH', 'PENDING', 100, 'test:blocked:research',
                   '{"requestSummary":"capacity filter integration test"}'::jsonb, 0, 3, :now, :now, :now)
                on conflict (idempotency_key) do nothing
                """)
                .param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .update();
        var workerId = new WorkerId("worker_allowed_type_test");

        var claimed = store.claimNext(
                        workerId,
                        now,
                        Duration.ofSeconds(30),
                        Set.of(BackgroundTaskType.ACCOUNT_SYNC))
                .orElseThrow();

        assertEquals("task_allowed_account", claimed.taskId().value());
        assertEquals(BackgroundTaskType.ACCOUNT_SYNC, claimed.taskType());
        assertTrue(store.recoverExpiredLeases(now.plusSeconds(31)) >= 1);
        var recovered = store.find(claimed.taskId()).orElseThrow();
        assertEquals(BackgroundTaskStatus.PENDING, recovered.status());
        assertEquals("LEASE_EXPIRED", recovered.errorCode());
    }

    @Test
    void doesNotMaterializeAnotherScheduledTaskWhileThePreviousRunIsActive() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        var store = new JdbcBackgroundTaskStore(jdbcClient, new TaskPayloadCodec(new ObjectMapper()));
        var now = Instant.parse("2020-01-01T00:00:00Z");
        var scheduleId = "schedule_task_coalesce_test";
        var taskSequence = new AtomicInteger();
        try {
            jdbcClient.sql("""
                    insert into schedule_definition (
                      schedule_id, display_name, task_type, payload, enabled, interval_seconds,
                      priority, maximum_attempts, next_run_at
                    ) values (
                      :scheduleId, '任务重叠抑制集成测试', 'ACCOUNT_SYNC',
                      '{"accountId":"account_bybit_demo_default"}'::jsonb,
                      true, 60, 80, 5, :now
                    )
                    """)
                    .param("scheduleId", scheduleId)
                    .param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                    .update();

            assertEquals(1, store.materializeDueSchedules(
                    now,
                    10,
                    () -> new BackgroundTaskId("task_schedule_coalesce_" + taskSequence.incrementAndGet())));
            jdbcClient.sql("""
                    update schedule_definition
                    set next_run_at = :now, updated_at = :now
                    where schedule_id = :scheduleId
                    """)
                    .param("scheduleId", scheduleId)
                    .param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                    .update();

            assertEquals(0, store.materializeDueSchedules(
                    now,
                    10,
                    () -> new BackgroundTaskId("task_schedule_coalesce_" + taskSequence.incrementAndGet())));
            assertEquals(1, jdbcClient.sql("""
                            select count(*)
                            from background_task
                            where status in ('PENDING', 'CLAIMED')
                              and left(idempotency_key, length(:scheduleKeyPrefix)) = :scheduleKeyPrefix
                            """)
                    .param("scheduleKeyPrefix", "schedule:" + scheduleId + ":")
                    .query(Integer.class)
                    .single());
        } finally {
            jdbcClient.sql("""
                    delete from background_task
                    where left(idempotency_key, length(:scheduleKeyPrefix)) = :scheduleKeyPrefix
                    """)
                    .param("scheduleKeyPrefix", "schedule:" + scheduleId + ":")
                    .update();
            jdbcClient.sql("delete from schedule_definition where schedule_id = :scheduleId")
                    .param("scheduleId", scheduleId)
                    .update();
        }
    }

    @Test
    void allowsOnlyOneWorkerToClaimTheSameTaskConcurrently() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        var store = new JdbcBackgroundTaskStore(jdbcClient, new TaskPayloadCodec(new ObjectMapper()));
        var now = Instant.parse("2026-07-16T02:30:00Z");
        jdbcClient.sql("""
                insert into background_task (
                  task_id, task_type, status, priority, idempotency_key, payload,
                  attempt_count, maximum_attempts, available_at, created_at, updated_at
                ) values (
                  'task_concurrent_claim', 'FORECAST_EVALUATION', 'PENDING', 100, 'test:concurrent:claim',
                  '{"limit":50}'::jsonb, 0, 3, :now, :now, :now
                ) on conflict (idempotency_key) do nothing
                """)
                .param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .update();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var claims = List.of(
                    new WorkerId("worker_concurrent_a"),
                    new WorkerId("worker_concurrent_b"))
                    .stream()
                    .map(workerId -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        return store.claimNext(
                                workerId,
                                now,
                                Duration.ofSeconds(30),
                                Set.of(BackgroundTaskType.FORECAST_EVALUATION));
                    }))
                    .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            var successfulClaims = 0;
            for (var claim : claims) {
                if (claim.get(5, TimeUnit.SECONDS).isPresent()) {
                    successfulClaims++;
                }
            }
            assertEquals(1, successfulClaims);
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

    @Test
    void updatesInformationSourceWithOptimisticVersion() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var repository = new JdbcIngestionRepository(
                JdbcClient.create(dataSource), new ObjectMapper());
        var sourceId = new SourceId("source_x_market_search");
        var current = repository.findSource(sourceId).orElseThrow();

        var updated = repository.setSourceEnabled(
                        sourceId,
                        !current.enabled(),
                        current.version(),
                        Instant.parse("2026-07-15T01:00:00Z"))
                .orElseThrow();

        assertEquals(current.version() + 1, updated.version());
        assertEquals(!current.enabled(), updated.enabled());
        assertTrue(repository.setSourceEnabled(
                        sourceId,
                        current.enabled(),
                        current.version(),
                        Instant.parse("2026-07-15T01:01:00Z"))
                .isEmpty());
    }

    @Test
    void managesInformationSourceWithoutDeletingHistoricalCollection() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        var repository = new JdbcIngestionRepository(jdbcClient, new ObjectMapper());
        var sourceId = new SourceId("source_managed_integration01");
        var source = new InformationSource(
                sourceId,
                "Managed integration source",
                SourceMode.RSS,
                SourceTier.T2,
                "market_news",
                "integration",
                new BigDecimal("0.75"),
                900,
                SourcePriority.P2,
                List.of("BTCUSDT"),
                List.of(java.net.URI.create("https://example.com/feed.xml")),
                List.of(),
                List.of(),
                null,
                "FINBOT_INFORMATION_SOURCE_KEYS_JSON",
                OutboundRoute.PUBLIC_DATA,
                10,
                0,
                true,
                0);
        var createdAt = Instant.parse("2026-07-16T14:00:00Z");
        var created = repository.createSource(source, createdAt).orElseThrow();
        var updatedSource = new InformationSource(
                created.sourceId(),
                "Managed integration source updated",
                created.mode(),
                created.tier(),
                created.category(),
                created.provider(),
                created.trustWeight(),
                created.pollIntervalSeconds(),
                created.priority(),
                created.assetScope(),
                created.feedUrls(),
                created.seedUrls(),
                created.searchQueries(),
                created.endpointBaseUrl(),
                created.credentialEnvironmentVariable(),
                created.outboundRoute(),
                created.maximumResults(),
                created.maximumScrapeTargets(),
                created.enabled(),
                created.version());
        var updated = repository.updateSource(
                        updatedSource,
                        created.version(),
                        createdAt.plusSeconds(1))
                .orElseThrow();
        assertEquals(created.version() + 1, updated.version());

        var collectionId = new CollectionRunId("collection_managed_integration01");
        repository.startCollection(new SourceCollectionRun(
                collectionId,
                null,
                sourceId,
                "integration probe",
                CollectionStatus.RUNNING,
                0,
                0,
                0,
                null,
                null,
                createdAt.plusSeconds(2),
                null));
        repository.finishCollection(
                collectionId,
                CollectionStatus.COMPLETED,
                0,
                0,
                0,
                null,
                null,
                createdAt.plusSeconds(3));
        jdbcClient.sql("""
                insert into runtime_secret_override (
                  scope_type, target_id, secret_name, version, updated_at
                ) values ('INFORMATION_SOURCE', :sourceId, 'API_KEY', 1, :updatedAt)
                """)
                .param("sourceId", sourceId.value())
                .param("updatedAt", OffsetDateTime.parse("2026-07-16T14:00:04Z"))
                .update();

        assertTrue(repository.archiveSource(
                sourceId,
                updated.version(),
                createdAt.plusSeconds(5)));

        assertTrue(repository.findSource(sourceId).isEmpty());
        assertEquals(1, jdbcClient.sql("""
                select count(*) from source_collection_run where source_id = :sourceId
                """).param("sourceId", sourceId.value()).query(Long.class).single());
        assertEquals(0, jdbcClient.sql("""
                select count(*) from runtime_secret_override where target_id = :sourceId
                """).param("sourceId", sourceId.value()).query(Long.class).single());
        assertTrue(jdbcClient.sql("""
                select deleted_at is not null from information_source where source_id = :sourceId
                """).param("sourceId", sourceId.value()).query(Boolean.class).single());
    }

    @Test
    void acceptsSingleCharacterAssetsPublishedByRealExchangeCatalogs() throws Exception {
        updateSchema();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                        insert into canonical_product (
                          product_id, base_asset, quote_asset, display_name,
                          category, status, created_at, updated_at
                        ) values (
                          'product_t_usdt_test', 'T', 'USDT', 'T / Tether',
                          'CRYPTO', 'ACTIVE', current_timestamp, current_timestamp
                        )
                        """)) {
                assertEquals(1, statement.executeUpdate());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void deduplicatesNetworkDiagnosticBatchesPerRoute() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var store = new JdbcNetworkDiagnosticStore(JdbcClient.create(dataSource));
        var startedAt = Instant.parse("2026-07-15T02:00:00Z");
        var batchKey = "network-diagnostic:test-batch";
        var claim = store.prepareBatch(
                "diagnosticbatch_network_1",
                batchKey,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                startedAt);

        var first = store.start(
                "diagnostic_network_batch_1",
                batchKey,
                OutboundRoute.FIRECRAWL,
                startedAt);
        var duplicate = store.start(
                "diagnostic_network_batch_2",
                batchKey,
                OutboundRoute.FIRECRAWL,
                startedAt.plusSeconds(1));
        var conflictingClaim = store.prepareBatch(
                "diagnosticbatch_network_2",
                batchKey,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                startedAt.plusSeconds(2));

        assertTrue(claim.created());
        assertTrue(first.created());
        assertFalse(duplicate.created());
        assertFalse(conflictingClaim.created());
        assertEquals(claim.requestFingerprint(), conflictingClaim.requestFingerprint());
        assertEquals(first.diagnostic().diagnosticId(), duplicate.diagnostic().diagnosticId());
    }

    @Test
    void appliesSetupProfileIdempotentlyWithoutOverwritingUserSettings() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var repository = new JdbcSetupProfileRepository(
                JdbcClient.create(dataSource), new ObjectMapper());
        var appliedAt = Instant.parse("2026-07-15T02:30:00Z");

        var first = repository.apply(
                "setup_profile_test_1",
                "setup-profile:test-profile",
                SetupProfileId.ECONOMY,
                Map.of("ai.max_tokens_per_run", "1000000"),
                appliedAt);
        var duplicate = repository.apply(
                "setup_profile_test_2",
                "setup-profile:test-profile",
                SetupProfileId.ECONOMY,
                Map.of("ai.max_tokens_per_run", "9999999"),
                appliedAt.plusSeconds(1));

        assertEquals(first.applicationId(), duplicate.applicationId());
        assertEquals(first.appliedAt(), duplicate.appliedAt());
    }

    @Test
    void routesScheduledWorkflowThroughStableAiExperimentAssignment() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("""
                insert into ai_experiment (
                  experiment_id, display_name, status, control_workflow_version_id,
                  candidate_workflow_version_id, candidate_allocation_basis_points,
                  evaluation_metric, minimum_sample_size, version, created_at, updated_at
                ) values (
                  'experiment_routing_test', 'Routing test', 'RUNNING',
                  'workflowversion_standard_v4', 'workflowversion_standard_v1',
                  5000, 'RESEARCH_EFFECTIVENESS', 2, 0,
                  cast('2026-07-15T03:00:00Z' as timestamptz),
                  cast('2026-07-15T03:00:00Z' as timestamptz)
                )
                """).update();
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var store = new JdbcWorkflowStore(jdbcClient, new WorkflowEventCodec(objectMapper));
        var command = new StartWorkflowCommand(
                WorkflowType.SCHEDULED_RESEARCH,
                WorkflowTrigger.SCHEDULED,
                new WorkflowVersionId("workflowversion_standard_v4"),
                "AI experiment routing integration test",
                candidateIdempotencyKey());
        var acceptedAt = Instant.parse("2026-07-15T03:01:00Z");

        var first = store.accept(command, new WorkflowAccepted(
                new WorkflowEventId("event_experiment_routing_1"),
                new WorkflowRunId("run_experiment_routing_1"),
                1,
                command.workflowType(),
                acceptedAt));
        var duplicate = store.accept(command, new WorkflowAccepted(
                new WorkflowEventId("event_experiment_routing_2"),
                new WorkflowRunId("run_experiment_routing_2"),
                1,
                command.workflowType(),
                acceptedAt.plusSeconds(1)));

        assertEquals(first.runId(), duplicate.runId());
        var assignment = jdbcClient.sql("""
                select workflow_version_id, requested_workflow_version_id,
                       ai_experiment_id, ai_experiment_variant
                from workflow_run where run_id = :runId
                """)
                .param("runId", first.runId().value())
                .query((resultSet, rowNumber) -> java.util.List.of(
                        resultSet.getString("workflow_version_id"),
                        resultSet.getString("requested_workflow_version_id"),
                        resultSet.getString("ai_experiment_id"),
                        resultSet.getString("ai_experiment_variant")))
                .single();
        assertEquals("workflowversion_standard_v1", assignment.get(0));
        assertEquals("workflowversion_standard_v4", assignment.get(1));
        assertEquals("experiment_routing_test", assignment.get(2));
        assertEquals("CANDIDATE", assignment.get(3));
    }

    @Test
    void failsOnlyOrphanedAiInvocationsDuringWorkerStartupRecovery() throws Exception {
        updateSchema();
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcClient = JdbcClient.create(dataSource);
        var suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var runId = new WorkflowRunId("run_recovery_" + suffix);
        var acceptedAt = Instant.parse("2026-07-21T11:00:00Z");
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var workflowStore = new JdbcWorkflowStore(jdbcClient, new WorkflowEventCodec(objectMapper));
        var command = new StartWorkflowCommand(
                WorkflowType.INSTANT_RESEARCH,
                WorkflowTrigger.MANUAL,
                new WorkflowVersionId("workflowversion_standard_v8"),
                "AI invocation recovery integration test",
                "ai-recovery:" + suffix);
        workflowStore.accept(command, new WorkflowAccepted(
                new WorkflowEventId("event_recovery_" + suffix),
                runId,
                1,
                command.workflowType(),
                acceptedAt));
        jdbcClient.sql("""
                insert into ai_invocation (
                  invocation_id, run_id, node_id, provider_profile_id, protocol,
                  model_name, reasoning_effort, prompt_version, request_hash,
                  status, started_at, completed_at
                ) values
                  (:orphanedId, :runId, 'node_bull_analyst', 'provider_grok_sub2api',
                   'RESPONSES', 'grok-4.5', 'XHIGH', 'test-v1', repeat('a', 64),
                   'STREAMING', :startedAt, null),
                  (:completedId, :runId, 'node_bull_analyst', 'provider_grok_sub2api',
                   'RESPONSES', 'grok-4.5', 'XHIGH', 'test-v1', repeat('b', 64),
                   'COMPLETED', :startedAt, :completedAt)
                """)
                .param("orphanedId", "invocation_orphaned_" + suffix)
                .param("completedId", "invocation_completed_" + suffix)
                .param("runId", runId.value())
                .param("startedAt", OffsetDateTime.parse("2026-07-21T11:00:10Z"))
                .param("completedAt", OffsetDateTime.parse("2026-07-21T11:00:20Z"))
                .update();
        jdbcClient.sql("""
                update workflow_run
                set reserved_tokens = 12000,
                    reserved_cost_usd = 0.75000000
                where run_id = :runId
                """)
                .param("runId", runId.value())
                .update();
        jdbcClient.sql("""
                insert into ai_budget_reservation (
                  invocation_id, run_id, reserved_tokens, reserved_cost_usd,
                  status, reserved_at
                ) values (
                  :invocationId, :runId, 12000, 0.75000000,
                  'RESERVED', :reservedAt
                )
                """)
                .param("invocationId", "invocation_orphaned_" + suffix)
                .param("runId", runId.value())
                .param("reservedAt", OffsetDateTime.parse("2026-07-21T11:00:10Z"))
                .update();

        var recovered = new JdbcAiInvocationRecoveryStore(jdbcClient)
                .failOrphanedInvocations(Instant.parse("2026-07-21T11:01:10Z"));

        assertEquals(1, recovered);
        var statuses = jdbcClient.sql("""
                select invocation_id, status, error_code, latency_milliseconds
                from ai_invocation
                where run_id = :runId
                order by invocation_id
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getString("invocation_id"),
                        resultSet.getString("status"),
                        Objects.toString(resultSet.getString("error_code"), ""),
                        Long.toString(resultSet.getLong("latency_milliseconds"))))
                .list();
        assertEquals("COMPLETED", statuses.getFirst().get(1));
        assertEquals("FAILED", statuses.get(1).get(1));
        assertEquals("WORKER_RESTART_RECOVERY", statuses.get(1).get(2));
        assertEquals("60000", statuses.get(1).get(3));
        var reservationState = jdbcClient.sql("""
                select run.reserved_tokens, run.reserved_cost_usd,
                       reservation.status, reservation.released_at is not null as released
                from workflow_run run
                join ai_budget_reservation reservation on reservation.run_id = run.run_id
                where run.run_id = :runId
                """)
                .param("runId", runId.value())
                .query((resultSet, rowNumber) -> List.of(
                        Long.toString(resultSet.getLong("reserved_tokens")),
                        resultSet.getBigDecimal("reserved_cost_usd").toPlainString(),
                        resultSet.getString("status"),
                        Boolean.toString(resultSet.getBoolean("released"))))
                .single();
        assertEquals("0", reservationState.get(0));
        assertEquals(0, new BigDecimal(reservationState.get(1)).compareTo(BigDecimal.ZERO));
        assertEquals("RELEASED", reservationState.get(2));
        assertEquals("true", reservationState.get(3));
        assertEquals(0, new JdbcAiInvocationRecoveryStore(jdbcClient)
                .failOrphanedInvocations(Instant.parse("2026-07-21T11:02:10Z")));
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

    private static String candidateIdempotencyKey() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (var index = 0; index < 10_000; index++) {
            var value = "experiment-routing:" + index;
            var hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var bucket = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(hash).getInt()) % 10_000L;
            if (bucket < 5_000) {
                return value;
            }
        }
        throw new IllegalStateException("Unable to find deterministic candidate bucket");
    }

    private static final class EmptyRuntimeSecretStore implements RuntimeSecretStore {
        @Override
        public Optional<String> resolve(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return Optional.empty();
        }

        @Override
        public RuntimeSecretStatus status(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable) {
            return new RuntimeSecretStatus(
                    scope,
                    targetId,
                    secretName,
                    RuntimeSecretSource.UNCONFIGURED,
                    false,
                    null,
                    0,
                    null);
        }

        @Override
        public Optional<RuntimeSecretStatus> put(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String value,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }

        @Override
        public Optional<RuntimeSecretStatus> clear(
                RuntimeSecretScope scope,
                String targetId,
                String secretName,
                String fallbackEnvironmentVariable,
                long expectedVersion,
                Instant updatedAt) {
            return Optional.empty();
        }
    }
}
