import type {
  AccountsOverview, ActivityRecord, AiModel, AiProvider, AuthChallenge, AuthStatus,
  ConfigurationSnapshot, EvidenceDocument, ExecutionAiStage, OperationsOverview,
  PositionRecord, ProductDetail, ProductPage, ResearchHistoryDetail, ResearchLaunch,
  ResearchSummary, RiskPolicy, ScheduleRecord, SourceRecord, SystemSetting,
  TaskRecord, TradeAutomationConfiguration, TradeAutomationDetail, TradeAutomationSummary,
  WatchlistDetail, WatchlistSummary, WorkflowDefinitionSummary, WorkflowRun,
  WorkflowSchema, WorkflowVersion,
} from './types';

const API_BASE = import.meta.env.VITE_FINBOT_API_BASE || '';
export const AUTH_REQUIRED_EVENT = 'finbot:authentication-required';
let csrfToken = '';

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'ApiError';
  }
}

function query(values: Record<string, string | number | boolean | null | undefined>): string {
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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method || 'GET').toUpperCase();
  const changing = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: 'include',
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(changing && path !== '/api/v2/auth/login' ? { 'X-XSRF-TOKEN': csrfToken || decodeURIComponent(cookie('XSRF-TOKEN')) } : {}),
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
  tasks: (status?: string, limit = 50) => request<TaskRecord[]>(`/api/v2/operations/tasks${query({ status, limit })}`),
  task: (taskId: string) => request<TaskRecord>(`/api/v2/operations/tasks/${encodeURIComponent(taskId)}`),
  updateSchedule: (scheduleId: string, body: { enabled: boolean; intervalSeconds: number; expectedVersion: number }) =>
    request<ScheduleRecord>(`/api/v2/operations/schedules/${encodeURIComponent(scheduleId)}`, { method: 'PUT', body: JSON.stringify(body) }),

  instantResearch: (question: string, workflowVersionId: string | null, key: string) =>
    request<ResearchLaunch>('/api/v2/research/instant', { method: 'POST', headers: idempotency(key), body: JSON.stringify({ question, workflowVersionId }) }),
  workflow: (runId: string) => request<WorkflowRun>(`/api/v2/workflows/${encodeURIComponent(runId)}`),
  workflowEventsUrl: (runId: string) => `${API_BASE}/api/v2/workflows/${encodeURIComponent(runId)}/events`,
  researchHistory: (status?: string, limit = 50) => request<ResearchSummary[]>(`/api/v2/research/history${query({ status, limit })}`),
  researchDetail: (runId: string) => request<ResearchHistoryDetail>(`/api/v2/research/history/${encodeURIComponent(runId)}`),
  replayResearch: (runId: string, key: string) => request<ResearchLaunch>(`/api/v2/research/history/${encodeURIComponent(runId)}/replay`, { method: 'POST', headers: idempotency(key), body: '{}' }),
  resumeResearch: (runId: string, key: string) => request<ResearchLaunch>(`/api/v2/research/history/${encodeURIComponent(runId)}/resume`, { method: 'POST', headers: idempotency(key), body: '{}' }),

  tradeAutomations: (limit = 50) => request<TradeAutomationSummary[]>(`/api/v2/trading/automations${query({ limit })}`),
  tradeAutomation: (runId: string) => request<TradeAutomationDetail>(`/api/v2/trading/automations/${encodeURIComponent(runId)}`),
  tradeAutomationConfiguration: () => request<TradeAutomationConfiguration>('/api/v2/trading/automation-configuration'),
  updateExecutionStage: (stage: string, body: Omit<ExecutionAiStage, 'stage' | 'providerProfileId' | 'version'> & { providerProfileId: string; expectedVersion: number }) =>
    request<ExecutionAiStage>(`/api/v2/trading/automation-configuration/ai-stages/${stage}`, { method: 'PUT', body: JSON.stringify(body) }),
  activateRiskPolicy: (body: RiskPolicy & { policyVersion: string }) => {
    const { version: _ignoredVersion, ...requestBody } = body;
    return request<RiskPolicy>('/api/v2/trading/automation-configuration/risk-policies', { method: 'POST', body: JSON.stringify(requestBody) });
  },
  accounts: (range = 'ALL', from?: string, to?: string) => request<AccountsOverview>(`/api/v2/trading/accounts${query({ range, from, to })}`),
  positions: (accountId: string) => request<PositionRecord[]>(`/api/v2/trading/accounts/${encodeURIComponent(accountId)}/positions`),
  activity: (params: { accountId?: string; activityType?: string; range?: string; from?: string; to?: string; limit?: number }) =>
    request<{ activities: ActivityRecord[]; nextCursor: unknown | null }>(`/api/v2/trading/activity${query(params)}`),

  products: (params: { search?: string; category?: string; exchange?: string; marketType?: string; after?: string; limit?: number }) =>
    request<ProductPage>(`/api/v2/products${query(params)}`),
  product: (productId: string) => request<ProductDetail>(`/api/v2/products/${encodeURIComponent(productId)}`),
  watchlists: () => request<WatchlistSummary[]>('/api/v2/watchlists'),
  watchlist: (watchlistId: string) => request<WatchlistDetail>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}`),
  createWatchlist: (name: string, description: string) => request<WatchlistDetail>('/api/v2/watchlists', { method: 'POST', body: JSON.stringify({ name, description }) }),
  upsertWatchlistItem: (watchlistId: string, productId: string, body: { preferredInstrumentId: string | null; researchMode: string; note: string }) =>
    request<WatchlistDetail>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}/items/${encodeURIComponent(productId)}`, { method: 'PUT', body: JSON.stringify(body) }),
  removeWatchlistItem: (watchlistId: string, productId: string) =>
    request<WatchlistDetail>(`/api/v2/watchlists/${encodeURIComponent(watchlistId)}/items/${encodeURIComponent(productId)}`, { method: 'DELETE' }),

  workflowDefinitions: () => request<WorkflowDefinitionSummary[]>('/api/v2/workflow-definitions'),
  workflowVersion: (versionId: string) => request<WorkflowVersion>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}`),
  workflowSchema: () => request<WorkflowSchema>('/api/v2/workflow-schema'),
  saveWorkflowDraft: (body: Record<string, unknown>) => request<WorkflowVersion>('/api/v2/workflow-drafts', { method: 'PUT', body: JSON.stringify(body) }),
  publishWorkflow: (versionId: string) => request<WorkflowVersion>(`/api/v2/workflow-versions/${encodeURIComponent(versionId)}/publish`, { method: 'POST', body: '{}' }),

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
  updateModel: (model: AiModel) => request<AiModel>(`/api/v2/configuration/models/${encodeURIComponent(model.modelProfileId)}`, { method: 'PUT', body: JSON.stringify({ defaultReasoningEffort: model.defaultReasoningEffort, inputUsdPerMillion: model.inputUsdPerMillion, outputUsdPerMillion: model.outputUsdPerMillion, enabled: model.enabled, expectedVersion: model.version }) }),

  sources: (enabledOnly = false) => request<SourceRecord[]>(`/api/v2/sources${query({ enabledOnly })}`),
  documents: (sourceId: string, limit = 30) => request<EvidenceDocument[]>(`/api/v2/evidence/documents${query({ sourceId, limit })}`),
  collectSource: (sourceId: string, queryText: string, key: string) => request<TaskRecord>(`/api/v2/sources/${encodeURIComponent(sourceId)}/collect`, { method: 'POST', headers: idempotency(key), body: JSON.stringify({ query: queryText }) }),
};
