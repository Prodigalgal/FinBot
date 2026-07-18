package io.omnnu.finbot.configuration;

import io.omnnu.finbot.application.ai.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.AiCompletionGateway;
import io.omnnu.finbot.application.ai.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.AiRuntimeBindingResolver;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker;
import io.omnnu.finbot.application.autonomous.AutonomousResearchService;
import io.omnnu.finbot.application.autonomous.AutonomousResearchUseCase;
import io.omnnu.finbot.application.identity.AdminCredentialVerifier;
import io.omnnu.finbot.application.ingestion.EvidenceNormalizer;
import io.omnnu.finbot.application.ingestion.IngestionApplicationService;
import io.omnnu.finbot.application.ingestion.IngestionRepository;
import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.ingestion.SourceCollectionGateway;
import io.omnnu.finbot.application.ingestion.SourceRuntimeHealthGateway;
import io.omnnu.finbot.application.identity.AuthenticationApplicationService;
import io.omnnu.finbot.application.identity.AuthenticationCryptography;
import io.omnnu.finbot.application.identity.AuthenticationPolicy;
import io.omnnu.finbot.application.identity.AuthenticationStore;
import io.omnnu.finbot.application.identity.AuthenticationUseCase;
import io.omnnu.finbot.application.configuration.ConfigurationApplicationService;
import io.omnnu.finbot.application.configuration.ConfigurationRepository;
import io.omnnu.finbot.application.configuration.ConfigurationUseCase;
import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.ProviderModelCatalogGateway;
import io.omnnu.finbot.application.configuration.ProviderModelCatalogService;
import io.omnnu.finbot.application.configuration.ProviderModelCatalogUseCase;
import io.omnnu.finbot.application.experiment.AiExperimentRepository;
import io.omnnu.finbot.application.experiment.AiExperimentService;
import io.omnnu.finbot.application.experiment.AiExperimentUseCase;
import io.omnnu.finbot.application.exchange.OmsExecutionStore;
import io.omnnu.finbot.application.exchange.ExchangeAccountGateway;
import io.omnnu.finbot.application.exchange.ExchangeAccountConfigurationRepository;
import io.omnnu.finbot.application.exchange.ExchangeAccountControlRepository;
import io.omnnu.finbot.application.exchange.ExchangeAccountControlService;
import io.omnnu.finbot.application.exchange.ExchangeAccountControlUseCase;
import io.omnnu.finbot.application.exchange.ExchangeAccountSyncService;
import io.omnnu.finbot.application.exchange.ExchangeAccountSyncUseCase;
import io.omnnu.finbot.application.exchange.ExchangeSyncCursorStore;
import io.omnnu.finbot.application.exchange.PaperExchangeGateway;
import io.omnnu.finbot.application.exchange.ExchangeCapabilityQuery;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionService;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.exchange.OrderReconciliationService;
import io.omnnu.finbot.application.exchange.OrderReconciliationStore;
import io.omnnu.finbot.application.exchange.OrderReconciliationUseCase;
import io.omnnu.finbot.application.catalog.CatalogApplicationService;
import io.omnnu.finbot.application.catalog.CatalogRepository;
import io.omnnu.finbot.application.catalog.CatalogUseCase;
import io.omnnu.finbot.application.catalog.ProductCatalogGateway;
import io.omnnu.finbot.application.catalog.ProductCatalogSyncService;
import io.omnnu.finbot.application.catalog.ProductCatalogSyncStore;
import io.omnnu.finbot.application.catalog.ProductCatalogSyncUseCase;
import io.omnnu.finbot.application.ledger.TradingLedgerQueryRepository;
import io.omnnu.finbot.application.ledger.TradingLedgerQueryService;
import io.omnnu.finbot.application.ledger.TradingLedgerQueryUseCase;
import io.omnnu.finbot.application.ledger.TradingLedgerWriter;
import io.omnnu.finbot.application.market.MarketDataApplicationService;
import io.omnnu.finbot.application.market.MarketDataArtifactEncoder;
import io.omnnu.finbot.application.market.MarketDataArtifactUriFactory;
import io.omnnu.finbot.application.market.MarketDataGateway;
import io.omnnu.finbot.application.market.MarketDataRepository;
import io.omnnu.finbot.application.market.MarketDataUseCase;
import io.omnnu.finbot.application.market.MarketDataRefreshUseCase;
import io.omnnu.finbot.application.network.NetworkDiagnosticStore;
import io.omnnu.finbot.application.network.NetworkDiagnosticsService;
import io.omnnu.finbot.application.network.NetworkDiagnosticsUseCase;
import io.omnnu.finbot.application.network.NetworkProbeGateway;
import io.omnnu.finbot.application.network.ProxyRouteResolver;
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.BackgroundTaskStore;
import io.omnnu.finbot.application.operations.OperationsRepository;
import io.omnnu.finbot.application.quant.QuantResearchApplicationService;
import io.omnnu.finbot.application.quant.QuantResearchGateway;
import io.omnnu.finbot.application.quant.QuantResearchStore;
import io.omnnu.finbot.application.quant.QuantResearchUseCase;
import io.omnnu.finbot.application.quant.TradeRiskPreviewService;
import io.omnnu.finbot.application.quant.TradeRiskPreviewUseCase;
import io.omnnu.finbot.application.review.ResearchFeedbackStore;
import io.omnnu.finbot.application.review.ResearchReviewService;
import io.omnnu.finbot.application.review.ResearchReviewUseCase;
import io.omnnu.finbot.application.setup.SetupProfileRepository;
import io.omnnu.finbot.application.setup.SetupProfileService;
import io.omnnu.finbot.application.setup.SetupProfileUseCase;
import io.omnnu.finbot.application.workflow.WorkflowManagementRepository;
import io.omnnu.finbot.application.workflow.WorkflowManagementService;
import io.omnnu.finbot.application.workflow.WorkflowManagementUseCase;
import io.omnnu.finbot.application.research.CompressionApplicationService;
import io.omnnu.finbot.application.research.CompressionOutputParser;
import io.omnnu.finbot.application.research.CompressionRepository;
import io.omnnu.finbot.application.research.CompressionUseCase;
import io.omnnu.finbot.application.research.ResearchPipelineService;
import io.omnnu.finbot.application.research.StoredResearchWorkflowPlanQuery;
import io.omnnu.finbot.application.research.ResearchPipelineUseCase;
import io.omnnu.finbot.application.research.ResearchSegmentationService;
import io.omnnu.finbot.application.research.ResearchSegmentationStore;
import io.omnnu.finbot.application.research.ResearchLaunchService;
import io.omnnu.finbot.application.research.ResearchLaunchUseCase;
import io.omnnu.finbot.application.research.ResearchHistoryRepository;
import io.omnnu.finbot.application.research.ForecastEvaluationService;
import io.omnnu.finbot.application.research.ForecastEvaluationUseCase;
import io.omnnu.finbot.application.research.ResearchForecastRepository;
import io.omnnu.finbot.application.research.ResearchForecastService;
import io.omnnu.finbot.application.research.ResearchForecastUseCase;
import io.omnnu.finbot.application.workflow.StructuredAiOutputParser;
import io.omnnu.finbot.application.workflow.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.WorkflowExecutionService;
import io.omnnu.finbot.application.workflow.WorkflowExecutionStore;
import io.omnnu.finbot.application.workflow.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.WorkflowDiagnosticsService;
import io.omnnu.finbot.application.workflow.WorkflowDiagnosticsUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunFailureService;
import io.omnnu.finbot.application.workflow.WorkflowRunFailureUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunQuery;
import io.omnnu.finbot.application.workflow.WorkflowRunResumeService;
import io.omnnu.finbot.application.workflow.WorkflowRunResumeStore;
import io.omnnu.finbot.application.workflow.WorkflowRunResumeUseCase;
import io.omnnu.finbot.application.workspace.PlatformWorkspaceRepository;
import io.omnnu.finbot.application.workspace.PlatformWorkspaceService;
import io.omnnu.finbot.application.workspace.PlatformWorkspaceUseCase;
import io.omnnu.finbot.application.shared.SortableIdGenerator;
import io.omnnu.finbot.application.trading.TradeAutomationApplicationService;
import io.omnnu.finbot.application.trading.TradeAutomationStore;
import io.omnnu.finbot.application.trading.TradeAutomationUseCase;
import io.omnnu.finbot.application.trading.TradeDecisionOutputParser;
import io.omnnu.finbot.application.workflow.WorkflowCommandStore;
import io.omnnu.finbot.application.workflow.WorkflowRunApplicationService;
import io.omnnu.finbot.application.workflow.StartWorkflowUseCase;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import io.omnnu.finbot.infrastructure.identity.EncodedAdminCredentialVerifier;
import io.omnnu.finbot.infrastructure.identity.MonotonicSortableIdGenerator;
import io.omnnu.finbot.domain.risk.MarginRiskEngine;
import io.omnnu.finbot.domain.risk.EstimatedTradeEngine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    SortableIdGenerator sortableIdGenerator(Clock systemClock) {
        return new MonotonicSortableIdGenerator(systemClock);
    }

    @Bean
    RandomGenerator authenticationRandomGenerator() {
        return new SecureRandom();
    }

    @Bean
    PasswordEncoder adminPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AdminCredentialVerifier adminCredentialVerifier(
            AuthenticationProperties properties,
            PasswordEncoder adminPasswordEncoder) {
        return new EncodedAdminCredentialVerifier(
                properties.adminUsername(),
                properties.adminPassword(),
                adminPasswordEncoder);
    }

    @Bean
    AuthenticationUseCase authenticationUseCase(
            SortableIdGenerator idGenerator,
            AuthenticationStore store,
            AdminCredentialVerifier credentialVerifier,
            AuthenticationCryptography cryptography,
            AuthenticationProperties properties,
            Clock clock,
            RandomGenerator authenticationRandomGenerator) {
        var policy = new AuthenticationPolicy(
                properties.challengeTtl(),
                properties.proofOfWorkDifficulty(),
                properties.maximumChallengeFailures(),
                properties.sessionTtl(),
                properties.touchInterval());
        return new AuthenticationApplicationService(
                idGenerator,
                store,
                credentialVerifier,
                cryptography,
                policy,
                clock,
                authenticationRandomGenerator);
    }

    @Bean
    ConfigurationUseCase configurationUseCase(
            ConfigurationRepository repository,
            EnvironmentValueResolver environmentValueResolver,
            io.omnnu.finbot.application.configuration.RuntimeSecretStore runtimeSecretStore,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new ConfigurationApplicationService(
                repository, environmentValueResolver, runtimeSecretStore, idGenerator, clock);
    }

    @Bean
    io.omnnu.finbot.application.configuration.RuntimeSecretManagementUseCase runtimeSecretManagementUseCase(
            io.omnnu.finbot.application.configuration.RuntimeSecretStore store,
            io.omnnu.finbot.application.configuration.RuntimeSecretTargetRepository targets,
            Clock clock) {
        return new io.omnnu.finbot.application.configuration.RuntimeSecretManagementService(
                store, targets, clock);
    }

    @Bean
    ProviderModelCatalogUseCase providerModelCatalogUseCase(
            ConfigurationRepository configuration,
            EnvironmentValueResolver environment,
            io.omnnu.finbot.application.configuration.RuntimeSecretStore runtimeSecretStore,
            ProviderModelCatalogGateway gateway) {
        return new ProviderModelCatalogService(
                configuration, environment, runtimeSecretStore, gateway);
    }

    @Bean
    CatalogUseCase catalogUseCase(
            CatalogRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new CatalogApplicationService(repository, idGenerator, clock);
    }

    @Bean
    ProductCatalogSyncUseCase productCatalogSyncUseCase(
            ProductCatalogGateway gateway,
            ProductCatalogSyncStore store,
            Clock clock) {
        return new ProductCatalogSyncService(gateway, store, clock);
    }

    @Bean
    TradingLedgerQueryUseCase tradingLedgerQueryUseCase(
            TradingLedgerQueryRepository repository,
            io.omnnu.finbot.application.configuration.RuntimeSecretStore runtimeSecretStore,
            Clock clock,
            TradingLedgerProperties properties) {
        return new TradingLedgerQueryService(
                repository,
                runtimeSecretStore,
                clock,
                properties.staleAfter());
    }

    @Bean
    BackgroundTaskCoordinator backgroundTaskCoordinator(
            BackgroundTaskStore store,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new BackgroundTaskCoordinator(store, idGenerator, clock);
    }

    @Bean
    AutonomousResearchUseCase autonomousResearchUseCase(
            OperationsRepository operations,
            BackgroundTaskCoordinator tasks,
            ResearchHistoryRepository history,
            Clock clock) {
        return new AutonomousResearchService(operations, tasks, history, clock);
    }

    @Bean
    PlatformWorkspaceUseCase platformWorkspaceUseCase(
            PlatformWorkspaceRepository repository,
            Clock clock) {
        return new PlatformWorkspaceService(repository, clock);
    }

    @Bean
    ResearchReviewUseCase researchReviewUseCase(
            ResearchHistoryRepository history,
            ResearchFeedbackStore feedbackStore,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new ResearchReviewService(history, feedbackStore, idGenerator, clock);
    }

    @Bean
    SetupProfileUseCase setupProfileUseCase(
            ConfigurationUseCase configuration,
            SetupProfileRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new SetupProfileService(configuration, repository, idGenerator, clock);
    }

    @Bean
    WorkflowManagementUseCase workflowManagementUseCase(
            WorkflowManagementRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new WorkflowManagementService(repository, idGenerator, clock);
    }

    @Bean
    AiExperimentUseCase aiExperimentUseCase(
            AiExperimentRepository repository,
            WorkflowManagementUseCase workflows,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new AiExperimentService(repository, workflows, idGenerator, clock);
    }

    @Bean(name = "workflowVirtualThreadExecutor", destroyMethod = "close")
    ExecutorService workflowVirtualThreadExecutor() {
        var factory = Thread.ofVirtual().name("finbot-workflow-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "close")
    ScheduledExecutorService sseHeartbeatScheduler() {
        var factory = Thread.ofPlatform().daemon(true).name("finbot-sse-heartbeat").factory();
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Bean(name = "quantHttpClient")
    HttpClient quantHttpClient(
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Bean(name = "aiHttpClient")
    HttpClient aiHttpClient(
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Bean
    StartWorkflowUseCase startWorkflowUseCase(
            SortableIdGenerator idGenerator,
            WorkflowCommandStore commandStore,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new WorkflowRunApplicationService(idGenerator, commandStore, clock, executor);
    }

    @Bean
    ResearchSegmentationService researchSegmentationService(ResearchSegmentationStore store) {
        return new ResearchSegmentationService(store);
    }

    @Bean
    ResearchPipelineUseCase researchPipelineUseCase(
            StartWorkflowUseCase startWorkflow,
            WorkflowExecutionStore workflowStore,
            IngestionUseCase ingestion,
            CompressionUseCase compression,
            MarketDataUseCase marketData,
            QuantResearchUseCase quantResearch,
            WorkflowExecutionUseCase workflowExecution,
            WorkflowRunFailureUseCase workflowFailure,
            WorkflowRunQuery workflowRuns,
            TradeAutomationUseCase tradeAutomation,
            ResearchSegmentationService segmentation,
            Clock clock) {
        return new ResearchPipelineService(
                startWorkflow,
                new StoredResearchWorkflowPlanQuery(workflowStore),
                ingestion,
                compression,
                marketData,
                quantResearch,
                workflowExecution,
                workflowFailure,
                workflowRuns,
                tradeAutomation,
                segmentation,
                clock);
    }

    @Bean
    ResearchLaunchUseCase researchLaunchUseCase(
            StartWorkflowUseCase startWorkflow,
            BackgroundTaskCoordinator tasks,
            WorkflowRunResumeUseCase workflowResume) {
        return new ResearchLaunchService(startWorkflow, tasks, workflowResume);
    }

    @Bean
    WorkflowRunResumeUseCase workflowRunResumeUseCase(
            WorkflowRunResumeStore store,
            WorkflowRunQuery query,
            Clock clock) {
        return new WorkflowRunResumeService(store, query, clock);
    }

    @Bean
    WorkflowAiInvoker workflowAiInvoker(
            AiCompletionGateway completionGateway,
            AiRuntimeBindingResolver bindingResolver,
            AiInvocationAuditStore auditStore,
            AiBudgetReservationStore budgetStore,
            WorkflowEventPublisher eventPublisher,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new WorkflowAiInvoker(
                completionGateway,
                bindingResolver,
                auditStore,
                budgetStore,
                eventPublisher,
                idGenerator,
                clock);
    }

    @Bean
    WorkflowRunFailureUseCase workflowRunFailureUseCase(
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher) {
        return new WorkflowRunFailureService(executionStore, eventPublisher);
    }

    @Bean
    WorkflowExecutionUseCase workflowExecutionUseCase(
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowRunFailureUseCase workflowFailure,
            WorkflowAiInvoker aiInvoker,
            StructuredAiOutputParser outputParser,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new WorkflowExecutionService(
                executionStore,
                eventPublisher,
                workflowFailure,
                aiInvoker,
                outputParser,
                clock,
                executor);
    }

    @Bean
    WorkflowDiagnosticsUseCase workflowDiagnosticsUseCase(
            WorkflowManagementUseCase management,
            ConfigurationUseCase configuration,
            StartWorkflowUseCase startWorkflow,
            WorkflowExecutionStore executionStore,
            WorkflowRunFailureUseCase failureUseCase,
            WorkflowEventPublisher eventPublisher,
            WorkflowAiInvoker aiInvoker,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new WorkflowDiagnosticsService(
                management,
                configuration,
                startWorkflow,
                executionStore,
                failureUseCase,
                eventPublisher,
                aiInvoker,
                idGenerator,
                clock,
                executor);
    }

    @Bean
    NetworkDiagnosticsUseCase networkDiagnosticsUseCase(
            ProxyRouteResolver routeResolver,
            NetworkProbeGateway probeGateway,
            NetworkDiagnosticStore store,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new NetworkDiagnosticsService(
                routeResolver, probeGateway, store, idGenerator, clock, executor);
    }

    @Bean
    io.omnnu.finbot.application.network.ProxyGatewayControlUseCase proxyGatewayControlUseCase(
            io.omnnu.finbot.application.network.ProxyGatewayProfileRepository profiles,
            io.omnnu.finbot.application.configuration.RuntimeSecretStore runtimeSecretStore,
            io.omnnu.finbot.application.network.ProxyGatewayControlGateway gateway,
            Clock clock) {
        return new io.omnnu.finbot.application.network.ProxyGatewayControlService(
                profiles, runtimeSecretStore, gateway, clock);
    }

    @Bean
    IngestionUseCase ingestionUseCase(
            IngestionRepository repository,
            SourceCollectionGateway collectionGateway,
            EvidenceNormalizer evidenceNormalizer,
            SourceRuntimeHealthGateway runtimeHealthGateway,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new IngestionApplicationService(
                repository,
                collectionGateway,
                evidenceNormalizer,
                runtimeHealthGateway,
                idGenerator,
                clock,
                executor);
    }

    @Bean
    CompressionUseCase compressionUseCase(
            CompressionRepository repository,
            WorkflowExecutionStore workflowStore,
            WorkflowAiInvoker aiInvoker,
            CompressionOutputParser outputParser,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new CompressionApplicationService(
                repository,
                workflowStore,
                aiInvoker,
                outputParser,
                idGenerator,
                clock,
                executor);
    }

    @Bean
    MarketDataApplicationService marketDataUseCase(
            MarketDataRepository repository,
            MarketDataGateway gateway,
            ExchangeCapabilityQuery capabilities,
            MarketDataArtifactEncoder artifactEncoder,
            MarketDataArtifactUriFactory artifactUriFactory,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new MarketDataApplicationService(
                repository,
                gateway,
                capabilities,
                artifactEncoder,
                artifactUriFactory,
                clock,
                executor);
    }

    @Bean
    ResearchForecastUseCase researchForecastUseCase(ResearchForecastRepository repository) {
        return new ResearchForecastService(repository);
    }

    @Bean
    ForecastEvaluationUseCase forecastEvaluationUseCase(
            ResearchForecastRepository repository,
            MarketDataRefreshUseCase marketData,
            Clock clock) {
        return new ForecastEvaluationService(repository, marketData, clock);
    }

    @Bean
    QuantResearchUseCase quantResearchUseCase(
            QuantResearchGateway gateway,
            QuantResearchStore store,
            WorkflowExecutionStore workflowStore,
            WorkflowEventPublisher workflowEvents,
            Clock clock) {
        return new QuantResearchApplicationService(
                gateway,
                store,
                workflowStore,
                workflowEvents,
                clock);
    }

    @Bean
    TradeRiskPreviewUseCase tradeRiskPreviewUseCase(
            io.omnnu.finbot.application.trading.TradeAutomationConfigurationRepository configuration,
            Clock clock) {
        return new TradeRiskPreviewService(
                configuration,
                new MarginRiskEngine(),
                new EstimatedTradeEngine(),
                clock);
    }

    @Bean
    PaperOrderExecutionUseCase paperOrderExecutionUseCase(
            OmsExecutionStore store,
            PaperExchangeGateway gateway,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor,
            @Value("${finbot.worker.instance-name:${HOSTNAME:finbot-local}}") String workerId) {
        return new PaperOrderExecutionService(store, gateway, clock, executor, workerId);
    }

    @Bean
    TradeAutomationUseCase tradeAutomationUseCase(
            WorkflowExecutionStore workflowStore,
            WorkflowAiInvoker aiInvoker,
            TradeDecisionOutputParser outputParser,
            TradeAutomationStore store,
            PaperOrderExecutionUseCase orderExecution,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new TradeAutomationApplicationService(
                workflowStore,
                aiInvoker,
                outputParser,
                store,
                orderExecution,
                new MarginRiskEngine(),
                new EstimatedTradeEngine(),
                clock,
                executor);
    }

    @Bean
    ExchangeAccountSyncUseCase exchangeAccountSyncUseCase(
            ExchangeAccountGateway gateway,
            ExchangeAccountConfigurationRepository accounts,
            ExchangeSyncCursorStore cursors,
            TradingLedgerWriter ledger,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new ExchangeAccountSyncService(gateway, accounts, cursors, ledger, clock, executor);
    }

    @Bean
    ExchangeAccountControlUseCase exchangeAccountControlUseCase(
            ExchangeAccountControlRepository repository,
            Clock clock) {
        return new ExchangeAccountControlService(repository, clock);
    }

    @Bean
    OrderReconciliationUseCase orderReconciliationUseCase(
            OrderReconciliationStore store,
            PaperOrderExecutionUseCase execution,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new OrderReconciliationService(
                store,
                execution,
                idGenerator,
                clock,
                executor);
    }
}
