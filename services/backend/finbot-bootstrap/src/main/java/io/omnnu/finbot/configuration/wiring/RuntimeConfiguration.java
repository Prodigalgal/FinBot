package io.omnnu.finbot.configuration.wiring;

import io.omnnu.finbot.application.ai.port.out.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.port.out.AiCompletionGateway;
import io.omnnu.finbot.application.ai.port.out.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.port.out.AiRuntimeBindingResolver;
import io.omnnu.finbot.application.ai.service.AiExecutionPolicyExecutor;
import io.omnnu.finbot.application.ai.service.WorkflowAiInvoker;
import io.omnnu.finbot.application.autonomous.port.in.AutonomousResearchUseCase;
import io.omnnu.finbot.application.autonomous.service.AutonomousResearchService;
import io.omnnu.finbot.application.catalog.port.in.CatalogUseCase;
import io.omnnu.finbot.application.catalog.port.in.ProductCatalogSyncUseCase;
import io.omnnu.finbot.application.catalog.port.out.CatalogRepository;
import io.omnnu.finbot.application.catalog.port.out.ProductCatalogGateway;
import io.omnnu.finbot.application.catalog.port.out.ProductCatalogSyncStore;
import io.omnnu.finbot.application.catalog.service.CatalogApplicationService;
import io.omnnu.finbot.application.catalog.service.ProductCatalogSyncService;
import io.omnnu.finbot.application.configuration.port.in.ConfigurationUseCase;
import io.omnnu.finbot.application.configuration.port.in.ProviderModelCatalogUseCase;
import io.omnnu.finbot.application.configuration.port.out.ConfigurationRepository;
import io.omnnu.finbot.application.configuration.port.out.EnvironmentValueResolver;
import io.omnnu.finbot.application.configuration.port.out.ProviderModelCatalogGateway;
import io.omnnu.finbot.application.configuration.service.ConfigurationApplicationService;
import io.omnnu.finbot.application.configuration.service.ProviderModelCatalogService;
import io.omnnu.finbot.application.exchange.port.in.ExchangeAccountControlUseCase;
import io.omnnu.finbot.application.exchange.port.in.ExchangeAccountSyncUseCase;
import io.omnnu.finbot.application.exchange.port.in.OrderReconciliationUseCase;
import io.omnnu.finbot.application.exchange.port.in.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.exchange.port.out.ExchangeAccountConfigurationRepository;
import io.omnnu.finbot.application.exchange.port.out.ExchangeAccountControlRepository;
import io.omnnu.finbot.application.exchange.port.out.ExchangeAccountGateway;
import io.omnnu.finbot.application.exchange.port.out.ExchangeCapabilityQuery;
import io.omnnu.finbot.application.exchange.port.out.ExchangeSyncCursorStore;
import io.omnnu.finbot.application.exchange.port.out.OmsExecutionStore;
import io.omnnu.finbot.application.exchange.port.out.OrderReconciliationStore;
import io.omnnu.finbot.application.exchange.port.out.PaperExchangeGateway;
import io.omnnu.finbot.application.exchange.service.ExchangeAccountControlService;
import io.omnnu.finbot.application.exchange.service.ExchangeAccountSyncService;
import io.omnnu.finbot.application.exchange.service.OrderReconciliationService;
import io.omnnu.finbot.application.exchange.service.PaperOrderExecutionService;
import io.omnnu.finbot.application.experiment.port.in.AiExperimentUseCase;
import io.omnnu.finbot.application.experiment.port.out.AiExperimentRepository;
import io.omnnu.finbot.application.experiment.service.AiExperimentService;
import io.omnnu.finbot.application.identity.port.in.AdminApiTokenUseCase;
import io.omnnu.finbot.application.identity.port.in.AuthenticationUseCase;
import io.omnnu.finbot.application.identity.port.out.AdminApiTokenStore;
import io.omnnu.finbot.application.identity.port.out.AdminCredentialVerifier;
import io.omnnu.finbot.application.identity.port.out.AuthenticationCryptography;
import io.omnnu.finbot.application.identity.port.out.AuthenticationStore;
import io.omnnu.finbot.application.identity.service.AdminApiTokenApplicationService;
import io.omnnu.finbot.application.identity.service.AuthenticationApplicationService;
import io.omnnu.finbot.application.identity.service.AuthenticationPolicy;
import io.omnnu.finbot.application.ingestion.port.in.CrawlerHeaderProfileUseCase;
import io.omnnu.finbot.application.ingestion.port.in.IngestionUseCase;
import io.omnnu.finbot.application.ingestion.port.out.CrawlerHeaderProfileRepository;
import io.omnnu.finbot.application.ingestion.port.out.CrawlerHeaderProfileResolver;
import io.omnnu.finbot.application.ingestion.port.out.EvidenceNormalizer;
import io.omnnu.finbot.application.ingestion.port.out.IngestionRepository;
import io.omnnu.finbot.application.ingestion.port.out.SourceCollectionGateway;
import io.omnnu.finbot.application.ingestion.port.out.SourceRuntimeHealthGateway;
import io.omnnu.finbot.application.ingestion.service.CrawlerAccessChallengeDetector;
import io.omnnu.finbot.application.ingestion.service.CrawlerHeaderProfileService;
import io.omnnu.finbot.application.ingestion.service.IngestionApplicationService;
import io.omnnu.finbot.application.ledger.port.in.TradingLedgerQueryUseCase;
import io.omnnu.finbot.application.ledger.port.out.TradingLedgerQueryRepository;
import io.omnnu.finbot.application.ledger.port.out.TradingLedgerWriter;
import io.omnnu.finbot.application.ledger.service.TradingLedgerQueryService;
import io.omnnu.finbot.application.market.port.in.MarketDataRefreshUseCase;
import io.omnnu.finbot.application.market.port.in.MarketDataUseCase;
import io.omnnu.finbot.application.market.port.out.MarketDataArtifactEncoder;
import io.omnnu.finbot.application.market.port.out.MarketDataArtifactUriFactory;
import io.omnnu.finbot.application.market.port.out.MarketDataGateway;
import io.omnnu.finbot.application.market.port.out.MarketDataRepository;
import io.omnnu.finbot.application.market.service.MarketDataApplicationService;
import io.omnnu.finbot.application.network.port.in.NetworkDiagnosticsUseCase;
import io.omnnu.finbot.application.network.port.out.NetworkDiagnosticStore;
import io.omnnu.finbot.application.network.port.out.NetworkProbeGateway;
import io.omnnu.finbot.application.network.port.out.ProxyRouteResolver;
import io.omnnu.finbot.application.network.service.NetworkDiagnosticsService;
import io.omnnu.finbot.application.operations.port.out.BackgroundTaskStore;
import io.omnnu.finbot.application.operations.port.out.OperationsRepository;
import io.omnnu.finbot.application.operations.service.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.quant.port.in.QuantResearchUseCase;
import io.omnnu.finbot.application.quant.port.in.TradeRiskPreviewUseCase;
import io.omnnu.finbot.application.quant.port.out.QuantResearchGateway;
import io.omnnu.finbot.application.quant.port.out.QuantResearchStore;
import io.omnnu.finbot.application.quant.service.QuantResearchApplicationService;
import io.omnnu.finbot.application.quant.service.TradeRiskPreviewService;
import io.omnnu.finbot.application.research.port.in.CompressionUseCase;
import io.omnnu.finbot.application.research.port.in.ForecastEvaluationUseCase;
import io.omnnu.finbot.application.research.port.in.ResearchForecastUseCase;
import io.omnnu.finbot.application.research.port.in.ResearchLaunchUseCase;
import io.omnnu.finbot.application.research.port.in.ResearchPipelineUseCase;
import io.omnnu.finbot.application.research.port.out.CompressionOutputParser;
import io.omnnu.finbot.application.research.port.out.CompressionRepository;
import io.omnnu.finbot.application.research.port.out.ResearchForecastRepository;
import io.omnnu.finbot.application.research.port.out.ResearchHistoryRepository;
import io.omnnu.finbot.application.research.port.out.ResearchSegmentationStore;
import io.omnnu.finbot.application.research.service.CompressionApplicationService;
import io.omnnu.finbot.application.research.service.ForecastEvaluationService;
import io.omnnu.finbot.application.research.service.ResearchForecastService;
import io.omnnu.finbot.application.research.service.ResearchLaunchService;
import io.omnnu.finbot.application.research.service.ResearchPipelineService;
import io.omnnu.finbot.application.research.service.ResearchSegmentationService;
import io.omnnu.finbot.application.research.service.StoredResearchWorkflowPlanQuery;
import io.omnnu.finbot.application.review.port.in.ResearchReviewUseCase;
import io.omnnu.finbot.application.review.port.out.ResearchFeedbackStore;
import io.omnnu.finbot.application.review.service.ResearchReviewService;
import io.omnnu.finbot.application.setup.port.in.SetupProfileUseCase;
import io.omnnu.finbot.application.setup.port.out.SetupProfileRepository;
import io.omnnu.finbot.application.setup.service.SetupProfileService;
import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.application.trading.port.in.TradeAutomationUseCase;
import io.omnnu.finbot.application.trading.port.out.TradeAutomationStore;
import io.omnnu.finbot.application.trading.port.out.TradeDecisionOutputParser;
import io.omnnu.finbot.application.trading.service.TradeAutomationApplicationService;
import io.omnnu.finbot.application.workflow.port.in.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.port.in.SdbScaDebateRunner;
import io.omnnu.finbot.application.workflow.port.in.WorkflowDiagnosticsUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowManagementUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowRunFailureUseCase;
import io.omnnu.finbot.application.workflow.port.in.WorkflowRunResumeUseCase;
import io.omnnu.finbot.application.workflow.port.out.StructuredAiOutputParser;
import io.omnnu.finbot.application.workflow.port.out.DebateProtocolStore;
import io.omnnu.finbot.application.workflow.port.out.SdbScaDocumentCodec;
import io.omnnu.finbot.application.workflow.port.out.SdbScaOutputParser;
import io.omnnu.finbot.application.workflow.port.out.WorkflowCommandStore;
import io.omnnu.finbot.application.workflow.port.out.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.port.out.WorkflowExecutionStore;
import io.omnnu.finbot.application.workflow.port.out.WorkflowManagementRepository;
import io.omnnu.finbot.application.workflow.port.out.WorkflowRunQuery;
import io.omnnu.finbot.application.workflow.port.out.WorkflowRunResumeStore;
import io.omnnu.finbot.application.workflow.service.WorkflowDiagnosticsService;
import io.omnnu.finbot.application.workflow.service.SdbScaDebateExecutionService;
import io.omnnu.finbot.application.workflow.service.WorkflowExecutionService;
import io.omnnu.finbot.application.workflow.service.WorkflowManagementService;
import io.omnnu.finbot.application.workflow.service.WorkflowRunApplicationService;
import io.omnnu.finbot.application.workflow.service.WorkflowRunFailureService;
import io.omnnu.finbot.application.workflow.service.WorkflowRunResumeService;
import io.omnnu.finbot.application.workspace.port.in.PlatformWorkspaceUseCase;
import io.omnnu.finbot.application.workspace.port.out.PlatformWorkspaceRepository;
import io.omnnu.finbot.application.workspace.service.PlatformWorkspaceService;
import io.omnnu.finbot.configuration.properties.AuthenticationProperties;
import io.omnnu.finbot.configuration.properties.TradingLedgerProperties;
import io.omnnu.finbot.domain.risk.EstimatedTradeEngine;
import io.omnnu.finbot.domain.risk.MarginRiskEngine;
import io.omnnu.finbot.infrastructure.identity.adapter.EncodedAdminCredentialVerifier;
import io.omnnu.finbot.infrastructure.identity.adapter.MonotonicSortableIdGenerator;
import io.omnnu.finbot.infrastructure.ingestion.client.CrawlerRequestHeaderPolicy;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    AdminApiTokenUseCase adminApiTokenUseCase(
            SortableIdGenerator idGenerator,
            AdminApiTokenStore store,
            AuthenticationCryptography cryptography,
            Clock clock) {
        return new AdminApiTokenApplicationService(idGenerator, store, cryptography, clock);
    }

    @Bean
    ConfigurationUseCase configurationUseCase(
            ConfigurationRepository repository,
            EnvironmentValueResolver environmentValueResolver,
            io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore runtimeSecretStore,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new ConfigurationApplicationService(
                repository, environmentValueResolver, runtimeSecretStore, idGenerator, clock);
    }

    @Bean
    io.omnnu.finbot.application.configuration.port.in.RuntimeSecretManagementUseCase runtimeSecretManagementUseCase(
            io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore store,
            io.omnnu.finbot.application.configuration.port.out.RuntimeSecretTargetRepository targets,
            Clock clock) {
        return new io.omnnu.finbot.application.configuration.service.RuntimeSecretManagementService(
                store, targets, clock);
    }

    @Bean
    ProviderModelCatalogUseCase providerModelCatalogUseCase(
            ConfigurationRepository configuration,
            EnvironmentValueResolver environment,
            io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore runtimeSecretStore,
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
            io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore runtimeSecretStore,
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
    AiExecutionPolicyExecutor aiExecutionPolicyExecutor(
            WorkflowAiInvoker aiInvoker,
            Clock clock) {
        return new AiExecutionPolicyExecutor(aiInvoker, clock);
    }

    @Bean
    WorkflowRunFailureUseCase workflowRunFailureUseCase(
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher) {
        return new WorkflowRunFailureService(executionStore, eventPublisher);
    }

    @Bean
    SdbScaDebateRunner sdbScaDebateRunner(
            WorkflowExecutionStore executionStore,
            DebateProtocolStore protocolStore,
            AiExecutionPolicyExecutor aiExecution,
            SdbScaOutputParser outputParser,
            SdbScaDocumentCodec documentCodec,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new SdbScaDebateExecutionService(
                executionStore,
                protocolStore,
                aiExecution,
                outputParser,
                documentCodec,
                clock,
                executor);
    }

    @Bean
    WorkflowExecutionUseCase workflowExecutionUseCase(
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowRunFailureUseCase workflowFailure,
            AiExecutionPolicyExecutor aiExecution,
            StructuredAiOutputParser outputParser,
            SdbScaDebateRunner sdbScaDebateRunner,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new WorkflowExecutionService(
                executionStore,
                eventPublisher,
                workflowFailure,
                aiExecution,
                outputParser,
                sdbScaDebateRunner,
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
    io.omnnu.finbot.application.network.port.in.ProxyGatewayControlUseCase proxyGatewayControlUseCase(
            io.omnnu.finbot.application.network.port.out.ProxyGatewayProfileRepository profiles,
            io.omnnu.finbot.application.configuration.port.out.RuntimeSecretStore runtimeSecretStore,
            io.omnnu.finbot.application.network.port.out.ProxyGatewayControlGateway gateway,
            Clock clock) {
        return new io.omnnu.finbot.application.network.service.ProxyGatewayControlService(
                profiles, runtimeSecretStore, gateway, clock);
    }

    @Bean
    CrawlerHeaderProfileUseCase crawlerHeaderProfileUseCase(
            CrawlerHeaderProfileRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new CrawlerHeaderProfileService(repository, idGenerator, clock);
    }

    @Bean
    CrawlerRequestHeaderPolicy crawlerRequestHeaderPolicy(
            CrawlerHeaderProfileResolver resolver) {
        return new CrawlerRequestHeaderPolicy(resolver);
    }

    @Bean
    CrawlerAccessChallengeDetector crawlerAccessChallengeDetector() {
        return new CrawlerAccessChallengeDetector();
    }

    @Bean
    IngestionUseCase ingestionUseCase(
            IngestionRepository repository,
            CrawlerHeaderProfileRepository headerProfiles,
            SourceCollectionGateway collectionGateway,
            EvidenceNormalizer evidenceNormalizer,
            SourceRuntimeHealthGateway runtimeHealthGateway,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new IngestionApplicationService(
                repository,
                headerProfiles,
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
            AiExecutionPolicyExecutor aiExecution,
            CompressionOutputParser outputParser,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new CompressionApplicationService(
                repository,
                workflowStore,
                aiExecution,
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
            io.omnnu.finbot.application.trading.port.out.TradeAutomationConfigurationRepository configuration,
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
            AiExecutionPolicyExecutor aiExecution,
            TradeDecisionOutputParser outputParser,
            TradeAutomationStore store,
            PaperOrderExecutionUseCase orderExecution,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new TradeAutomationApplicationService(
                workflowStore,
                aiExecution,
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
