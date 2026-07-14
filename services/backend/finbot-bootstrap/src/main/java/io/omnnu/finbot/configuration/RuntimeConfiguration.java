package io.omnnu.finbot.configuration;

import io.omnnu.finbot.application.ai.AiBudgetReservationStore;
import io.omnnu.finbot.application.ai.AiCompletionGateway;
import io.omnnu.finbot.application.ai.AiInvocationAuditStore;
import io.omnnu.finbot.application.ai.AiProviderProtocolResolver;
import io.omnnu.finbot.application.ai.WorkflowAiInvoker;
import io.omnnu.finbot.application.identity.AdminCredentialVerifier;
import io.omnnu.finbot.application.ingestion.EvidenceNormalizer;
import io.omnnu.finbot.application.ingestion.IngestionApplicationService;
import io.omnnu.finbot.application.ingestion.IngestionRepository;
import io.omnnu.finbot.application.ingestion.IngestionUseCase;
import io.omnnu.finbot.application.ingestion.SourceCollectionGateway;
import io.omnnu.finbot.application.identity.AuthenticationApplicationService;
import io.omnnu.finbot.application.identity.AuthenticationCryptography;
import io.omnnu.finbot.application.identity.AuthenticationPolicy;
import io.omnnu.finbot.application.identity.AuthenticationStore;
import io.omnnu.finbot.application.identity.AuthenticationUseCase;
import io.omnnu.finbot.application.configuration.ConfigurationApplicationService;
import io.omnnu.finbot.application.configuration.ConfigurationRepository;
import io.omnnu.finbot.application.configuration.ConfigurationUseCase;
import io.omnnu.finbot.application.configuration.EnvironmentValueResolver;
import io.omnnu.finbot.application.exchange.OmsExecutionStore;
import io.omnnu.finbot.application.exchange.ExchangeAccountGateway;
import io.omnnu.finbot.application.exchange.ExchangeAccountSyncService;
import io.omnnu.finbot.application.exchange.ExchangeAccountSyncUseCase;
import io.omnnu.finbot.application.exchange.ExchangeSyncCursorStore;
import io.omnnu.finbot.application.exchange.PaperExchangeGateway;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionService;
import io.omnnu.finbot.application.exchange.PaperOrderExecutionUseCase;
import io.omnnu.finbot.application.exchange.OrderReconciliationService;
import io.omnnu.finbot.application.exchange.OrderReconciliationStore;
import io.omnnu.finbot.application.exchange.OrderReconciliationUseCase;
import io.omnnu.finbot.application.catalog.CatalogApplicationService;
import io.omnnu.finbot.application.catalog.CatalogRepository;
import io.omnnu.finbot.application.catalog.CatalogUseCase;
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
import io.omnnu.finbot.application.operations.BackgroundTaskCoordinator;
import io.omnnu.finbot.application.operations.BackgroundTaskStore;
import io.omnnu.finbot.application.quant.QuantResearchApplicationService;
import io.omnnu.finbot.application.quant.QuantResearchGateway;
import io.omnnu.finbot.application.quant.QuantResearchStore;
import io.omnnu.finbot.application.quant.QuantResearchUseCase;
import io.omnnu.finbot.application.workflow.WorkflowManagementRepository;
import io.omnnu.finbot.application.workflow.WorkflowManagementService;
import io.omnnu.finbot.application.workflow.WorkflowManagementUseCase;
import io.omnnu.finbot.application.research.CompressionApplicationService;
import io.omnnu.finbot.application.research.CompressionOutputParser;
import io.omnnu.finbot.application.research.CompressionRepository;
import io.omnnu.finbot.application.research.CompressionUseCase;
import io.omnnu.finbot.application.research.ResearchPipelineService;
import io.omnnu.finbot.application.research.ResearchPipelineUseCase;
import io.omnnu.finbot.application.research.ResearchLaunchService;
import io.omnnu.finbot.application.research.ResearchLaunchUseCase;
import io.omnnu.finbot.application.workflow.StructuredAiOutputParser;
import io.omnnu.finbot.application.workflow.WorkflowEventPublisher;
import io.omnnu.finbot.application.workflow.WorkflowExecutionService;
import io.omnnu.finbot.application.workflow.WorkflowExecutionStore;
import io.omnnu.finbot.application.workflow.WorkflowExecutionUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunFailureService;
import io.omnnu.finbot.application.workflow.WorkflowRunFailureUseCase;
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
            Clock clock) {
        return new ConfigurationApplicationService(repository, environmentValueResolver, clock);
    }

    @Bean
    CatalogUseCase catalogUseCase(
            CatalogRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new CatalogApplicationService(repository, idGenerator, clock);
    }

    @Bean
    TradingLedgerQueryUseCase tradingLedgerQueryUseCase(
            TradingLedgerQueryRepository repository,
            EnvironmentValueResolver environmentValueResolver,
            Clock clock,
            TradingLedgerProperties properties) {
        return new TradingLedgerQueryService(
                repository,
                environmentValueResolver,
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
    WorkflowManagementUseCase workflowManagementUseCase(
            WorkflowManagementRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new WorkflowManagementService(repository, idGenerator, clock);
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
                .version(HttpClient.Version.HTTP_2)
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
    ResearchPipelineUseCase researchPipelineUseCase(
            StartWorkflowUseCase startWorkflow,
            IngestionUseCase ingestion,
            CompressionUseCase compression,
            MarketDataUseCase marketData,
            QuantResearchUseCase quantResearch,
            WorkflowExecutionUseCase workflowExecution,
            WorkflowRunFailureUseCase workflowFailure,
            TradeAutomationUseCase tradeAutomation,
            Clock clock) {
        return new ResearchPipelineService(
                startWorkflow,
                ingestion,
                compression,
                marketData,
                quantResearch,
                workflowExecution,
                workflowFailure,
                tradeAutomation,
                clock);
    }

    @Bean
    ResearchLaunchUseCase researchLaunchUseCase(
            StartWorkflowUseCase startWorkflow,
            BackgroundTaskCoordinator tasks) {
        return new ResearchLaunchService(startWorkflow, tasks);
    }

    @Bean
    WorkflowAiInvoker workflowAiInvoker(
            AiCompletionGateway completionGateway,
            AiProviderProtocolResolver protocolResolver,
            AiInvocationAuditStore auditStore,
            AiBudgetReservationStore budgetStore,
            WorkflowEventPublisher eventPublisher,
            SortableIdGenerator idGenerator,
            Clock clock) {
        return new WorkflowAiInvoker(
                completionGateway,
                protocolResolver,
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
    IngestionUseCase ingestionUseCase(
            IngestionRepository repository,
            SourceCollectionGateway collectionGateway,
            EvidenceNormalizer evidenceNormalizer,
            SortableIdGenerator idGenerator,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new IngestionApplicationService(
                repository,
                collectionGateway,
                evidenceNormalizer,
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
            MarketDataArtifactEncoder artifactEncoder,
            MarketDataArtifactUriFactory artifactUriFactory,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new MarketDataApplicationService(
                repository,
                gateway,
                artifactEncoder,
                artifactUriFactory,
                clock,
                executor);
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
                clock,
                executor);
    }

    @Bean
    ExchangeAccountSyncUseCase exchangeAccountSyncUseCase(
            ExchangeAccountGateway gateway,
            ExchangeSyncCursorStore cursors,
            TradingLedgerWriter ledger,
            Clock clock,
            @Qualifier("workflowVirtualThreadExecutor") Executor executor) {
        return new ExchangeAccountSyncService(gateway, cursors, ledger, clock, executor);
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
