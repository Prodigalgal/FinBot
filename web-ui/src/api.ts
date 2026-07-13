import type { AccountPnlRange, AIConfigPayload, AIModelRefreshResponse, AutonomousStatusPayload, CouncilTemplateConfig, DecisionReviewInbox, DecisionReviewItem, DecisionReviewStatus, ExchangeAccountsPayload, ExecutionRiskGateResult, InstantProductContext, InstantResearchListPayload, InstantResearchSession, JobRecord, JobSubmitResponse, LeveragePreviewResult, PaperExecutionReport, PaperExecutionStatusPayload, ProductDetailPayload, ProductListPayload, ProxyDiagnostics, ResearchFeedbackPayload, ResearchHistoryComparison, ResearchHistoryList, ResearchHistoryRunDetail, ResearchNotification, SetupPayload, StatusPayload, SystemConfigPayload, WatchlistResearchMode, WatchlistsPayload, WorkflowDirectorPlan, WorkflowEstimate, WorkflowLearningPayload, WorkflowNodeTestRecord, WorkflowRunDetail, WorkflowRunSummary, WorkflowSchemaPayload, WorkflowVersionRecord, WorkflowVersionsPayload } from './types';

const API_BASE = import.meta.env.VITE_FINBOT_API_BASE || '';
export const AUTH_REQUIRED_EVENT = 'finbot:authentication-required';

export interface AuthStatusPayload {
  status: string;
  enabled: boolean;
  authenticated: boolean;
  session: { username: string; expires_at: number } | null;
}

export interface AuthChallenge {
  challenge_id: string;
  math_question: string;
  pow_prefix: string;
  difficulty_bits: number;
  expires_at: number;
}

export interface AuthChallengePayload {
  status: string;
  enabled: boolean;
  challenge: AuthChallenge | null;
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

function queryString(values: Record<string, string | number | boolean | null | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value));
    }
  }
  const query = params.toString();
  return query ? `?${query}` : '';
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      ...(init?.headers || {}),
    },
    ...init,
  });
  if (!response.ok) {
    const rawDetail = await response.text();
    let detail = rawDetail;
    try {
      const parsed = JSON.parse(rawDetail) as { detail?: unknown };
      if (typeof parsed.detail === 'string') detail = parsed.detail;
    } catch {
      // Non-JSON failures keep the original response text.
    }
    if (response.status === 401 && path !== '/api/v1/auth/login') {
      window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT));
    }
    throw new ApiError(detail || `HTTP ${response.status}`, response.status);
  }
  return response.json() as Promise<T>;
}

export const api = {
  health: () => request<{ status: string; service: string }>('/health'),
  authStatus: () => request<AuthStatusPayload>('/api/v1/auth/status'),
  authChallenge: () => request<AuthChallengePayload>('/api/v1/auth/challenge'),
  login: (payload: {
    username: string;
    password: string;
    challenge_id: string;
    math_answer: number;
    pow_nonce: number;
  }) =>
    request<AuthStatusPayload>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  logout: () => request<{ status: string; authenticated: boolean }>('/api/v1/auth/logout', {
    method: 'POST',
    body: JSON.stringify({}),
  }),
  status: () => request<StatusPayload>('/api/v1/status'),
  autonomousStatus: () => request<AutonomousStatusPayload>('/api/v1/autonomous/status'),
  operationsStreamUrl: () => `${API_BASE}/api/v1/stream/operations`,
  products: (params: {
    search?: string;
    provider?: string;
    market_type?: string;
    product_type?: string;
    active?: boolean;
    watchlist_id?: string;
    watched_only?: boolean;
    page?: number;
    page_size?: number;
  }) => request<ProductListPayload>(`/api/v1/products${queryString(params)}`),
  product: (productId: string, watchlistId?: string) =>
    request<ProductDetailPayload>(`/api/v1/products/${encodeURIComponent(productId)}${queryString({ watchlist_id: watchlistId })}`),
  watchlists: () => request<WatchlistsPayload>('/api/v1/watchlists'),
  upsertWatchlistItem: (
    watchlistId: string,
    productId: string,
    payload: {
      preferred_instrument_id?: string | null;
      research_mode: WatchlistResearchMode;
      notes?: string;
      tags?: string[];
      alert_policy?: Record<string, unknown>;
      sort_order?: number;
    },
  ) => request<{ status: string; watchlist_id: string; item: Record<string, unknown> }>(
    `/api/v1/watchlists/${encodeURIComponent(watchlistId)}/items/${encodeURIComponent(productId)}`,
    { method: 'PUT', body: JSON.stringify(payload) },
  ),
  deleteWatchlistItem: (watchlistId: string, productId: string) =>
    request<{ status: string; watchlist_id: string; product_id: string }>(
      `/api/v1/watchlists/${encodeURIComponent(watchlistId)}/items/${encodeURIComponent(productId)}`,
      { method: 'DELETE' },
    ),
  paperExecutionStatus: () => request<PaperExecutionStatusPayload>('/api/v1/paper-execution/status'),
  exchangeAccounts: (params: { pnl_range: AccountPnlRange; start_at?: string; end_at?: string }) =>
    request<ExchangeAccountsPayload>(`/api/v1/exchange-accounts${queryString(params)}`),
  leveragePreview: (payload: Record<string, unknown>) =>
    request<LeveragePreviewResult>('/api/v1/quant/risk/leverage-preview', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  executionRiskGate: (payload: Record<string, unknown>) =>
    request<ExecutionRiskGateResult>('/api/v1/quant/risk/execution-gate', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  runAutonomousNow: (payload: { trigger_type?: string }) =>
    request<{ status: string; mode?: string; request?: Record<string, unknown>; trigger_type?: string; started_at?: string }>('/api/v1/autonomous/run-now', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  submitInstantResearch: (payload: { query: string; symbols?: string[] } & InstantProductContext) =>
    request<{ status: string; mode: string; session: InstantResearchSession }>('/api/v1/instant-research', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  instantResearchSessions: (limit = 20) =>
    request<InstantResearchListPayload>(`/api/v1/instant-research${queryString({ limit })}`),
  instantResearchSession: (sessionId: string) =>
    request<InstantResearchSession>(`/api/v1/instant-research/${encodeURIComponent(sessionId)}`),
  instantResearchStreamUrl: (sessionId: string) =>
    `${API_BASE}/api/v1/instant-research/${encodeURIComponent(sessionId)}/events`,
  decisionReviews: (status?: DecisionReviewStatus, limit = 100) =>
    request<DecisionReviewInbox>(`/api/v1/decision-reviews${queryString({ status, limit })}`),
  decisionReview: (decisionId: string) =>
    request<DecisionReviewItem>(`/api/v1/decision-reviews/${encodeURIComponent(decisionId)}`),
  updateDecisionReview: (
    decisionId: string,
    payload: { status: DecisionReviewStatus; note: string; expected_version: number },
  ) => request<DecisionReviewItem>(`/api/v1/decision-reviews/${encodeURIComponent(decisionId)}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  }),
  executeReviewedDecision: (
    decisionId: string,
    payload: { adapter_ids?: string[]; confirm_simulated_execution: boolean },
  ) => request<PaperExecutionReport>(`/api/v1/decision-reviews/${encodeURIComponent(decisionId)}/paper-execution`, {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  researchHistory: (limit = 50, status?: string) =>
    request<ResearchHistoryList>(`/api/v1/history/runs${queryString({ limit, status })}`),
  researchHistoryRun: (loopRunId: string) =>
    request<ResearchHistoryRunDetail>(`/api/v1/history/runs/${encodeURIComponent(loopRunId)}`),
  compareResearchRuns: (left: string, right: string) =>
    request<ResearchHistoryComparison>(`/api/v1/history/runs/compare${queryString({ left, right })}`),
  replayResearchRun: (loopRunId: string) =>
    request<{ status: string; replay: Record<string, unknown>; request: Record<string, unknown> }>(
      `/api/v1/history/runs/${encodeURIComponent(loopRunId)}/replay`,
      { method: 'POST', body: JSON.stringify({}) },
    ),
  resumeResearchRun: (loopRunId: string, fromStep?: string) =>
    request<{ status: string; resume: Record<string, unknown>; job: JobRecord }>(
      `/api/v1/history/runs/${encodeURIComponent(loopRunId)}/resume`,
      { method: 'POST', body: JSON.stringify({ from_step: fromStep || null }) },
    ),
  workflowVersions: (templateId?: string) =>
    request<WorkflowVersionsPayload>(`/api/v1/workflows/versions${queryString({ template_id: templateId })}`),
  saveWorkflowDraft: (payload: {
    template: CouncilTemplateConfig;
    workflow_version_id?: string | null;
    parent_version_id?: string | null;
    expected_checksum?: string | null;
    change_note?: string;
  }) => request<{ status: string; version: WorkflowVersionRecord; estimate: WorkflowEstimate }>('/api/v1/workflows/drafts', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  publishWorkflowVersion: (workflowVersionId: string) =>
    request<{ status: string; version: WorkflowVersionRecord; ai_config: AIConfigPayload }>(
      `/api/v1/workflows/versions/${encodeURIComponent(workflowVersionId)}/publish`,
      { method: 'POST', body: JSON.stringify({}) },
    ),
  rollbackWorkflowVersion: (workflowVersionId: string, publish = true) =>
    request<{ status: string; version: WorkflowVersionRecord; ai_config?: AIConfigPayload }>(
      `/api/v1/workflows/versions/${encodeURIComponent(workflowVersionId)}/rollback`,
      { method: 'POST', body: JSON.stringify({ publish }) },
    ),
  estimateWorkflow: (template: CouncilTemplateConfig, rounds = 3) =>
    request<WorkflowEstimate>('/api/v1/workflows/estimate', {
      method: 'POST',
      body: JSON.stringify({ template, rounds }),
    }),
  testWorkflowNode: (payload: { template: CouncilTemplateConfig; node_id: string; workflow_version_id?: string | null; sample_input?: Record<string, unknown> }) =>
    request<{ status: string; test: WorkflowNodeTestRecord }>('/api/v1/workflows/node-test', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  workflowSchema: () => request<WorkflowSchemaPayload>('/api/v1/workflows/schema'),
  planWorkflow: (payload: {
    trigger_type?: string;
    query?: string;
    template_id?: string;
    depth?: 'quick' | 'standard' | 'deep';
    rounds?: number;
    product_id?: string;
    symbol?: string;
    product_type?: string;
    market_type?: string;
    evidence_status?: string;
  }) => request<WorkflowDirectorPlan>('/api/v1/workflows/director/plan', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  workflowRuns: (params: { template_id?: string; status?: string; limit?: number } = {}) =>
    request<{ status: string; count: number; runs: WorkflowRunSummary[] }>(`/api/v1/workflows/runs${queryString(params)}`),
  workflowRun: (workflowRunId: string) =>
    request<WorkflowRunDetail>(`/api/v1/workflows/runs/${encodeURIComponent(workflowRunId)}`),
  runWorkflow: (payload: {
    trigger_type?: string;
    query?: string;
    template_id?: string;
    depth?: 'quick' | 'standard' | 'deep';
    rounds?: number;
    workflow_version_id?: string;
    dry_run?: boolean;
    product_id?: string;
    symbol?: string;
    product_type?: string;
    market_type?: string;
    evidence_status?: string;
  }) => request<WorkflowRunDetail>('/api/v1/workflows/runs', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  resumeWorkflowRun: (workflowRunId: string, nodeOutputs: Record<string, Record<string, unknown>>) =>
    request<WorkflowRunDetail>(`/api/v1/workflows/runs/${encodeURIComponent(workflowRunId)}/resume`, {
      method: 'POST',
      body: JSON.stringify({ node_outputs: nodeOutputs }),
    }),
  workflowLearning: (templateId?: string, limit = 100) =>
    request<WorkflowLearningPayload>(`/api/v1/workflows/learning${queryString({ template_id: templateId, limit })}`),
  researchFeedback: () => request<ResearchFeedbackPayload>('/api/v1/feedback'),
  refreshResearchFeedback: () => request<ResearchFeedbackPayload>('/api/v1/feedback/refresh', {
    method: 'POST',
    body: JSON.stringify({}),
  }),
  updateNotification: (notificationId: string, status: 'read' | 'dismissed') =>
    request<{ status: string; notification: ResearchNotification }>(`/api/v1/notifications/${encodeURIComponent(notificationId)}`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  config: () => request<SystemConfigPayload>('/api/v1/config'),
  updateConfig: (payload: { values: Record<string, unknown>; clear_keys?: string[] }) =>
    request<SystemConfigPayload>('/api/v1/config', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  setup: () => request<SetupPayload>('/api/v1/setup'),
  applySetupProfile: (payload: { profile_id: string; preserve_existing: boolean }) =>
    request<SetupPayload>('/api/v1/setup/apply', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  aiConfig: () => request<AIConfigPayload>('/api/v1/ai/config'),
  updateAiConfig: (payload: Pick<AIConfigPayload, 'sites' | 'task_bindings' | 'prompts' | 'council_templates' | 'experiments'>) =>
    request<AIConfigPayload>('/api/v1/ai/config', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  refreshAiModels: (payload: { site_id: string; protocol: string }) =>
    request<AIModelRefreshResponse>('/api/v1/ai/config/models/refresh', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  jobs: () => request<{ status: string; jobs: JobRecord[] }>('/api/v1/jobs'),
  job: (jobId: string) => request<JobRecord>(`/api/v1/jobs/${jobId}`),
  latestReport: (kind: string) => request<Record<string, unknown>>(`/api/v1/reports/latest/${kind}`),
  proxyDiagnostics: (startBridges = false) =>
    request<ProxyDiagnostics>(`/api/v1/proxy/diagnostics?start_bridges=${startBridges ? 'true' : 'false'}`),
  submitResearchPipeline: (payload: Record<string, unknown>) =>
    request<JobSubmitResponse>('/api/v1/jobs/research-pipeline', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  submitOperatorWorkbench: (payload: Record<string, unknown>) =>
    request<JobSubmitResponse>('/api/v1/jobs/operator-workbench', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  submitProxyDiagnostics: (payload: Record<string, unknown>) =>
    request<JobSubmitResponse>('/api/v1/jobs/proxy-diagnostics', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
};
