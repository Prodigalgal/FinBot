export type ControlPlaneRequestPath = `/api/v2${string}`;

export type WorkflowRunStatus = 'ACCEPTED' | 'RUNNING' | 'WAITING_HUMAN' | 'PARTIAL' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type BackgroundTaskStatus = 'PENDING' | 'CLAIMED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type ReasoningEffort = 'PROVIDER_DEFAULT' | 'NONE' | 'MINIMAL' | 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX';
export type WorkflowFailurePolicy = 'STOP' | 'CONTINUE' | 'REPLAN';
export type WorkflowOutputContract = 'TEXT' | 'RESEARCH_FINDINGS' | 'DEBATE_ARGUMENT' | 'RISK_ASSESSMENT' | 'CHAIR_VERDICT' | 'CONSENSUS_RESULT' | 'TRADE_DECISIONS' | 'EXECUTION_VERDICT';
export type WorkflowNodeType = 'INPUT' | 'ROUTER' | 'DETERMINISTIC' | 'COLLECTOR' | 'CLEANER' | 'AI_CLEANER' | 'COMPRESSOR' | 'COMPRESSION_VALIDATOR' | 'AGENT' | 'GATE' | 'QUANT' | 'RISK' | 'SUBFLOW' | 'HUMAN_REVIEW' | 'AGGREGATOR' | 'CHAIR' | 'SOCIAL_CHOICE' | 'EXECUTION_REVIEW' | 'OUTPUT';
export type DebateProtocol = 'LEGACY_CHAIR_V1' | 'SDB_SCA_V1';
export interface DebateProtocolConfiguration {
  protocol: DebateProtocol;
  minimumParticipantSeats: number;
  minimumQuorumRoles: number;
  stageTimeoutSeconds: number;
  critiqueAssignmentPolicy: 'FULL_MATRIX' | 'BALANCED_INCOMPLETE';
}

export interface DebateProtocolTrace {
  debateId: string;
  protocol: DebateProtocol;
  phases: Array<{
    phaseType: 'PROPOSAL' | 'CRITIQUE' | 'REVISION' | 'BALLOT' | 'AGGREGATION';
    status: 'PENDING' | 'OPEN' | 'REVEALING' | 'REVEALED' | 'COMPLETED' | 'FAILED';
    requiredTasks: number;
    terminalTasks: number;
    pendingTasks: number;
    claimedTasks: number;
    completedTasks: number;
    failedTasks: number;
    timedOutTasks: number;
    cancelledTasks: number;
    deadline: string;
    openedAt: string | null;
    revealedAt: string | null;
    completedAt: string | null;
    recoveryPoint: boolean;
  }>;
  artifacts: Array<{
    artifactId: string;
    phaseType: 'PROPOSAL' | 'CRITIQUE' | 'REVISION' | 'BALLOT' | 'AGGREGATION';
    candidateAlias: string | null;
    targetCandidateAlias: string | null;
    status: 'SEALED' | 'REVEALED' | 'REJECTED';
    contentHash: string;
    contentJson: string;
    sealedAt: string;
    revealedAt: string | null;
  }>;
  ballots: Array<{
    orientation: 'FORWARD' | 'REVERSED';
    preferenceTiersJson: string;
    contentHash: string;
    createdAt: string;
  }>;
  decision: {
    status: 'SELECTED' | 'TIED' | 'LOW_QUORUM' | 'ORDER_SENSITIVE' | 'NO_VALID_BALLOTS' | 'NO_STRICT_WINNER';
    winnerCandidateAlias: string | null;
    contributingRoleCount: number;
    undefeatedCandidatesJson: string;
    pairwiseMatrixJson: string;
    strongestPathsJson: string;
    rankingJson: string;
    forecastJson: string | null;
    explanation: string | null;
    decisionHash: string;
    decidedAt: string;
  } | null;
}

export interface ExchangeAccountControl {
  accountId: string;
  exchange: 'GATE' | 'BYBIT';
  environment: 'TESTNET' | 'DEMO';
  displayName: string;
  enabled: boolean;
  version: number;
  updatedAt: string;
}

export interface ExchangeAccountSyncResult {
  accountId: { value: string };
  factCount: number;
  watermark: string;
  complete: boolean;
  warnings: string[];
}

export interface RuntimeSecretStatus {
  scope: 'AI_PROVIDER' | 'EXCHANGE_ACCOUNT' | 'PROXY_ROUTE' | 'PROXY_GATEWAY' | 'INFORMATION_SOURCE';
  targetId: string;
  secretName: string;
  source: 'DATABASE_OVERRIDE' | 'ENVIRONMENT_FALLBACK' | 'UNCONFIGURED';
  configured: boolean;
  fingerprint: string | null;
  version: number;
  updatedAt: string | null;
}

export interface ProxyGatewayProfile {
  gatewayId: string;
  displayName: string;
  controlUrl: string;
  subscriptionUrlEnvironment: string;
  inlineNodesEnvironment: string;
  engine: 'SING_BOX' | 'XRAY';
  preferredNames: string[];
  maximumNodes: number;
  refreshSeconds: number;
  allowInsecureTls: boolean;
  enabled: boolean;
  version: number;
  updatedAt: string;
}

export interface ProxyGatewayReloadResult {
  gatewayId: string;
  status: string;
  requestedAt: string;
}

export interface LoginRequest {
  username: string;
  password: string;
  challengeId: string;
  proofOfWorkSolution: string;
  mathAnswer: number;
}

export interface InstantResearchRequest {
  question: string;
  workflowVersionId: string | null;
  demoWorkflowVersionId: string | null;
}

export interface CollectSourceRequest {
  query: string;
  workflowRunId: string | null;
}

export interface CreateWatchlistRequest {
  name: string;
  description: string;
}

export interface UpdateWatchlistRequest extends CreateWatchlistRequest {
  expectedVersion: number;
}

export interface UpsertWatchlistItemRequest {
  preferredInstrumentId: string | null;
  researchMode: 'MONITOR' | 'RESEARCH' | 'PINNED';
  note: string;
}

export interface UpdateSettingRequest {
  value: string;
  expectedVersion: number;
}

export interface UpdateScheduleRequest {
  enabled: boolean;
  intervalSeconds: number;
  expectedVersion: number;
}

export interface ResearchFeedbackRequest {
  rating: 'HELPFUL' | 'NEUTRAL' | 'NOT_HELPFUL';
  effectiveness: 'UNKNOWN' | 'PENDING' | 'WIN' | 'LOSS' | 'NO_TRADE';
  note: string;
  expectedVersion?: number | null;
}

export interface UpdateExchangeAccountControlRequest {
  enabled: boolean;
  expectedVersion: number;
}

export interface WorkflowActivationRequest {
  active: boolean;
}

export interface StartWorkflowRequest {
  workflowType: 'SCHEDULED_RESEARCH' | 'INSTANT_RESEARCH' | 'ACCOUNT_RECONCILIATION' | 'EXCHANGE_LEDGER_SYNC';
  trigger: 'MANUAL' | 'SCHEDULED' | 'API' | 'RECOVERY';
  workflowVersionId: string | null;
  requestSummary: string;
}

export interface AgentRoleMutation {
  displayName: string;
  objective: string;
  systemPrompt: string;
  userPromptTemplate: string;
  outputContract: WorkflowOutputContract;
  defaultProviderProfileId: string;
  defaultModelName: string;
  defaultReasoningEffort: ReasoningEffort;
  expectedVersion?: number | null;
}

export interface AiExperimentMutation {
  displayName: string;
  status: 'DRAFT' | 'RUNNING' | 'PAUSED' | 'COMPLETED';
  controlWorkflowVersionId: string;
  candidateWorkflowVersionId: string;
  candidateAllocationBasisPoints: number;
  evaluationMetric: string;
  minimumSampleSize: number;
  expectedVersion?: number | null;
}

export interface UpdateExecutionAiStageRequest {
  primaryAiBinding: { providerProfileId: string; modelName: string; reasoningEffort: ReasoningEffort };
  fallbackAiBinding: { providerProfileId: string; modelName: string; reasoningEffort: ReasoningEffort } | null;
  systemPrompt: string;
  userPromptTemplate: string;
  maximumOutputTokens: number;
  timeoutSeconds: number;
  retryMaximumAttempts: number;
  retryBackoffSeconds: number;
  enabled: boolean;
  expectedVersion: number;
}

export interface ActivateRiskPolicyRequest {
  policyVersion: string;
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

export interface WorkflowDraftRequest {
  definitionId: string | null;
  versionId: string | null;
  name: string;
  description: string;
  defaultDebateRounds: number;
  debateProtocol: DebateProtocolConfiguration;
  maximumSteps: number;
  maximumDurationSeconds: number;
  maximumTokens: number;
  maximumCostUsd: number;
  failurePolicy: WorkflowFailurePolicy;
  expectedChecksum: string | null;
  nodes: WorkflowDraftNodeRequest[];
  edges: WorkflowDraftEdgeRequest[];
}

export interface WorkflowDraftNodeRequest {
  nodeId: string;
  nodeType: WorkflowNodeType;
  displayName: string;
  roleName: string | null;
  roleTemplateId: string | null;
  logicalRoleKey: string | null;
  primaryAiBinding: { providerProfileId: string; modelName: string; reasoningEffort: ReasoningEffort } | null;
  fallbackAiBinding: { providerProfileId: string; modelName: string; reasoningEffort: ReasoningEffort } | null;
  systemPrompt: string | null;
  userPromptTemplate: string | null;
  outputContract: WorkflowOutputContract | null;
  contextMode: string;
  contextHistoryRounds: number;
  contextMaximumMessages: number;
  maximumOutputTokens: number;
  timeoutSeconds: number;
  retryMaximumAttempts: number;
  retryBackoffSeconds: number;
  operation: string | null;
  positionX: number;
  positionY: number;
  enabled: boolean;
}

export interface WorkflowDraftEdgeRequest {
  edgeId: string;
  sourceNodeId: string;
  targetNodeId: string;
  activationMode: string;
  contextMode: string;
  condition: WorkflowCondition | null;
  loopEdge: boolean;
  maximumTraversals: number | null;
}

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

export interface ResearchCase {
  caseId: string;
  status: 'PENDING' | 'RUNNING' | 'PARTIAL' | 'COMPLETED' | 'FAILED';
  requestSummary: string;
  evidenceArtifactId: string | null;
  segments: Array<{
    segmentId: string;
    segmentType: 'EVIDENCE' | 'LIVE_RESEARCH' | 'DEMO_AUTOTRADE';
    dataPlane: 'LIVE' | 'PAPER' | null;
    workflowRunId: string | null;
    evidenceArtifactId: string | null;
    status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
    errorCode: string | null;
    errorMessage: string | null;
    startedAt: string | null;
    completedAt: string | null;
  }>;
  createdAt: string;
  completedAt: string | null;
  updatedAt: string;
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
  debateProtocol: DebateProtocolTrace | null;
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
  estimatedTradeCount: number;
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
  estimatedTrades: Array<{
    projectionId: string; proposalId: string; instrumentId: string; exchange: string;
    symbol: string; side: string; policyVersion: string; entryReference: number;
    marketPrice: number; targetPrice: number; stopPrice: number; quantity: number;
    contractSize: number; notionalUsdt: number; leverage: number; initialMarginUsdt: number;
    estimatedEntryCostUsdt: number; estimatedTargetExitCostUsdt: number;
    estimatedStopExitCostUsdt: number; estimatedProfitUsdt: number;
    estimatedLossUsdt: number; riskRewardRatio: number; calculatedAt: string;
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
  providerProfileId: string;
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
  apiKeySource: 'DATABASE_OVERRIDE' | 'ENVIRONMENT_FALLBACK' | 'UNCONFIGURED';
  apiKeyFingerprint: string | null; apiKeyVersion: number;
  apiSecretSource: 'DATABASE_OVERRIDE' | 'ENVIRONMENT_FALLBACK' | 'UNCONFIGURED';
  apiSecretFingerprint: string | null; apiSecretVersion: number;
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
  accountId: string | null; exchange: string | null; symbol: string | null; status: string | null;
  side: string | null; quantity: number | null; price: number | null; amount: number | null;
  currency: string | null; exchangeOrderId: string | null; clientOrderId: string | null;
  title: string; detail: string; detailsJson: string;
  occurredAt: string; receivedAt: string;
}

export interface ActivityPage {
  activities: ActivityRecord[];
  nextCursor: { occurredAt: string; activityId: string } | null;
  matchedCount: number;
  counts: Array<{ activityType: string; count: number }>;
  sources: Array<{
    source: string; accountId: string | null; exchange: string | null; status: string;
    complete: boolean; message: string; latestAt: string | null;
  }>;
}

export interface ProductSummary {
  productId: string; baseAsset: string; quoteAsset: string; displayName: string;
  category: string; status: string; instrumentCount: number; highestWatchlistMode: string | null;
}

export interface ProductPage { products: ProductSummary[]; nextCursor: string | null; totalCount: number }
export interface ResearchForecast {
  forecastId: string; workflowRunId: string; instrumentId: string; exchange: string; environment: 'LIVE' | 'TESTNET' | 'DEMO'; symbol: string;
  intervalSeconds: number; horizonSeconds: number; marketReferencePrice: number;
  direction: string; directionProbabilities: { up: number; sideways: number; down: number } | null;
  expectedLow: number | null; expectedHigh: number | null; invalidationPrice: number | null;
  confidence: number; thesis: string; evidenceReferences: string[]; status: string;
  issuedAt: string; targetAt: string; actualPrice: number | null; actualReturn: number | null;
  shadowNotionalUsdt: number; shadowPnlUsdt: number | null;
  directionCorrect: boolean | null; rangeHit: boolean | null; evaluatedAt: string | null;
}
export interface CatalogSyncRun {
  syncRunId: string; exchange: string; marketType: string; status: string;
  discoveredCount: number; activeCount: number; inactiveCount: number;
  errorCode: string | null; errorMessage: string | null; startedAt: string; completedAt: string | null;
}
export interface InstrumentRecord {
  instrumentId: string; exchange: string; marketType: string; symbol: string; settlementAsset: string;
  contractSize: number; priceTick: number; quantityStep: number; minimumQuantity: number;
  maximumLeverage: number; executionEnabled: boolean; status: string; metadataUpdatedAt: string;
  latestPrice: number | null; latestPriceAt: string | null;
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
  nodeId: string; nodeType: WorkflowNodeType; displayName: string; roleName: string | null;
  roleTemplateId: string | null; logicalRoleKey: string | null; primaryAiBinding: AiModelBinding | null;
  fallbackAiBinding: AiModelBinding | null; systemPrompt: string | null; userPromptTemplate: string | null;
  outputContract: WorkflowOutputContract | null; contextMode: string; contextHistoryRounds: number;
  contextMaximumMessages: number; maximumOutputTokens: number; timeoutSeconds: number;
  retryMaximumAttempts: number; retryBackoffSeconds: number; operation: string | null;
  positionX: number; positionY: number; enabled: boolean;
}
export interface WorkflowEdge {
  edgeId: string; sourceNodeId: string; targetNodeId: string; activationMode: string;
  contextMode: string; condition: WorkflowCondition | null; loopEdge: boolean; maximumTraversals: number | null;
}
export type WorkflowConditionOperandType = 'TEXT' | 'DECIMAL' | 'BOOLEAN' | 'TEXT_LIST';
export interface WorkflowConditionOperand {
  type: WorkflowConditionOperandType;
  textValue: string | null;
  decimalValue: number | null;
  booleanValue: boolean | null;
  textValues: string[] | null;
}
export interface WorkflowCondition {
  field: string;
  operator: string;
  operand: WorkflowConditionOperand | null;
}
export interface WorkflowVersion {
  versionId: string; definitionId: string; versionNumber: number; status: string;
  defaultDebateRounds: number; debateProtocol: DebateProtocolConfiguration; maximumSteps: number; maximumDurationSeconds: number;
  maximumTokens: number; maximumCostUsd: number; failurePolicy: WorkflowFailurePolicy; checksum: string;
  publishedAt: string | null; createdAt: string; createdBy: string; nodes: WorkflowNode[]; edges: WorkflowEdge[];
}
export interface WorkflowSchema {
  nodeTypes: WorkflowNodeType[]; reasoningEfforts: ReasoningEffort[]; contextModes: string[];
  edgeContextModes: string[]; conditionOperators: string[]; outputContracts: WorkflowOutputContract[]; failurePolicies: WorkflowFailurePolicy[];
}

export interface SystemSetting { key: string; type: string; value: string; source: string; description: string; version: number; updatedAt: string }
export interface AiProvider {
  profileId: string; displayName: string; protocol: 'CHAT' | 'RESPONSES'; reasoningParameterStyle: 'NONE' | 'FLAT' | 'NESTED';
  baseUrl: string | null; baseUrlConfigured: boolean;
  apiKeyConfigured: boolean;
  credentialSource: 'DATABASE_OVERRIDE' | 'ENVIRONMENT_FALLBACK' | 'UNCONFIGURED';
  credentialFingerprint: string | null; credentialVersion: number; credentialUpdatedAt: string | null;
  enabled: boolean; connectTimeoutSeconds: number;
  requestTimeoutSeconds: number; maximumConcurrentRequests: number; acquireTimeoutSeconds: number;
  workflowNodeUsageCount: number; roleTemplateUsageCount: number;
  executionStageUsageCount: number; totalUsageCount: number; version: number; updatedAt: string;
}
export interface AiModel {
  modelProfileId: string; providerProfileId: string; modelName: string;
  defaultReasoningEffort: ReasoningEffort; maximumReasoningEffort: ReasoningEffort; inputUsdPerMillion: number;
  outputUsdPerMillion: number; enabled: boolean; version: number; updatedAt: string;
}
export interface ConfigurationSnapshot { settings: SystemSetting[]; providers: AiProvider[]; models: AiModel[] }

export interface AdminApiToken {
  tokenId: string;
  displayName: string;
  fingerprint: string;
  username: string;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  expiresAt: string | null;
  lastUsedAt: string | null;
  revokedAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface CreatedAdminApiToken {
  token: AdminApiToken;
  readonly rawToken: string;
}

export interface SourceRecord {
  sourceId: string;
  displayName: string;
  mode: string;
  tier: string;
  category: string;
  provider: string | null;
  trustWeight: number;
  pollIntervalSeconds: number;
  priority: string;
  assetScope: string[];
  feedUrls: string[];
  seedUrls: string[];
  searchQueries: string[];
  endpointBaseUrl: string | null;
  credentialSupported: boolean;
  outboundRoute: string | null;
  crawlerHeaderProfileId: string;
  maximumResults: number;
  maximumScrapeTargets: number;
  enabled: boolean;
  version: number;
  aiWebSearchBinding: {
    providerProfileId: string;
    modelName: string;
    reasoningEffort: ReasoningEffort;
    tool: 'WEB_SEARCH' | 'GOOGLE_SEARCH';
  } | null;
}
export type SourceMutation = Omit<SourceRecord, 'sourceId' | 'version'>;
export type CrawlerBrowserTemplate =
  | 'NONE'
  | 'CHROME_WINDOWS'
  | 'CHROME_MAC'
  | 'FIREFOX_WINDOWS'
  | 'EDGE_WINDOWS'
  | 'CUSTOM';
export type CrawlerCaptchaBypassProvider =
  | 'NONE'
  | 'CAPSOLVER'
  | 'TWOCAPTCHA'
  | 'FIRECRAWL_BROWSER'
  | 'BROWSER_WORKER';
export interface CrawlerHeaderProfile {
  profileId: string;
  displayName: string;
  userAgent: string;
  accept: string | null;
  acceptLanguage: string | null;
  additionalHeaders: Record<string, string>;
  browserTemplate: CrawlerBrowserTemplate;
  retainSensitiveHeadersOnCrossOriginRedirect: boolean;
  crossOriginRetainHeaders: string[];
  captchaBypassEnabled: boolean;
  captchaBypassProvider: CrawlerCaptchaBypassProvider;
  enabled: boolean;
  usageCount: number;
  version: number;
  updatedAt: string;
}
export type CrawlerHeaderProfileMutation = Omit<CrawlerHeaderProfile, 'profileId' | 'usageCount' | 'version' | 'updatedAt'>;
export interface SourceHealth {
  sourceId: string;
  serviceReady: boolean;
  egressReady: boolean;
  routeType: string;
  routeEndpoint: string;
  channelStatus: string;
  firecrawlChannelStatus: string;
  rateLimitStatus: string;
  lastSuccessAt: string | null;
  lastBlockedAt: string | null;
  lastAttemptAt: string | null;
  latestOutcome: string | null;
  latestStatusCode: number | null;
  latestErrorCode: string | null;
  safeMessage: string | null;
}
export interface EvidenceDocument { documentId: string; evidenceId: string; sourceId: string; sourceTier: string; category: string; trustWeight: number; canonicalUrl: string | null; title: string; language: string; excerpt: string; assetScope: string[]; publishedAt: string | null; fetchedAt: string }

export interface AutonomousStatus {
  enabled: boolean;
  workerOnline: boolean;
  schedule: ScheduleRecord | null;
  activeTask: TaskRecord | null;
  latestRun: ResearchSummary | null;
  latestConclusion: string | null;
  generatedAt: string;
}

export interface ResearchFeedback {
  feedbackId: string;
  workflowRunId: string;
  rating: 'HELPFUL' | 'NEUTRAL' | 'NOT_HELPFUL';
  effectiveness: 'UNKNOWN' | 'PENDING' | 'WIN' | 'LOSS' | 'NO_TRADE';
  note: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ResearchComparison {
  left: ResearchSummary;
  right: ResearchSummary;
  inputTokenDelta: number;
  outputTokenDelta: number;
  costDeltaUsd: number;
  durationDeltaSeconds: number | null;
  leftConclusion: string;
  rightConclusion: string;
  nodes: Array<{
    nodeId: string; round: number; leftStatus: string; rightStatus: string;
    leftSummary: string | null; rightSummary: string | null; changed: boolean;
  }>;
}

export interface TradeRiskPreview {
  mode: 'EXECUTION' | 'ESTIMATE';
  status: string;
  reasons: string[];
  quantity: number | null;
  notionalUsdt: number | null;
  leverage: number | null;
  initialMarginUsdt: number | null;
  estimatedMaximumLossUsdt: number | null;
  approximateLiquidationPrice: number | null;
  estimatedProfitUsdt: number | null;
  riskRewardRatio: number | null;
  policy: RiskPolicy;
  calculatedAt: string;
}

export interface PlatformReadiness {
  ready: boolean;
  score: number;
  checks: Array<{
    code: string; title: string; status: string; detail: string;
    actionPage: string; observedAt: string;
  }>;
  latestResearch: null | {
    runId: string; status: string; requestSummary: string;
    conclusion: string; completedAt: string | null;
  };
  accountSummary: {
    enabledAccounts: number; synchronizedAccounts: number; currency: string;
    equity: number; unrealizedPnl: number; realizedPnl: number; snapshotAt: string | null;
  };
  pendingTaskCount: number;
  failedTaskCount: number;
  generatedAt: string;
}

export interface IngestionWorkspace {
  rawEvidenceCount: number;
  normalizedDocumentCount: number;
  compressionCount: number;
  aiReviewCount: number;
  sources: Array<{
    sourceId: string; displayName: string; mode: string; tier: string; category: string;
    outboundRoute: string | null; credentialSupported: boolean; credentialConfigured: boolean;
    credentialSource: 'DATABASE_OVERRIDE' | 'ENVIRONMENT_FALLBACK' | 'UNCONFIGURED' | 'NOT_REQUIRED';
    credentialFingerprint: string | null; credentialVersion: number;
    enabled: boolean; version: number; latestStatus: string | null;
    fetchedCount: number; insertedCount: number; duplicateCount: number;
    errorCode: string | null; errorMessage: string | null; lastCollectedAt: string | null;
  }>;
  recentRuns: Array<{
    collectionId: string; workflowRunId: string | null; sourceId: string; sourceName: string;
    query: string | null; status: string; fetchedCount: number; insertedCount: number;
    duplicateCount: number; errorCode: string | null; errorMessage: string | null;
    startedAt: string; completedAt: string | null;
  }>;
  recentAiReviews: Array<{
    reviewId: string; workflowRunId: string; documentId: string; nodeId: string;
    stage: 'CLEANING' | 'COMPRESSION' | 'VALIDATION'; status: string; summary: string | null; errorCode: string | null;
    citations: string[]; errorMessage: string | null; createdAt: string;
  }>;
  sourceCatalogVersion: string;
  sourceCatalogManifestHash: string;
  sourceCatalogSize: number;
  generatedAt: string;
}

export interface QuantWorkspace {
  runs: Array<{
    researchRunId: string; workflowRunId: string; requestSummary: string;
    researchKind: string; strategyId: string; strategyVersion: string; status: string;
    observationCount: number; resultFingerprint: string | null; metricsJson: string;
    errorCode: string | null; errorMessage: string | null; requestedAt: string;
    startedAt: string | null; completedAt: string | null;
  }>;
  generatedAt: string;
}

export interface OperationsReport {
  fromInclusive: string;
  toExclusive: string;
  sections: Array<{
    code: string; title: string;
    metrics: Array<{ label: string; value: string; unit: string; status: string }>;
    entries: Array<{ referenceId: string; title: string; summary: string; status: string; occurredAt: string }>;
  }>;
  generatedAt: string;
}

export interface NetworkWorkspace {
  routes: Array<{
    routeId: string; routeType: string; displayName: string; enabled: boolean;
    requireProxy: boolean; allowDirect: boolean; proxyConfigured: boolean;
    proxyCredentialSource: 'DATABASE_OVERRIDE' | 'ENVIRONMENT_FALLBACK' | 'UNCONFIGURED';
    proxyCredentialFingerprint: string | null; proxyCredentialVersion: number;
    expectedIpFamily: string; resolvedEndpoint: string; status: string;
    latestDependencyStatus: string; latestError: string | null;
    latestActivityAt: string | null; updatedAt: string;
  }>;
  proxyGateways: Array<{
    gatewayId: string; displayName: string; enabled: boolean; engine: ProxyEngine; preferredNames: string;
    maximumNodes: number; refreshSeconds: number; allowInsecureTls: boolean;
    subscriptionSupported: boolean; subscriptionSource: string; subscriptionFingerprint: string | null; subscriptionVersion: number;
    inlineNodesSupported: boolean; inlineNodesSource: string; inlineNodesFingerprint: string | null; inlineNodesVersion: number;
    status: string; version: number; updatedAt: string;
  }>;
  generatedAt: string;
}

export interface ProxyGatewayRuntimeStatus {
  gatewayId: string;
  engine: ProxyEngine;
  serviceReady: boolean;
  egressReady: boolean;
  nodeCount: number;
  healthyNodeCount: number;
  unhealthyNodeCount: number;
  healthyNodeIndices: number[];
  probeFailureCounts: Record<string, number>;
  validationEnabled: boolean;
  validationTarget: string | null;
  generation: number;
  refreshAttempt: number;
  lastRefreshAt: string | null;
  error: string | null;
}

export type ProxyEngine = 'SING_BOX' | 'XRAY';

export interface NetworkDiagnostic {
  diagnosticId: string; route: string; status: string; proxyConfigured: boolean;
  proxied: boolean; safeEndpoint: string; httpStatus: number | null;
  latencyMilliseconds: number | null; errorCode: string | null; errorMessage: string | null;
  startedAt: string; completedAt: string | null;
}

export interface SetupProfileDefinition {
  profileId: 'RECOMMENDED' | 'ECONOMY' | 'DEEP_RESEARCH';
  displayName: string;
  description: string;
  values: Record<string, string>;
}

export interface SetupProfilePreview {
  profile: SetupProfileDefinition;
  changes: Array<{ key: string; currentValue: string; proposedValue: string }>;
  preservedKeys: string[];
  missingKeys: string[];
}

export interface SetupProfileApplication {
  applicationId: string;
  profileId: SetupProfileDefinition['profileId'];
  appliedKeys: string[];
  preservedKeys: string[];
  skippedKeys: string[];
  appliedAt: string;
}

export interface ProviderModelCatalog {
  providerProfileId: string; status: string; models: string[]; httpStatus: number | null;
  latencyMilliseconds: number | null; errorCode: string | null; errorMessage: string | null;
  checkedAt: string;
}

export interface AgentRole {
  roleTemplateId: string; displayName: string; objective: string; systemPrompt: string;
  userPromptTemplate: string; outputContract: WorkflowOutputContract; defaultProviderProfileId: string;
  defaultModelName: string; defaultReasoningEffort: ReasoningEffort; builtIn: boolean;
  version: number; createdAt: string; updatedAt: string;
}

export interface AiExperiment {
  experimentId: string; displayName: string; status: 'DRAFT' | 'RUNNING' | 'PAUSED' | 'COMPLETED';
  controlWorkflowVersionId: string; candidateWorkflowVersionId: string;
  candidateAllocationBasisPoints: number; evaluationMetric: string; minimumSampleSize: number;
  controlSampleCount: number; candidateSampleCount: number; version: number;
  createdAt: string; updatedAt: string;
}

export interface WorkflowEstimate {
  versionId: string; debateRounds: number; estimatedCalls: number;
  estimatedInputTokens: number; maximumOutputTokens: number; primaryCostUsd: number;
  fallbackWorstCaseCostUsd: number; configuredCostLimitUsd: number; configuredTokenLimit: number;
  nodes: Array<{
    nodeId: string; displayName: string; estimatedCalls: number; estimatedInputTokens: number;
    maximumOutputTokens: number; primaryProvider: string; primaryModel: string;
    primaryCostUsd: number; fallbackProvider: string | null; fallbackModel: string | null;
    fallbackCostUsd: number; rateComplete: boolean;
  }>;
  warnings: string[];
}

export interface WorkflowExecutionPlan {
  workflowVersionId: string; defaultDebateRounds: number; maximumDebateRounds: number;
  maximumSteps: number; maximumTokens: number; maximumCostUsd: string;
  nodes: Array<{
    sequence: number; nodeId: string; displayName: string; nodeType: string;
    runtimeHandler: string; invocationPolicy: string; upstreamNodeIds: string[];
    providerProfileId: string | null; modelName: string | null; reasoningEffort: string | null;
    fallbackProviderProfileId: string | null; fallbackModelName: string | null; enabled: boolean;
  }>;
  warnings: string[];
}

export interface WorkflowNodeTestResult {
  runId: string; versionId: string; nodeId: string; status: string;
  invocationId: string | null; output: string | null; errorCode: string | null;
  errorMessage: string | null; startedAt: string; completedAt: string;
}

export interface WorkflowLearning {
  definitionId: string; versionId: string; runCount: number; completedRunCount: number;
  failedRunCount: number; totalCostUsd: number;
  nodes: Array<{
    nodeId: string; displayName: string; invocationCount: number;
    successfulInvocationCount: number; failedInvocationCount: number;
    inputTokens: number; outputTokens: number; costUsd: number;
    averageLatencyMilliseconds: number | null;
  }>;
  recentFailures: Array<{
    runId: string; nodeId: string; errorCode: string | null;
    errorMessage: string | null; occurredAt: string;
  }>;
  generatedAt: string;
}
