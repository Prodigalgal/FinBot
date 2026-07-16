import type {
  AccountsOverview, ActivityPage, AgentRole, AiExperiment, AiModel, AiProvider,
  AuthChallenge, AuthStatus, AutonomousStatus, ConfigurationSnapshot, EvidenceDocument,
  ExecutionAiStage, IngestionWorkspace, NetworkDiagnostic, NetworkWorkspace,
  OperationsOverview, OperationsReport, PlatformReadiness, PositionRecord,
  CatalogSyncRun, ProductDetail, ProductPage, ProviderModelCatalog, QuantWorkspace, ResearchComparison,
  ResearchCase, ResearchFeedback, ResearchForecast, ResearchHistoryDetail, ResearchLaunch, ResearchSummary, RiskPolicy,
  ScheduleRecord, SetupProfileApplication, SetupProfileDefinition, SetupProfilePreview,
  SourceRecord, SystemSetting, TaskRecord, TradeAutomationConfiguration,
  TradeAutomationDetail, TradeAutomationSummary, TradeRiskPreview, WatchlistDetail,
  WatchlistSummary, WorkflowDefinitionSummary, WorkflowEstimate, WorkflowExecutionPlan, WorkflowLearning,
  WorkflowNodeTestResult, WorkflowRun, WorkflowSchema, WorkflowVersion,
} from './types';
import type {
  ControlPlaneDeleteRequestPath,
  ControlPlaneGetRequestPath,
  ControlPlanePatchRequestPath,
  ControlPlanePostRequestPath,
  ControlPlanePutRequestPath,
  ControlPlaneRequestPath,
} from './generated/control-plane';

const API_BASE = import.meta.env.VITE_FINBOT_API_BASE || '';
export const AUTH_REQUIRED_EVENT = 'finbot:authentication-required';
let csrfToken = '';

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'ApiError';
  }
}

function query(values: Record<string, string | number | boolean | null | undefined>): '' | `?${string}` {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== '') params.set(key, String(value));
  });
  return params.size > 0 ? `?${params.toString()}` : '';
}

function cookie(name: string): string {
  const prefix = `${encodeURIComponent(name)}=`;
  return document.cookie.split(';').map((part) => part.trim()).find((part) => part.startsWith(prefix))?.slice(prefix.length) || '';
}

function request<T>(path: ControlPlaneGetRequestPath, init?: RequestInit & { method?: 'GET' }): Promise<T>;
function request<T>(path: ControlPlanePostRequestPath, init: RequestInit & { method: 'POST' }): Promise<T>;
function request<T>(path: ControlPlanePutRequestPath, init: RequestInit & { method: 'PUT' }): Promise<T>;
function request<T>(path: ControlPlaneDeleteRequestPath, init: RequestInit & { method: 'DELETE' }): Promise<T>;
function request<T>(path: ControlPlanePatchRequestPath, init: RequestInit & { method: 'PATCH' }): Promise<T>;
async function request<T>(path: ControlPlaneRequestPath, init: RequestInit = {}): Promise<T> {
  const method = (init.method || 'GET').toUpperCase();
  const changing = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  const cookieCsrfToken = decodeURIComponent(cookie('XSRF-TOKEN'));
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: 'include',
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(changing && path !== '/api/v2/auth/login' ? { 'X-XSRF-TOKEN': cookieCsrfToken || csrfToken } : {}),
      ...init.headers,
    },
  });
  if (!response.ok) {
    const raw = await response.text();
    let message = raw || `HTTP ${response.status}`;
    try {
      const problem = JSON.parse(raw) as { detail?: unknown; title?: unknown };
      if (typeof problem.detail === 'string') message = problem.detail;
      else if (typeof problem.title === 'string') message = problem.title;
    } catch {
      // Keep non-JSON response.
    }
    if (response.status === 401 && path !== '/api/v2/auth/login') {
      window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT));
    }
    throw new ApiError(message, response.status);
  }
  if (response.status === 204) return undefined as T;
  const result = await response.json() as T;
  if (typeof result === 'object' && result !== null && 'csrfToken' in result) {
    const token = (result as { csrfToken?: unknown }).csrfToken;
    if (typeof token === 'string') csrfToken = token;
  }
  return result;
}

function idempotency(key: string): HeadersInit {
  return { 'Idempotency-Key': key };
}

export const api = {
  authStatus: () => request<AuthStatus>('/api/v2/auth/status'),
  authChallenge: () => request<AuthChallenge>('/api/v2/auth/challenge'),
  login: (body: { username: string; password: string; challengeId: string; proofOfWorkSolution: string; mathAnswer: number }) =>
    request<AuthStatus>('/api/v2/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  logout: () => request<AuthStatus>('/api/v2/auth/logout', { method: 'POST', body: '{}' }),

  operations: () => request<OperationsOverview>('/api/v2/operations'),
  operationsEventsUrl: () => `${API_BASE}/api/v2/operations/events`,
  tasks: (status?: string, limit = 50) => request<TaskRecord[]>(`/api/v2/operations/tasks${query({ status, limit })}`),
  task: (taskId: string) => request<TaskRecord>(`/api/v2/operations/tasks/${encodeURIComponent(taskId)}`),
  updateSchedule: (scheduleId: string, body: { enabled: boolean; intervalSeconds: number; expectedVersion: number }) =>
    request<ScheduleRecord>(`/api/v2/operations/schedules/${encodeURIComponent(scheduleId)}`, { method: 'PUT', body: JSON.stringify(body) }),
  readiness: () => request<PlatformReadiness>('/api/v2/readiness'),
  autonomous: () => request<AutonomousStatus>('/api/v2/autonomous'),
  triggerAutonomous: (requestSummary: string, key: string) => request<TaskRecord>('/api/v2/autonomous/runs', { method: 'POST', headers: idempotency(key), body: JSON.stringify({ requestSummary }) }),

  instantResearch: (question: string, workflowVersionId: string | null, demoWorkflowVersionId: string | null, key: string) =>
    request<ResearchLaunch>('/api/v2/research/instant', { method: 'POST', headers: idempotency(key), body: JSON.stringify({ question, workflowVersionId, demoWorkflowVersionId }) }),
  workflow: (runId: string) => request<WorkflowRun>(`/api/v2/workflows/${encodeURIComponent(runId)}`),
  workflowEventsUrl: (runId: string) => `${API_BASE}/api/v2/workflows/${encodeURIComponent(runId)}/events`,
  researchHistory: (status?: string, limit = 50) => request<ResearchSummary[]>(`/api/v2/research/history${query({ status, limit })}`),
  researchDetail: (runId: string) => request<ResearchHistoryDetail>(`/api/v2/research/history/${encodeURIComponent(runId)}`),
  researchCase: (runId: string) => request<ResearchCase>(`/api/v2/research/cases/by-run/${encodeURIComponent(runId)}`),
  replayResearch: (runId: string, key: string) => request<ResearchLaunch>(`/api/v2/research/history/${encodeURIComponent(runId)}/replay`, { method: 'POST', headers: idempotency(key), body: '{}' }),
  resumeResearch: (runId: string, key: string, checkpointNodeId?: string) => request<ResearchLaunch>(`/api/v2/research/history/${encodeURIComponent(runId)}/resume${query({ checkpointNodeId })}`, { method: 'POST', headers: idempotency(key), body: '{}' }),
  compareResearch: (leftRunId: string, rightRunId: string) => request<ResearchComparison>(`/api/v2/research/review/compare${query({ leftRunId, rightRunId })}`),
  researchFeedback: (limit = 100) => request<ResearchFeedback[]>(`/api/v2/research/review/feedback${query({ limit })}`),
  saveResearchFeedback: (runId: string, body: { rating: ResearchFeedback['rating']; effectiveness: ResearchFeedback['effectiveness']; note: string; expectedVersion: number | null }) => request<ResearchFeedback>(`/api/v2/research/review/${encodeURIComponent(runId)}/feedback`, { method: 'PUT', body: JSON.stringify(body) }),
  marketAnalysis: (body: { instrumentId: string; symbol: string; exchange: string; intervalSeconds: number; forecastHorizonSeconds: number; question: string; workflowVersionId: string | null; demoWorkflowVersionId: string | null }, key: string) => request<ResearchLaunch>('/api/v2/analysis/market-runs', { method: 'POST', headers: idempotency(key), body: JSON.stringify(body) }),
  researchForecast: (runId: string) => request<ResearchForecast>(`/api/v2/analysis/market-runs/${encodeURIComponent(runId)}/forecast`),
  researchForecasts: (limit = 50) => request<ResearchForecast[]>(`/api/v2/analysis/forecasts${query({ limit })}`),
  quantRuns: (limit = 100) => request<QuantWorkspace>(`/api/v2/quant/runs${query({ limit })}`),
  tradeRiskPreview: (body: Record<string, unknown>) => request<TradeRiskPreview>('/api/v2/quant/previews', { method: 'POST', body: JSON.stringify(body) }),

  tradeAutomations: (limit = 50) => request<TradeAutomationSummary[]>(`/api/v2/trading/automations${query({ limit })}`),
  tradeAutomation: (runId: string) => request<TradeAutomationDetail>(`/api/v2/trading/automations/${encodeURIComponent(runId)}`),
  tradeAutomationConfiguration: () => request<TradeAutomationConfiguration>('/api/v2/trading/automation-configuration'),
  updateExecutionStage: (stage: string, body: {
    primaryAiBinding: { providerProfileId: string; modelName: string; reasoningEffort: ExecutionAiStage['primaryAiBinding']['reasoningEffort'] };
    fallbackAiBinding: { providerProfileId: string; modelName: string; reasoningEffort: ExecutionAiStage['primaryAiBinding']['reasoningEffort'] } | null;
    systemPrompt: string; userPromptTemplate: string; maximumOutputTokens: number; timeoutSeconds: number;
    retryMaximumAttempts: number; retryBackoffSeconds: number; enabled: boolean; expectedVersion: number;
  }) =>
    request<ExecutionAiStage>(`/api/v2/trading/automation-configuration/ai-stages/${stage}`, { method: 'PUT', body: JSON.stringify(body) }),
  activateRiskPolicy: (body: RiskPolicy & { policyVersion: string }) => {
    const { version: _ignoredVersion, ...requestBody } = body;
    return request<RiskPolicy>('/api/v2/trading/automation-configuration/risk-policies', { method: 'POST', body: JSON.stringify(requestBody) });
  },
  accounts: (range = 'ALL', from?: string, to?: string) => request<AccountsOverview>(`/api/v2/trading/accounts${query({ range, from, to })}`),
  setExchangeAccountEnabled: (accountId: string, enabled: boolean, expectedVersion: number) =>
    request<{ accountId: string; enabled: boolean; version: number }>(`/api/v2/trading/accounts/${encodeURIComponent(accountId)}/configuration`, { method: 'PUT', body: JSON.stringify({ enabled, expectedVersion }) }),
  positions: (accountId: string) => request<PositionRecord[]>(`/api/v2/trading/accounts/${encodeURIComponent(accountId)}/positions`),
  activity: (params: {
    accountId?: string; source?: string; activityType?: string; status?: string; symbol?: string;
    range?: string; from?: string; to?: string; beforeOccurredAt?: string;
    beforeActivityId?: string; limit?: number;
  }) => request<ActivityPage>(`/api/v2/trading/activity${query(params)}`),

  products: (params: { search?: string; category?: string; exchange?: string; marketType?: string; after?: string; limit?: number }) =>
    request<ProductPage>(`/api/v2/products${query(params)}`),
  product: (productId: string) => request<ProductDetail>(`/api/v2/products/${encodeURIComponent(productId)}`),
  catalogSyncRuns: () => request<CatalogSyncRun[]>('/api/v2/products/catalog-sync-runs'),
  synchronizeCatalog: (exchange: string, marketType: string, key: string) =>
    request<TaskRecord>(`/api/v2/products/catalog-sync/${encodeURIComponent(exchange)}/${encodeURIComponent(marketType)}`, { method: 'POST', headers: idempotency(key), body: '{}' }),
  watchlists: () => request<WatchlistSummary[]>('/api/v2/watchlists'),
  watchlist: (watchlistId: string) => request<WatchlistDetail>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}`),
  createWatchlist: (name: string, description: string) => request<WatchlistDetail>('/api/v2/watchlists', { method: 'POST', body: JSON.stringify({ name, description }) }),
  updateWatchlist: (watchlistId: string, name: string, description: string, expectedVersion: number) => request<WatchlistDetail>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}`, { method: 'PUT', body: JSON.stringify({ name, description, expectedVersion }) }),
  deleteWatchlist: (watchlistId: string) => request<void>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}`, { method: 'DELETE' }),
  upsertWatchlistItem: (watchlistId: string, productId: string, body: { preferredInstrumentId: string | null; researchMode: string; note: string }) =>
    request<WatchlistDetail>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}/items/${encodeURIComponent(productId)}`, { method: 'PUT', body: JSON.stringify(body) }),
  removeWatchlistItem: (watchlistId: string, productId: string) =>
    request<WatchlistDetail>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}/items/${encodeURIComponent(productId)}`, { method: 'DELETE' }),

  workflowDefinitions: () => request<WorkflowDefinitionSummary[]>('/api/v2/workflow-definitions'),
  workflowVersion: (versionId: string) => request<WorkflowVersion>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}`),
  workflowVersions: (definitionId: string) => request<WorkflowVersion[]>(`/api/v2/workflow-definitions/${encodeURIComponent(definitionId)}/versions`),
  workflowSchema: () => request<WorkflowSchema>('/api/v2/workflow-schema'),
  saveWorkflowDraft: (body: Record<string, unknown>) => request<WorkflowVersion>('/api/v2/workflow-drafts', { method: 'PUT', body: JSON.stringify(body) }),
  publishWorkflow: (versionId: string) => request<WorkflowVersion>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}/publish`, { method: 'POST', body: '{}' }),
  rollbackWorkflow: (definitionId: string, targetVersionId: string) => request<WorkflowVersion>(`/api/v2/workflow-definitions/${encodeURIComponent(definitionId)}/rollback/${encodeURIComponent(targetVersionId)}`, { method: 'POST', body: '{}' }),
  setWorkflowActive: (definitionId: string, active: boolean) => request<WorkflowDefinitionSummary>(`/api/v2/workflow-definitions/${encodeURIComponent(definitionId)}/activation`, { method: 'PUT', body: JSON.stringify({ active }) }),
  workflowEstimate: (versionId: string) => request<WorkflowEstimate>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}/estimate`),
  workflowPlan: (versionId: string) => request<WorkflowExecutionPlan>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}/plan`),
  workflowLearning: (versionId: string) => request<WorkflowLearning>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}/learning`),
  testWorkflowNode: (versionId: string, nodeId: string, userPrompt: string, key: string) => request<WorkflowNodeTestResult>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}/nodes/${encodeURIComponent(nodeId)}/test`, { method: 'POST', headers: idempotency(key), body: JSON.stringify({ userPrompt }) }),
  agentRoles: () => request<AgentRole[]>('/api/v2/agent-roles'),
  createAgentRole: (body: Record<string, unknown>) => request<AgentRole>('/api/v2/agent-roles', { method: 'POST', body: JSON.stringify(body) }),
  updateAgentRole: (roleTemplateId: string, body: Record<string, unknown>) => request<AgentRole>(`/api/v2/agent-roles/${encodeURIComponent(roleTemplateId)}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteAgentRole: (roleTemplateId: string, expectedVersion: number) => request<void>(`/api/v2/agent-roles/${encodeURIComponent(roleTemplateId)}${query({ expectedVersion })}`, { method: 'DELETE' }),

  configuration: () => request<ConfigurationSnapshot>('/api/v2/configuration'),
  updateSetting: (setting: SystemSetting, value: string) => request<SystemSetting>(`/api/v2/configuration/settings/${encodeURIComponent(setting.key)}`, { method: 'PUT', body: JSON.stringify({ value, expectedVersion: setting.version }) }),
  updateProvider: (provider: AiProvider) => request<AiProvider>(`/api/v2/configuration/providers/${encodeURIComponent(provider.profileId)}`, {
    method: 'PUT',
    body: JSON.stringify({
      displayName: provider.displayName,
      protocol: provider.protocol,
      reasoningParameterStyle: provider.reasoningParameterStyle,
      baseUrl: provider.baseUrl,
      baseUrlEnv: provider.baseUrlEnv,
      apiKeyEnv: provider.apiKeyEnv,
      enabled: provider.enabled,
      connectTimeoutSeconds: provider.connectTimeoutSeconds,
      requestTimeoutSeconds: provider.requestTimeoutSeconds,
      expectedVersion: provider.version,
    }),
  }),
  updateModel: (model: AiModel) => request<AiModel>(`/api/v2/configuration/models/${encodeURIComponent(model.modelProfileId)}`, { method: 'PUT', body: JSON.stringify({ defaultReasoningEffort: model.defaultReasoningEffort, maximumReasoningEffort: model.maximumReasoningEffort, inputUsdPerMillion: model.inputUsdPerMillion, outputUsdPerMillion: model.outputUsdPerMillion, enabled: model.enabled, expectedVersion: model.version }) }),
  probeProvider: (profileId: string) => request<ProviderModelCatalog>(`/api/v2/configuration/providers/${encodeURIComponent(profileId)}/probe`, { method: 'POST', body: '{}' }),
  setupProfiles: () => request<SetupProfileDefinition[]>('/api/v2/setup-profiles'),
  previewSetupProfile: (profileId: SetupProfileDefinition['profileId']) => request<SetupProfilePreview>(`/api/v2/setup-profiles/${profileId}/preview`),
  applySetupProfile: (profileId: SetupProfileDefinition['profileId'], key: string) => request<SetupProfileApplication>(`/api/v2/setup-profiles/${profileId}/apply`, { method: 'POST', headers: idempotency(key), body: '{}' }),
  setupHistory: (limit = 20) => request<SetupProfileApplication[]>(`/api/v2/setup-profiles/history${query({ limit })}`),
  aiExperiments: () => request<AiExperiment[]>('/api/v2/ai-experiments'),
  createAiExperiment: (body: Record<string, unknown>) => request<AiExperiment>('/api/v2/ai-experiments', { method: 'POST', body: JSON.stringify(body) }),
  updateAiExperiment: (experimentId: string, body: Record<string, unknown>) => request<AiExperiment>(`/api/v2/ai-experiments/${encodeURIComponent(experimentId)}`, { method: 'PUT', body: JSON.stringify(body) }),

  sources: (enabledOnly = false) => request<SourceRecord[]>(`/api/v2/sources${query({ enabledOnly })}`),
  setSourceEnabled: (sourceId: string, enabled: boolean, expectedVersion: number) => request<SourceRecord>(`/api/v2/sources/${encodeURIComponent(sourceId)}/status`, { method: 'PUT', body: JSON.stringify({ enabled, expectedVersion }) }),
  documents: (sourceId: string, limit = 30) => request<EvidenceDocument[]>(`/api/v2/evidence/documents${query({ sourceId, limit })}`),
  collectSource: (sourceId: string, queryText: string, key: string) => request<TaskRecord>(`/api/v2/sources/${encodeURIComponent(sourceId)}/collect`, { method: 'POST', headers: idempotency(key), body: JSON.stringify({ query: queryText }) }),
  ingestionWorkspace: (limit = 100) => request<IngestionWorkspace>(`/api/v2/ingestion/workspace${query({ limit })}`),
  reports: (from?: string, to?: string) => request<OperationsReport>(`/api/v2/reports${query({ from, to })}`),
  network: () => request<NetworkWorkspace>('/api/v2/network'),
  networkDiagnostics: (limit = 100) => request<NetworkDiagnostic[]>(`/api/v2/network/diagnostics${query({ limit })}`),
  startNetworkDiagnostics: (routes: string[]) => request<NetworkDiagnostic[]>('/api/v2/network/diagnostics', { method: 'POST', headers: idempotency(crypto.randomUUID()), body: JSON.stringify({ routes }) }),
};
