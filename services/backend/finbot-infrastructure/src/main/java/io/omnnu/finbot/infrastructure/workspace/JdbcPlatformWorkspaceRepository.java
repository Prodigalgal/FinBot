package io.omnnu.finbot.infrastructure.workspace;

import static io.omnnu.finbot.infrastructure.jdbc.PostgresJdbcParameters.timestamp;

import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.RuntimeSecretScope;
import io.omnnu.finbot.application.configuration.RuntimeSecretStore;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.application.workspace.IngestionWorkspace;
import io.omnnu.finbot.application.workspace.NetworkWorkspace;
import io.omnnu.finbot.application.workspace.OperationsReport;
import io.omnnu.finbot.application.workspace.PlatformReadiness;
import io.omnnu.finbot.application.workspace.PlatformWorkspaceRepository;
import io.omnnu.finbot.application.workspace.QuantWorkspace;
import io.omnnu.finbot.application.workspace.WorkflowLearning;
import io.omnnu.finbot.domain.network.OutboundRoute;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class JdbcPlatformWorkspaceRepository implements PlatformWorkspaceRepository {
    private final JdbcClient jdbcClient;
    private final EnvironmentValueResolver environment;
    private final ProxyRouteResolver proxyRoutes;
    private final RuntimeSecretStore runtimeSecrets;

    public JdbcPlatformWorkspaceRepository(
            JdbcClient jdbcClient,
            EnvironmentValueResolver environment,
            ProxyRouteResolver proxyRoutes,
            RuntimeSecretStore runtimeSecrets) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.proxyRoutes = Objects.requireNonNull(proxyRoutes, "proxyRoutes");
        this.runtimeSecrets = Objects.requireNonNull(runtimeSecrets, "runtimeSecrets");
    }

    @Override
    @Transactional(readOnly = true)
    public PlatformReadiness readiness(Instant generatedAt) {
        var checks = new ArrayList<PlatformReadiness.Check>();
        checks.add(check("DATABASE", "PostgreSQL", "READY", "业务数据库连接正常", "settings", generatedAt));
        checks.add(providerCheck(generatedAt));
        checks.add(workflowCheck(generatedAt));
        checks.add(workerCheck(generatedAt));
        checks.add(sourceCheck(generatedAt));
        checks.add(exchangeCheck(generatedAt));
        checks.add(scheduleCheck(generatedAt));
        var blocked = checks.stream().filter(value -> "BLOCKED".equals(value.status())).count();
        var readyCount = checks.stream().filter(value -> "READY".equals(value.status())).count();
        var warningCount = checks.stream().filter(value -> "WARNING".equals(value.status())).count();
        var score = Math.toIntExact(Math.round(
                (readyCount + warningCount * 0.5D) * 100D / Math.max(1, checks.size())));
        var taskCounts = jdbcClient.sql("""
                select count(*) filter (where status in ('PENDING', 'CLAIMED')) as pending_count,
                       count(*) filter (where status = 'FAILED') as failed_count
                from background_task
                """)
                .query((resultSet, rowNumber) -> new TaskCounts(
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("failed_count")))
                .single();
        return new PlatformReadiness(
                blocked == 0,
                score,
                checks,
                latestResearch(),
                accountSummary(),
                taskCounts.pending(),
                taskCounts.failed(),
                generatedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public IngestionWorkspace ingestion(int limit, Instant generatedAt) {
        var totals = jdbcClient.sql("""
                select (select count(*) from raw_evidence) as raw_count,
                       (select count(*) from normalized_document) as document_count,
                       (select count(*) from ai_compression) as compression_count,
                       (select count(*) from evidence_ai_review) as ai_review_count
                """)
                .query((resultSet, rowNumber) -> new IngestionTotals(
                        resultSet.getLong("raw_count"),
                        resultSet.getLong("document_count"),
                        resultSet.getLong("compression_count"),
                        resultSet.getLong("ai_review_count")))
                .single();
        var sources = jdbcClient.sql("""
                select source.source_id, source.display_name, source.source_mode, source.source_tier,
                       source.category, source.proxy_route_type, source.credential_env,
                       source.enabled, source.version,
                       latest.status, coalesce(latest.fetched_count, 0) as fetched_count,
                       coalesce(latest.inserted_count, 0) as inserted_count,
                       coalesce(latest.duplicate_count, 0) as duplicate_count,
                       latest.error_code, latest.error_message, latest.started_at
                from information_source source
                left join lateral (
                  select run.status, run.fetched_count, run.inserted_count, run.duplicate_count,
                         run.error_code, run.error_message, run.started_at
                  from source_collection_run run
                  where run.source_id = source.source_id
                  order by run.started_at desc, run.id desc
                  limit 1
                ) latest on true
                where source.deleted_at is null
                order by source.enabled desc, source.priority, source.source_tier, source.id
                """)
                .query((resultSet, rowNumber) -> ingestionSource(resultSet))
                .list();
        var runs = jdbcClient.sql("""
                select run.collection_id, run.workflow_run_id, run.source_id,
                       source.display_name, run.query, run.status, run.fetched_count,
                       run.inserted_count, run.duplicate_count, run.error_code,
                       run.error_message, run.started_at, run.completed_at
                from source_collection_run run
                join information_source source on source.source_id = run.source_id
                order by run.started_at desc, run.id desc
                limit :limit
                """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new IngestionWorkspace.CollectionRun(
                        resultSet.getString("collection_id"),
                        resultSet.getString("workflow_run_id"),
                        resultSet.getString("source_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("query"),
                        resultSet.getString("status"),
                        resultSet.getInt("fetched_count"),
                        resultSet.getInt("inserted_count"),
                        resultSet.getInt("duplicate_count"),
                        resultSet.getString("error_code"),
                        safe(resultSet.getString("error_message")),
                        instant(resultSet, "started_at"),
                        nullableInstant(resultSet, "completed_at")))
                .list();
        var reviews = jdbcClient.sql("""
                select review_id, workflow_run_id, document_id, node_id, stage, status,
                       content->>'summary' as summary, error_code, error_message, created_at
                from evidence_ai_review
                order by created_at desc, id desc
                limit :limit
                """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new IngestionWorkspace.EvidenceAiReviewSummary(
                        resultSet.getString("review_id"),
                        resultSet.getString("workflow_run_id"),
                        resultSet.getString("document_id"),
                        resultSet.getString("node_id"),
                        resultSet.getString("stage"),
                        resultSet.getString("status"),
                        safe(resultSet.getString("summary")),
                        resultSet.getString("error_code"),
                        safe(resultSet.getString("error_message")),
                        instant(resultSet, "created_at")))
                .list();
        return new IngestionWorkspace(
                totals.rawEvidence(), totals.documents(), totals.compressions(), totals.aiReviews(),
                sources, runs, reviews, generatedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public QuantWorkspace quant(int limit, Instant generatedAt) {
        var runs = jdbcClient.sql("""
                select quant.research_run_id, quant.workflow_run_id, workflow.request_summary,
                       quant.research_kind, quant.strategy_id, quant.strategy_version,
                       quant.status, quant.observation_count, quant.result_fingerprint,
                       coalesce((
                         select jsonb_object_agg(metric.metric_name, jsonb_build_object(
                           'value', metric.metric_value,
                           'unit', metric.metric_unit
                         ) order by metric.metric_name)::text
                         from quant_metric_fact metric
                         where metric.research_run_id = quant.research_run_id
                       ), '{}') as metrics,
                       quant.error_code, quant.error_message, quant.requested_at,
                       quant.started_at, quant.completed_at
                from quant_research_run quant
                join workflow_run workflow on workflow.run_id = quant.workflow_run_id
                order by quant.requested_at desc, quant.id desc
                limit :limit
                """)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new QuantWorkspace.Run(
                        resultSet.getString("research_run_id"),
                        resultSet.getString("workflow_run_id"),
                        resultSet.getString("request_summary"),
                        resultSet.getString("research_kind"),
                        resultSet.getString("strategy_id"),
                        resultSet.getString("strategy_version"),
                        resultSet.getString("status"),
                        resultSet.getLong("observation_count"),
                        resultSet.getString("result_fingerprint"),
                        resultSet.getString("metrics"),
                        resultSet.getString("error_code"),
                        safe(resultSet.getString("error_message")),
                        instant(resultSet, "requested_at"),
                        nullableInstant(resultSet, "started_at"),
                        nullableInstant(resultSet, "completed_at")))
                .list();
        return new QuantWorkspace(runs, generatedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public OperationsReport report(
            Instant fromInclusive,
            Instant toExclusive,
            Instant generatedAt) {
        return new OperationsReport(
                fromInclusive,
                toExclusive,
                List.of(
                        researchReport(fromInclusive, toExclusive),
                        ingestionReport(fromInclusive, toExclusive),
                        quantReport(fromInclusive, toExclusive),
                        aiReport(fromInclusive, toExclusive),
                        tradingReport(fromInclusive, toExclusive),
                        taskReport(fromInclusive, toExclusive)),
                generatedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public NetworkWorkspace network(Instant generatedAt) {
        var routes = jdbcClient.sql("""
                select route_id, route_type, display_name, require_proxy, allow_direct,
                       proxy_url_env, expected_ip_family, enabled, updated_at
                from network_proxy_route
                order by route_type
                """)
                .query((resultSet, rowNumber) -> networkRoute(resultSet))
                .list();
        var proxyGateways = jdbcClient.sql("""
                select gateway_id, display_name, subscription_url_env, inline_nodes_env,
                       (select string_agg(value, ',')
                        from jsonb_array_elements_text(preferred_names) value) as preferred_names,
                       maximum_nodes, refresh_seconds, allow_insecure_tls,
                       enabled, version, updated_at
                from proxy_gateway_profile
                order by gateway_id
                """)
                .query((resultSet, rowNumber) -> proxyGateway(resultSet))
                .list();
        return new NetworkWorkspace(routes, proxyGateways, generatedAt);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowLearning workflowLearning(WorkflowVersionId versionId, Instant generatedAt) {
        var root = jdbcClient.sql("""
                select version.definition_id,
                       count(distinct run.run_id) as run_count,
                       count(distinct run.run_id) filter (where run.status in ('COMPLETED', 'PARTIAL')) as completed_count,
                       count(distinct run.run_id) filter (where run.status = 'FAILED') as failed_count,
                       coalesce(sum(invocation.estimated_cost_usd), 0) as total_cost
                from workflow_definition_version version
                left join workflow_run run on run.workflow_version_id = version.version_id
                left join ai_invocation invocation on invocation.run_id = run.run_id
                where version.version_id = :versionId
                group by version.definition_id
                """)
                .param("versionId", versionId.value())
                .query((resultSet, rowNumber) -> new WorkflowRoot(
                        resultSet.getString("definition_id"),
                        resultSet.getLong("run_count"),
                        resultSet.getLong("completed_count"),
                        resultSet.getLong("failed_count"),
                        resultSet.getBigDecimal("total_cost")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Workflow version does not exist"));
        var nodes = jdbcClient.sql("""
                select node.node_id, node.display_name,
                       count(invocation.invocation_id) as invocation_count,
                       count(invocation.invocation_id) filter (where invocation.status = 'COMPLETED') as success_count,
                       count(invocation.invocation_id) filter (where invocation.status = 'FAILED') as failure_count,
                       coalesce(sum(invocation.input_tokens), 0) as input_tokens,
                       coalesce(sum(invocation.output_tokens), 0) as output_tokens,
                       coalesce(sum(invocation.estimated_cost_usd), 0) as cost_usd,
                       cast(avg(invocation.latency_milliseconds) as bigint) as average_latency
                from workflow_node_definition node
                left join workflow_run run on run.workflow_version_id = node.version_id
                left join ai_invocation invocation
                  on invocation.run_id = run.run_id and invocation.node_id = node.node_id
                where node.version_id = :versionId
                group by node.id, node.node_id, node.display_name
                order by node.id
                """)
                .param("versionId", versionId.value())
                .query((resultSet, rowNumber) -> new WorkflowLearning.NodePerformance(
                        resultSet.getString("node_id"),
                        resultSet.getString("display_name"),
                        resultSet.getLong("invocation_count"),
                        resultSet.getLong("success_count"),
                        resultSet.getLong("failure_count"),
                        resultSet.getLong("input_tokens"),
                        resultSet.getLong("output_tokens"),
                        resultSet.getBigDecimal("cost_usd"),
                        nullableLong(resultSet, "average_latency")))
                .list();
        var failures = jdbcClient.sql("""
                select checkpoint.run_id, checkpoint.node_id, checkpoint.error_code,
                       checkpoint.error_message, checkpoint.updated_at
                from workflow_node_checkpoint checkpoint
                join workflow_run run on run.run_id = checkpoint.run_id
                where run.workflow_version_id = :versionId and checkpoint.status = 'FAILED'
                order by checkpoint.updated_at desc, checkpoint.id desc
                limit 20
                """)
                .param("versionId", versionId.value())
                .query((resultSet, rowNumber) -> new WorkflowLearning.Failure(
                        resultSet.getString("run_id"),
                        resultSet.getString("node_id"),
                        resultSet.getString("error_code"),
                        safe(resultSet.getString("error_message")),
                        instant(resultSet, "updated_at")))
                .list();
        return new WorkflowLearning(
                root.definitionId(),
                versionId.value(),
                root.runCount(),
                root.completedCount(),
                root.failedCount(),
                root.totalCost(),
                nodes,
                failures,
                generatedAt);
    }

    private PlatformReadiness.Check providerCheck(Instant now) {
        var providers = jdbcClient.sql("""
                select profile_id, api_key_env, base_url, base_url_env
                from ai_provider_profile where enabled
                """)
                .query((resultSet, rowNumber) -> new ProviderConfiguration(
                        resultSet.getString("profile_id"),
                        resultSet.getString("api_key_env"),
                        resultSet.getString("base_url"),
                        resultSet.getString("base_url_env")))
                .list();
        var configured = providers.stream().filter(this::providerConfigured).count();
        var status = configured > 0 ? "READY" : "BLOCKED";
        return check(
                "AI_PROVIDER",
                "AI 模型服务",
                status,
                configured + " / " + providers.size() + " 个已启用厂商具备端点与 Key",
                "settings",
                now);
    }

    private PlatformReadiness.Check workflowCheck(Instant now) {
        var count = scalarLong("""
                select count(*) from workflow_definition definition
                where definition.active
                  and exists (
                    select 1 from workflow_definition_version version
                    where version.definition_id = definition.definition_id
                      and version.status = 'PUBLISHED'
                  )
                """);
        return check(
                "WORKFLOW",
                "主工作流",
                count > 0 ? "READY" : "BLOCKED",
                count > 0 ? count + " 个已发布工作流处于激活状态" : "没有可运行的已发布工作流",
                "workflow",
                now);
    }

    private PlatformReadiness.Check workerCheck(Instant now) {
        var count = jdbcClient.sql("""
                select count(*) from worker_instance
                where status = 'RUNNING' and heartbeat_at >= :freshAfter
                """)
                .param("freshAfter", timestamp(now.minus(Duration.ofMinutes(1))))
                .query(Long.class)
                .single();
        return check(
                "WORKER",
                "常驻 Worker",
                count > 0 ? "READY" : "BLOCKED",
                count > 0 ? count + " 个 Worker 心跳新鲜" : "一分钟内没有 Worker 心跳",
                "autonomous",
                now);
    }

    private PlatformReadiness.Check sourceCheck(Instant now) {
        var enabledSources = scalarLong(
                "select count(*) from information_source where enabled and deleted_at is null");
        var firecrawlReady = routeReady(OutboundRoute.FIRECRAWL);
        var status = enabledSources == 0 || !firecrawlReady ? "BLOCKED" : "READY";
        var detail = enabledSources + " 个信息源已启用；Firecrawl 路由"
                + (firecrawlReady ? "可解析" : "未满足 fail-closed 条件");
        return check("INGESTION", "信息采集", status, detail, "ingestion", now);
    }

    private PlatformReadiness.Check exchangeCheck(Instant now) {
        var accounts = jdbcClient.sql("""
                select account_id, api_key_env, api_secret_env
                from exchange_account where enabled
                """)
                .query((resultSet, rowNumber) -> new AccountConfiguration(
                        resultSet.getString("account_id"),
                        resultSet.getString("api_key_env"),
                        resultSet.getString("api_secret_env")))
                .list();
        var configured = accounts.stream().filter(this::accountConfigured).count();
        var status = accounts.isEmpty() || configured == 0 ? "WARNING" : "READY";
        return check(
                "PAPER_EXCHANGE",
                "模拟交易所",
                status,
                configured + " / " + accounts.size() + " 个启用账户具备 Key 与 Secret",
                "trading",
                now);
    }

    private PlatformReadiness.Check scheduleCheck(Instant now) {
        var schedule = jdbcClient.sql("""
                select enabled, interval_seconds, next_run_at
                from schedule_definition where schedule_id = 'schedule_autonomous_research'
                """)
                .query((resultSet, rowNumber) -> new ScheduleConfiguration(
                        resultSet.getBoolean("enabled"),
                        resultSet.getInt("interval_seconds"),
                        instant(resultSet, "next_run_at")))
                .optional();
        if (schedule.isEmpty()) {
            return check("SCHEDULE", "自动研究调度", "BLOCKED", "自动研究 schedule 不存在", "autonomous", now);
        }
        var value = schedule.orElseThrow();
        return check(
                "SCHEDULE",
                "自动研究调度",
                value.enabled() ? "READY" : "WARNING",
                value.enabled()
                        ? "每 " + value.intervalSeconds() + " 秒运行；下次 " + value.nextRunAt()
                        : "自动研究当前已停用",
                "autonomous",
                now);
    }

    private PlatformReadiness.LatestResearch latestResearch() {
        return jdbcClient.sql("""
                select run.run_id, run.status, run.request_summary,
                       coalesce((
                         select message.summary from agent_message message
                         where message.run_id = run.run_id and message.message_type = 'CHAIR_VERDICT'
                         order by message.created_at desc, message.id desc limit 1
                       ), '尚无主席裁决') as conclusion,
                       run.completed_at
                from workflow_run run
                where run.workflow_type in ('SCHEDULED_RESEARCH', 'INSTANT_RESEARCH')
                order by run.accepted_at desc, run.id desc
                limit 1
                """)
                .query((resultSet, rowNumber) -> new PlatformReadiness.LatestResearch(
                        resultSet.getString("run_id"),
                        resultSet.getString("status"),
                        resultSet.getString("request_summary"),
                        resultSet.getString("conclusion"),
                        nullableInstant(resultSet, "completed_at")))
                .optional()
                .orElse(null);
    }

    private PlatformReadiness.AccountSummary accountSummary() {
        return jdbcClient.sql("""
                select count(*) filter (where account.enabled) as enabled_accounts,
                       count(*) filter (where account.enabled
                         and snapshot.snapshot_id is not null) as synchronized_accounts,
                       coalesce(max(snapshot.currency), 'USDT') as currency,
                       coalesce(sum(snapshot.equity), 0) as equity,
                       coalesce(sum(snapshot.unrealized_pnl), 0) as unrealized_pnl,
                       coalesce((select sum(amount) from realized_pnl_fact), 0) as realized_pnl,
                       max(snapshot.occurred_at) as snapshot_at
                from exchange_account account
                left join lateral (
                  select value.snapshot_id, value.currency, value.equity,
                         value.unrealized_pnl, value.occurred_at
                  from exchange_account_snapshot value
                  where value.account_id = account.account_id
                  order by value.occurred_at desc, value.id desc limit 1
                ) snapshot on true
                """)
                .query((resultSet, rowNumber) -> new PlatformReadiness.AccountSummary(
                        resultSet.getInt("enabled_accounts"),
                        resultSet.getInt("synchronized_accounts"),
                        resultSet.getString("currency"),
                        resultSet.getBigDecimal("equity"),
                        resultSet.getBigDecimal("unrealized_pnl"),
                        resultSet.getBigDecimal("realized_pnl"),
                        nullableInstant(resultSet, "snapshot_at")))
                .single();
    }

    private NetworkWorkspace.Route networkRoute(ResultSet resultSet) throws SQLException {
        var routeType = resultSet.getString("route_type");
        var enabled = resultSet.getBoolean("enabled");
        var proxyEnvironment = resultSet.getString("proxy_url_env");
        var proxyCredential = runtimeSecrets.status(
                RuntimeSecretScope.PROXY_ROUTE,
                routeType,
                "PROXY_URL",
                proxyEnvironment);
        var resolvedEndpoint = "disabled";
        var status = enabled ? "BLOCKED" : "DISABLED";
        String resolverError = null;
        if (enabled) {
            try {
                var decision = proxyRoutes.resolve(OutboundRoute.valueOf(routeType));
                resolvedEndpoint = decision.redactedEndpoint();
                status = "READY";
            } catch (RuntimeException exception) {
                resolverError = safe(exception.getMessage());
            }
        }
        var dependency = latestDependency(routeType);
        var latestError = resolverError != null ? resolverError : dependency.error();
        return new NetworkWorkspace.Route(
                resultSet.getString("route_id"),
                routeType,
                resultSet.getString("display_name"),
                enabled,
                resultSet.getBoolean("require_proxy"),
                resultSet.getBoolean("allow_direct"),
                proxyCredential.configured(),
                proxyCredential.source().name(),
                proxyCredential.fingerprint(),
                proxyCredential.version(),
                resultSet.getString("expected_ip_family"),
                resolvedEndpoint,
                status,
                dependency.status(),
                latestError,
                dependency.occurredAt(),
                instant(resultSet, "updated_at"));
    }

    private NetworkWorkspace.ProxyGateway proxyGateway(ResultSet resultSet) throws SQLException {
        var gatewayId = resultSet.getString("gateway_id");
        var subscriptionEnvironment = resultSet.getString("subscription_url_env");
        var inlineNodesEnvironment = resultSet.getString("inline_nodes_env");
        var subscription = subscriptionEnvironment == null
                ? null
                : runtimeSecrets.status(
                        RuntimeSecretScope.PROXY_GATEWAY,
                        gatewayId,
                        "SUBSCRIPTION_URL",
                        subscriptionEnvironment);
        var inlineNodes = inlineNodesEnvironment == null
                ? null
                : runtimeSecrets.status(
                        RuntimeSecretScope.PROXY_GATEWAY,
                        gatewayId,
                        "INLINE_NODES",
                        inlineNodesEnvironment);
        var enabled = resultSet.getBoolean("enabled");
        var configured = subscription != null && subscription.configured()
                || inlineNodes != null && inlineNodes.configured();
        return new NetworkWorkspace.ProxyGateway(
                gatewayId,
                resultSet.getString("display_name"),
                enabled,
                Objects.requireNonNullElse(resultSet.getString("preferred_names"), ""),
                resultSet.getInt("maximum_nodes"),
                resultSet.getInt("refresh_seconds"),
                resultSet.getBoolean("allow_insecure_tls"),
                subscription != null,
                subscription == null ? "NOT_SUPPORTED" : subscription.source().name(),
                subscription == null ? null : subscription.fingerprint(),
                subscription == null ? 0 : subscription.version(),
                inlineNodes != null,
                inlineNodes == null ? "NOT_SUPPORTED" : inlineNodes.source().name(),
                inlineNodes == null ? null : inlineNodes.fingerprint(),
                inlineNodes == null ? 0 : inlineNodes.version(),
                !enabled ? "DISABLED" : configured ? "READY" : "BLOCKED",
                resultSet.getLong("version"),
                instant(resultSet, "updated_at"));
    }

    private IngestionWorkspace.SourceStatus ingestionSource(ResultSet resultSet) throws SQLException {
        var sourceId = resultSet.getString("source_id");
        var credentialEnvironment = resultSet.getString("credential_env");
        var credential = credentialEnvironment == null
                ? null
                : runtimeSecrets.status(
                        RuntimeSecretScope.INFORMATION_SOURCE,
                        sourceId,
                        "API_KEY",
                        credentialEnvironment);
        return new IngestionWorkspace.SourceStatus(
                sourceId,
                resultSet.getString("display_name"),
                resultSet.getString("source_mode"),
                resultSet.getString("source_tier"),
                resultSet.getString("category"),
                resultSet.getString("proxy_route_type"),
                credentialEnvironment != null,
                credential == null || credential.configured(),
                credential == null ? "NOT_REQUIRED" : credential.source().name(),
                credential == null ? null : credential.fingerprint(),
                credential == null ? 0 : credential.version(),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("version"),
                resultSet.getString("status"),
                resultSet.getInt("fetched_count"),
                resultSet.getInt("inserted_count"),
                resultSet.getInt("duplicate_count"),
                resultSet.getString("error_code"),
                safe(resultSet.getString("error_message")),
                nullableInstant(resultSet, "started_at"));
    }

    private DependencyStatus latestDependency(String routeType) {
        if ("EXCHANGE_GATE".equals(routeType) || "EXCHANGE_BYBIT".equals(routeType)) {
            var exchange = "EXCHANGE_GATE".equals(routeType) ? "GATE" : "BYBIT";
            return jdbcClient.sql("""
                    select reconciliation.status, reconciliation.error_message,
                           reconciliation.started_at
                    from exchange_reconciliation_run reconciliation
                    join exchange_account account on account.account_id = reconciliation.account_id
                    where account.exchange = :exchange
                    order by reconciliation.started_at desc, reconciliation.id desc
                    limit 1
                    """)
                    .param("exchange", exchange)
                    .query((resultSet, rowNumber) -> new DependencyStatus(
                            resultSet.getString("status"),
                            safe(resultSet.getString("error_message")),
                            instant(resultSet, "started_at")))
                    .optional()
                    .orElse(new DependencyStatus("NO_DATA", null, null));
        }
        return jdbcClient.sql("""
                select run.status, run.error_message, run.started_at
                from source_collection_run run
                join information_source source on source.source_id = run.source_id
                where source.proxy_route_type = :routeType and source.deleted_at is null
                order by run.started_at desc, run.id desc
                limit 1
                """)
                .param("routeType", routeType)
                .query((resultSet, rowNumber) -> new DependencyStatus(
                        resultSet.getString("status"),
                        safe(resultSet.getString("error_message")),
                        instant(resultSet, "started_at")))
                .optional()
                .orElse(new DependencyStatus("NO_DATA", null, null));
    }

    private OperationsReport.Section researchReport(Instant from, Instant to) {
        var metric = jdbcClient.sql("""
                select count(*) as total,
                       count(*) filter (where status in ('COMPLETED', 'PARTIAL')) as completed,
                       count(*) filter (where status = 'FAILED') as failed
                from workflow_run where accepted_at >= :from and accepted_at < :to
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> new CountMetric(
                        resultSet.getLong("total"),
                        resultSet.getLong("completed"),
                        resultSet.getLong("failed")))
                .single();
        var entries = recentWorkflowFailures(from, to);
        return section("RESEARCH", "研究运行", metric, entries);
    }

    private OperationsReport.Section ingestionReport(Instant from, Instant to) {
        var metric = jdbcClient.sql("""
                select count(*) as total,
                       count(*) filter (where status in ('COMPLETED', 'PARTIAL')) as completed,
                       count(*) filter (where status in ('FAILED', 'BLOCKED')) as failed
                from source_collection_run where started_at >= :from and started_at < :to
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> new CountMetric(
                        resultSet.getLong("total"),
                        resultSet.getLong("completed"),
                        resultSet.getLong("failed")))
                .single();
        var entries = jdbcClient.sql("""
                select run.collection_id, source.display_name, run.status,
                       coalesce(run.error_message, '采集未完整完成') as summary, run.started_at
                from source_collection_run run
                join information_source source on source.source_id = run.source_id
                where run.started_at >= :from and run.started_at < :to
                  and run.status in ('FAILED', 'BLOCKED', 'PARTIAL')
                order by run.started_at desc, run.id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("collection_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "started_at")))
                .list();
        return section("INGESTION", "采集与证据", metric, entries);
    }

    private OperationsReport.Section quantReport(Instant from, Instant to) {
        var metric = countMetric("quant_research_run", "requested_at", from, to);
        var entries = jdbcClient.sql("""
                select research_run_id, strategy_id, status,
                       coalesce(error_message, observation_count || ' 条观测') as summary,
                       requested_at
                from quant_research_run
                where requested_at >= :from and requested_at < :to
                  and status in ('FAILED', 'CANCELLED')
                order by requested_at desc, id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("research_run_id"),
                        resultSet.getString("strategy_id"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "requested_at")))
                .list();
        return section("QUANT", "量化研究", metric, entries);
    }

    private OperationsReport.Section aiReport(Instant from, Instant to) {
        var aggregate = jdbcClient.sql("""
                select count(*) as total,
                       count(*) filter (where status = 'COMPLETED') as completed,
                       count(*) filter (where status = 'FAILED') as failed,
                       coalesce(sum(input_tokens + output_tokens), 0) as tokens,
                       coalesce(sum(estimated_cost_usd), 0) as cost
                from ai_invocation where started_at >= :from and started_at < :to
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> new AiMetric(
                        resultSet.getLong("total"),
                        resultSet.getLong("completed"),
                        resultSet.getLong("failed"),
                        resultSet.getLong("tokens"),
                        resultSet.getBigDecimal("cost")))
                .single();
        var metrics = new ArrayList<>(metrics(new CountMetric(
                aggregate.total(), aggregate.completed(), aggregate.failed())));
        metrics.add(new OperationsReport.Metric("Token", Long.toString(aggregate.tokens()), "tokens", "INFO"));
        metrics.add(new OperationsReport.Metric("估算成本", aggregate.cost().toPlainString(), "USD", "INFO"));
        var entries = jdbcClient.sql("""
                select invocation_id, model_name, status,
                       coalesce(error_message, error_code, 'AI 调用失败') as summary, started_at
                from ai_invocation
                where started_at >= :from and started_at < :to and status = 'FAILED'
                order by started_at desc, id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("invocation_id"),
                        resultSet.getString("model_name"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "started_at")))
                .list();
        return new OperationsReport.Section("AI", "AI 调用与成本", metrics, entries);
    }

    private OperationsReport.Section tradingReport(Instant from, Instant to) {
        var metric = jdbcClient.sql("""
                select (select count(*) from oms_order where created_at >= :from and created_at < :to) as total,
                       (select count(*) from exchange_fill_fact where occurred_at >= :from and occurred_at < :to) as completed,
                       (select count(*) from exchange_submission_attempt
                        where started_at >= :from and started_at < :to and status = 'FAILED') as failed
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> new CountMetric(
                        resultSet.getLong("total"),
                        resultSet.getLong("completed"),
                        resultSet.getLong("failed")))
                .single();
        var entries = jdbcClient.sql("""
                select attempt.attempt_id, order_record.symbol, attempt.status,
                       coalesce(attempt.error_message, attempt.error_code, '提交失败') as summary,
                       attempt.started_at
                from exchange_submission_attempt attempt
                join oms_order order_record on order_record.order_id = attempt.order_id
                where attempt.started_at >= :from and attempt.started_at < :to
                  and attempt.status = 'FAILED'
                order by attempt.started_at desc, attempt.id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("attempt_id"),
                        resultSet.getString("symbol"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "started_at")))
                .list();
        return section("TRADING", "模拟交易与风控", metric, entries);
    }

    private OperationsReport.Section taskReport(Instant from, Instant to) {
        var metric = countMetric("background_task", "created_at", from, to);
        var entries = jdbcClient.sql("""
                select task_id, task_type, status,
                       coalesce(error_message, error_code, '后台任务失败') as summary, created_at
                from background_task
                where created_at >= :from and created_at < :to and status = 'FAILED'
                order by created_at desc, id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("task_id"),
                        resultSet.getString("task_type"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "created_at")))
                .list();
        return section("OPERATIONS", "后台任务", metric, entries);
    }

    private CountMetric countMetric(String table, String timeColumn, Instant from, Instant to) {
        var allowed = switch (table) {
            case "quant_research_run" -> """
                    select count(*) as total,
                           count(*) filter (where status = 'COMPLETED') as completed,
                           count(*) filter (where status in ('FAILED', 'CANCELLED')) as failed
                    from quant_research_run where requested_at >= :from and requested_at < :to
                    """;
            case "background_task" -> """
                    select count(*) as total,
                           count(*) filter (where status = 'COMPLETED') as completed,
                           count(*) filter (where status = 'FAILED') as failed
                    from background_task where created_at >= :from and created_at < :to
                    """;
            default -> throw new IllegalArgumentException("Unsupported report source: " + table + '.' + timeColumn);
        };
        return jdbcClient.sql(allowed)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> new CountMetric(
                        resultSet.getLong("total"),
                        resultSet.getLong("completed"),
                        resultSet.getLong("failed")))
                .single();
    }

    private List<OperationsReport.Entry> recentWorkflowFailures(Instant from, Instant to) {
        return jdbcClient.sql("""
                select run.run_id, run.request_summary, run.status,
                       coalesce(checkpoint.error_message, checkpoint.error_code, '研究运行失败') as summary,
                       run.updated_at
                from workflow_run run
                left join lateral (
                  select value.error_code, value.error_message
                  from workflow_node_checkpoint value
                  where value.run_id = run.run_id and value.status = 'FAILED'
                  order by value.updated_at desc, value.id desc limit 1
                ) checkpoint on true
                where run.accepted_at >= :from and run.accepted_at < :to and run.status = 'FAILED'
                order by run.updated_at desc, run.id desc limit 10
                """)
                .param("from", timestamp(from))
                .param("to", timestamp(to))
                .query((resultSet, rowNumber) -> entry(
                        resultSet.getString("run_id"),
                        resultSet.getString("request_summary"),
                        resultSet.getString("summary"),
                        resultSet.getString("status"),
                        instant(resultSet, "updated_at")))
                .list();
    }

    private static OperationsReport.Section section(
            String code,
            String title,
            CountMetric metric,
            List<OperationsReport.Entry> entries) {
        return new OperationsReport.Section(code, title, metrics(metric), entries);
    }

    private static List<OperationsReport.Metric> metrics(CountMetric metric) {
        return List.of(
                new OperationsReport.Metric("总数", Long.toString(metric.total()), "次", "INFO"),
                new OperationsReport.Metric("完成", Long.toString(metric.completed()), "次", "SUCCESS"),
                new OperationsReport.Metric(
                        "失败", Long.toString(metric.failed()), "次", metric.failed() > 0 ? "WARNING" : "SUCCESS"));
    }

    private static OperationsReport.Entry entry(
            String referenceId,
            String title,
            String summary,
            String status,
            Instant occurredAt) {
        return new OperationsReport.Entry(referenceId, title, safe(summary), status, occurredAt);
    }

    private boolean providerConfigured(ProviderConfiguration provider) {
        var baseUrlConfigured = provider.baseUrl() != null && !provider.baseUrl().isBlank()
                || provider.baseUrlEnvironment() != null && configured(provider.baseUrlEnvironment());
        return baseUrlConfigured && runtimeSecrets.status(
                        RuntimeSecretScope.AI_PROVIDER,
                        provider.profileId(),
                        "API_KEY",
                        provider.apiKeyEnvironment())
                .configured();
    }

    private boolean accountConfigured(AccountConfiguration account) {
        return runtimeSecrets.status(
                                RuntimeSecretScope.EXCHANGE_ACCOUNT,
                                account.accountId(),
                                "API_KEY",
                                account.keyEnvironment())
                        .configured()
                && runtimeSecrets.status(
                                RuntimeSecretScope.EXCHANGE_ACCOUNT,
                                account.accountId(),
                                "API_SECRET",
                                account.secretEnvironment())
                        .configured();
    }

    private boolean configured(String environmentVariable) {
        return environment.resolve(environmentVariable).filter(value -> !value.isBlank()).isPresent();
    }

    private boolean routeReady(OutboundRoute route) {
        try {
            proxyRoutes.resolve(route);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private long scalarLong(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private static PlatformReadiness.Check check(
            String code,
            String title,
            String status,
            String detail,
            String actionPage,
            Instant observedAt) {
        return new PlatformReadiness.Check(code, title, status, detail, actionPage, observedAt);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Objects.requireNonNull(resultSet.getObject(column, OffsetDateTime.class), column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, Long.class);
        return value;
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.substring(0, Math.min(normalized.length(), 500));
    }

    private record ProviderConfiguration(
            String profileId,
            String apiKeyEnvironment,
            String baseUrl,
            String baseUrlEnvironment) {
    }

    private record AccountConfiguration(
            String accountId,
            String keyEnvironment,
            String secretEnvironment) {
    }

    private record ScheduleConfiguration(boolean enabled, int intervalSeconds, Instant nextRunAt) {
    }

    private record TaskCounts(long pending, long failed) {
    }

    private record IngestionTotals(long rawEvidence, long documents, long compressions, long aiReviews) {
    }

    private record DependencyStatus(String status, String error, Instant occurredAt) {
    }

    private record WorkflowRoot(
            String definitionId,
            long runCount,
            long completedCount,
            long failedCount,
            BigDecimal totalCost) {
    }

    private record CountMetric(long total, long completed, long failed) {
    }

    private record AiMetric(long total, long completed, long failed, long tokens, BigDecimal cost) {
    }
}
