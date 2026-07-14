export type WorkflowRunStatus = 'ACCEPTED' | 'RUNNING' | 'WAITING_HUMAN' | 'PARTIAL' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type BackgroundTaskStatus = 'PENDING' | 'CLAIMED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type ReasoningEffort = 'PROVIDER_DEFAULT' | 'NONE' | 'MINIMAL' | 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX';

export interface AuthStatus {
  authenticated: boolean;
  username: string | null;
  expiresAt: string | null;
  csrfToken: string;
}

export interface AuthChallenge {
  challengeId: string;
  nonce: string;
  proofOfWorkAlgorithm: string;
  proofOfWorkDifficulty: number;
  mathExpression: string;
  expiresAt: string;
}

export interface TaskRecord {
  taskId: string;
  taskType: string;
  status: BackgroundTaskStatus;
  priority: number;
  payloadSummary: string;
  attemptCount: number;
  maximumAttempts: number;
  availableAt: string;
  claimedAt: string | null;
  leaseExpiresAt: string | null;
  claimOwner: string | null;
  heartbeatAt: string | null;
  completedAt: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ScheduleRecord {
  scheduleId: string;
  displayName: string;
  taskType: string;
  enabled: boolean;
  intervalSeconds: number;
  priority: number;
  maximumAttempts: number;
  nextRunAt: string;
  lastScheduledAt: string | null;
  version: number;
  updatedAt: string;
}

export interface WorkerRecord {
  workerId: string;
  instanceName: string;
  status: string;
  startedAt: string;
  heartbeatAt: string;
  stoppedAt: string | null;
}

export interface OperationsOverview {
  taskCounts: Partial<Record<BackgroundTaskStatus, number>>;
  schedules: ScheduleRecord[];
  workers: WorkerRecord[];
  legacyImports: Array<{
    importId: string;
    sourceName: string;
    sourceSha256: string;
    status: string;
    sourceTableCount: number;
    sourceRowCount: number;
    archivedRowCount: number;
    transformedRowCount: number;
    startedAt: string;
    completedAt: string | null;
    errorSummary: string | null;
  }>;
  generatedAt: string;
}

export interface ResearchLaunch {
  runId: string;
  workflowStatus: WorkflowRunStatus;
  taskId: string;
  taskStatus: BackgroundTaskStatus;
  acceptedAt: string;
  statusUrl: string;
  eventsUrl: string;
  taskUrl: string;
}

export interface WorkflowRun {
  runId: string;
  workflowType: string;
  status: WorkflowRunStatus;
  trigger: string;
  requestSummary: string;
  acceptedAt: string;
  updatedAt: string;
}

export interface ResearchSummary {
  runId: string;
  workflowType: string;
  status: WorkflowRunStatus;
  trigger: string;
  requestSummary: string;
  workflowVersionId: string;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  acceptedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export interface ResearchHistoryDetail {
  summary: ResearchSummary;
  events: Array<{ sequence: number; eventType: string; payloadJson: string; occurredAt: string }>;
  checkpoints: Array<{
    nodeId: string; displayName: string; round: number; attempt: number; status: string;
    resultSummary: string | null; errorCode: string | null; errorMessage: string | null;
    startedAt: string | null; completedAt: string | null; updatedAt: string;
  }>;
  agentTurns: Array<{
    messageId: string; nodeId: string; roleName: string; round: number; turnIndex: number;
    messageType: string; status: string; summary: string; argument: string; confidence: number | null;
    claimsJson: string; evidenceReferencesJson: string; challengesJson: string;
    revisionNotesJson: string; createdAt: string;
  }>;
  aiInvocations: Array<{
    invocationId: string; nodeId: string; providerProfileId: string; modelName: string;
    reasoningEffort: ReasoningEffort; status: string; inputTokens: number; outputTokens: number;
    estimatedCostUsd: number; latencyMilliseconds: number | null; finishReason: string | null;
    errorCode: string | null; errorMessage: string | null; startedAt: string; completedAt: string | null;
  }>;
  artifacts: Array<{ artifactId: string; artifactType: string; schemaVersion: number; contentHash: string; createdAt: string }>;
  quantRuns: Array<{
    researchRunId: string; researchKind: string; strategyId: string; strategyVersion: string;
    status: string; observationCount: number; resultFingerprint: string | null; metricsJson: string;
    errorCode: string | null; errorMessage: string | null; requestedAt: string; completedAt: string | null;
  }>;
}

export interface WorkflowEvent {
  eventId: { value: string } | string;
  runId: { value: string } | string;
  sequence: number;
  occurredAt: string;
  [key: string]: unknown;
}

export interface TradeAutomationSummary {
  automationRunId: string;
  workflowRunId: string;
  status: string;
  symbol: string | null;
  action: string | null;
  confidence: number | null;
  orderCount: number;
  errorCode: string | null;
  statusMessage: string | null;
  startedAt: string;
  completedAt: string | null;
}

export interface TradeAutomationDetail {
  summary: TradeAutomationSummary;
  decision: null | {
    decisionId: string; decisionKind: string; symbol: string; action: string; confidence: number;
    entryReference: number | null; targetPrice: number | null; invalidationPrice: number | null;
    rationaleJson: string; proposalId: string | null; proposalStatus: string | null; createdAt: string;
  };
  aiReviews: Array<{
    reviewId: string; stage: string; status: string; invocationId: string | null;
    providerProfileId: string | null; modelName: string | null; reasoningEffort: ReasoningEffort | null;
    outputJson: string | null; outputHash: string | null; errorCode: string | null;
    errorMessage: string | null; createdAt: string;
  }>;
  riskAssessments: Array<{
    assessmentId: string; proposalId: string; accountId: string; policyVersion: string; status: string;
    reasonsJson: string; quantity: number | null; notionalUsdt: number | null; leverage: number | null;
    initialMarginUsdt: number | null; estimatedMaximumLossUsdt: number | null;
    approximateLiquidationPrice: number | null; assessedAt: string;
  }>;
  orders: Array<{
    orderId: string; intentId: string; exchange: string; environment: string; accountId: string;
    symbol: string; side: string; status: string; requestedQuantity: number; filledQuantity: number;
    averageFillPrice: number | null; leverage: number; clientOrderId: string | null;
    exchangeOrderId: string | null; submittedAt: string | null; terminalAt: string | null;
    createdAt: string; updatedAt: string;
    events: Array<{ eventId: string; sequence: number; eventType: string; fromStatus: string | null; toStatus: string; payloadJson: string; occurredAt: string }>;
    submissionAttempts: Array<{
      attemptId: string; attemptNumber: number; requestHash: string; status: string;
      exchangeOrderId: string | null; httpStatus: number | null; responseJson: string | null;
      errorCode: string | null; errorMessage: string | null; startedAt: string; completedAt: string | null;
    }>;
  }>;
}

export interface RiskPolicy {
  version: string;
  testEnvironmentOnly: boolean;
  minimumConfidence: number;
  riskBudgetUsdt: number;
  maximumNotionalUsdt: number;
  preferredLeverage: number;
  maximumLeverage: number;
  maximumOpenPositions: number;
  maximumStopDistance: number;
  takerFeeRate: number;
  slippageRate: number;
  liquidationBufferRate: number;
}

export interface ExecutionAiStage {
  stage: 'DRAFT' | 'REFLECTION';
  primaryAiBinding: AiModelBinding;
  fallbackAiBinding: AiModelBinding | null;
  systemPrompt: string;
  userPromptTemplate: string;
  maximumOutputTokens: number;
  timeoutSeconds: number;
  retryPolicy: { maximumAttempts: number; backoff: number | string };
  enabled: boolean;
  version: number;
}

export interface AiModelBinding {
  providerProfileId: { value: string } | string;
  modelName: string;
  reasoningEffort: ReasoningEffort;
}

export interface TradeAutomationConfiguration {
  aiStages: ExecutionAiStage[];
  activeRiskPolicy: RiskPolicy;
}

export interface AccountOverview {
  accountId: string;
  exchange: string;
  environment: string;
  displayName: string;
  proxyRoute: string;
  enabled: boolean;
  version: number;
  credentialConfigured: boolean;
  dataStatus: string;
  currency: string;
  equity: number;
  availableBalance: number;
  marginBalance: number;
  unrealizedPnl: number;
  realizedPnl: number;
  openPositionCount: number;
  snapshotAt: string | null;
}

export interface AccountsOverview {
  range: { fromInclusive: string; toExclusive: string };
  currency: string;
  totalEquity: number;
  totalAvailableBalance: number;
  totalMarginBalance: number;
  totalUnrealizedPnl: number;
  totalRealizedPnl: number;
  accounts: AccountOverview[];
  generatedAt: string;
}

export interface PositionRecord {
  accountId: string; symbol: string; side: string; quantity: number; entryPrice: number;
  markPrice: number; liquidationPrice: number | null; leverage: number; unrealizedPnl: number;
  margin: number; occurredAt: string;
}

export interface ActivityRecord {
  activityId: string; sourceEventId: string; activityType: string; source: string;
  accountId: string; exchange: string; symbol: string | null; status: string | null;
  side: string | null; quantity: number | null; price: number | null; amount: number | null;
  currency: string | null; exchangeOrderId: string | null; clientOrderId: string | null;
  occurredAt: string; receivedAt: string;
}

export interface ProductSummary {
  productId: string; baseAsset: string; quoteAsset: string; displayName: string;
  category: string; status: string; instrumentCount: number; highestWatchlistMode: string | null;
}

export interface ProductPage { products: ProductSummary[]; nextCursor: string | null }
export interface InstrumentRecord {
  instrumentId: string; exchange: string; marketType: string; symbol: string; settlementAsset: string;
  contractSize: number; priceTick: number; quantityStep: number; minimumQuantity: number;
  maximumLeverage: number; executionEnabled: boolean; status: string; metadataUpdatedAt: string;
}
export interface ProductDetail extends ProductSummary {
  instruments: InstrumentRecord[];
  watchlists: Array<{ watchlistId: string; watchlistName: string; researchMode: string; preferredInstrumentId: string | null; note: string }>;
}
export interface WatchlistSummary { watchlistId: string; name: string; description: string; defaultWatchlist: boolean; itemCount: number; version: number; updatedAt: string }
export interface WatchlistDetail extends WatchlistSummary { items: Array<{ productId: string; displayName: string; baseAsset: string; quoteAsset: string; researchMode: string; preferredInstrumentId: string | null; note: string; updatedAt: string }> }

export interface WorkflowDefinitionSummary {
  definitionId: string; name: string; description: string; builtIn: boolean; active: boolean;
  publishedVersionId: string | null; publishedVersionNumber: number | null;
  draftVersionId: string | null; draftVersionNumber: number | null; updatedAt: string;
}
export interface WorkflowNode {
  nodeId: string; nodeType: string; displayName: string; roleName: string | null;
  roleTemplateId: string | null; primaryAiBinding: AiModelBinding | null;
  fallbackAiBinding: AiModelBinding | null; systemPrompt: string | null; userPromptTemplate: string | null;
  outputContract: string | null; contextMode: string; contextHistoryRounds: number;
  contextMaximumMessages: number; maximumOutputTokens: number; timeoutSeconds: number;
  retryMaximumAttempts: number; retryBackoffSeconds: number; operation: string | null;
  positionX: number; positionY: number; enabled: boolean;
}
export interface WorkflowEdge {
  edgeId: string; sourceNodeId: string; targetNodeId: string; activationMode: string;
  contextMode: string; condition: unknown | null; loopEdge: boolean; maximumTraversals: number | null;
}
export interface WorkflowVersion {
  versionId: string; definitionId: string; versionNumber: number; status: string;
  defaultDebateRounds: number; maximumSteps: number; maximumDurationSeconds: number;
  maximumTokens: number; maximumCostUsd: number; failurePolicy: string; checksum: string;
  publishedAt: string | null; createdAt: string; createdBy: string; nodes: WorkflowNode[]; edges: WorkflowEdge[];
}
export interface WorkflowSchema {
  nodeTypes: string[]; reasoningEfforts: ReasoningEffort[]; contextModes: string[];
  edgeContextModes: string[]; conditionOperators: string[]; outputContracts: string[]; failurePolicies: string[];
}

export interface SystemSetting { key: string; type: string; value: string; source: string; description: string; version: number; updatedAt: string }
export interface AiProvider {
  profileId: string; displayName: string; protocol: string; reasoningParameterStyle: string;
  baseUrl: string | null; baseUrlEnv: string | null; apiKeyEnv: string; baseUrlConfigured: boolean;
  apiKeyConfigured: boolean; enabled: boolean; connectTimeoutSeconds: number;
  requestTimeoutSeconds: number; version: number; updatedAt: string;
}
export interface AiModel {
  modelProfileId: string; providerProfileId: string; modelName: string;
  defaultReasoningEffort: ReasoningEffort; inputUsdPerMillion: number;
  outputUsdPerMillion: number; enabled: boolean; version: number; updatedAt: string;
}
export interface ConfigurationSnapshot { settings: SystemSetting[]; providers: AiProvider[]; models: AiModel[] }

export interface SourceRecord { sourceId: string; displayName: string; mode: string; tier: string; category: string; provider: string; trustWeight: number; pollIntervalSeconds: number; priority: string; assetScope: string[]; outboundRoute: string | null; enabled: boolean; version: number }
export interface EvidenceDocument { documentId: string; evidenceId: string; sourceId: string; sourceTier: string; category: string; trustWeight: number; canonicalUrl: string | null; title: string; language: string; excerpt: string; assetScope: string[]; publishedAt: string | null; fetchedAt: string }
