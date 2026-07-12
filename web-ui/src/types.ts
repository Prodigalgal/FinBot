export type JobStatus = 'queued' | 'running' | 'succeeded' | 'failed';

export interface JobRecord {
  job_id: string;
  kind: string;
  status: JobStatus;
  request: Record<string, unknown>;
  created_at: string;
  started_at?: string | null;
  finished_at?: string | null;
  result?: Record<string, unknown> | null;
  error?: string | null;
}

export interface StatusPayload {
  status: string;
  service: string;
  generated_at: string;
  data_dir: string;
  reports_dir: string;
  counts: Record<string, number>;
  source_statuses: Record<string, number>;
  autonomous_scheduler?: AutonomousSchedulerSnapshot;
  latest_autonomous_loop?: AutonomousLoopRun | null;
  latest_pipeline_run?: {
    run_id: string;
    profile: string;
    status: string;
    started_at: string;
    finished_at?: string | null;
    error?: string | null;
    summary?: Record<string, unknown>;
    steps?: Array<Record<string, unknown>>;
  } | null;
  latest_advisory_report?: {
    report_id: string;
    profile: string;
    status: string;
    generated_at: string;
    summary?: Record<string, unknown>;
  } | null;
  jobs: JobRecord[];
  policy: Record<string, boolean>;
}

export interface AutonomousSchedulerSnapshot {
  mode?: 'worker' | 'embedded';
  status: string;
  enabled: boolean;
  interval_minutes: number;
  running: boolean;
  next_run_at?: string | null;
  current_trigger_type?: string | null;
  last_started_at?: string | null;
  last_finished_at?: string | null;
  last_loop_run_id?: string | null;
  last_error?: string | null;
  active_worker_count?: number;
  queue?: Record<string, number>;
}

export interface AutonomousRunRequest {
  request_id: string;
  trigger_type: string;
  status: string;
  requested_at: string;
  started_at?: string | null;
  finished_at?: string | null;
  worker_id?: string | null;
  loop_run_id?: string | null;
  attempt: number;
  error?: string | null;
  payload: Record<string, unknown>;
  result: Record<string, unknown>;
}

export interface AutonomousWorkerSnapshot {
  queue: Record<string, number>;
  workers: Array<{
    worker_id: string;
    status: string;
    process_id?: number | null;
    hostname?: string | null;
    heartbeat_at: string;
    current_request_id?: string | null;
    last_loop_run_id?: string | null;
    last_error?: string | null;
    active?: boolean;
  }>;
  leases: Array<Record<string, unknown>>;
  scheduler?: Record<string, unknown> | null;
  recent_requests: AutonomousRunRequest[];
}

export interface UniverseInstrument {
  instrument_id: string;
  rank_index: number;
  score: number;
  provider: string;
  market_type: string;
  symbol: string;
  normalized_symbol: string;
  base_asset: string;
  quote_asset?: string | null;
  contract: boolean;
  contract_size?: number | null;
  sources: string[];
  reasons: string[];
  leverage?: Record<string, unknown>;
}

export interface UniverseRun {
  universe_run_id: string;
  loop_run_id?: string | null;
  mode: string;
  status: string;
  summary: Record<string, unknown>;
  created_at: string;
  instruments: UniverseInstrument[];
}

export interface AutonomousLoopStep {
  step_id: string;
  step_name: string;
  status: string;
  attempt: number;
  started_at?: string | null;
  finished_at?: string | null;
  duration_ms?: number | null;
  input?: Record<string, unknown>;
  output?: Record<string, unknown>;
  error?: string | null;
}

export interface DecisionReadiness {
  status: 'ready' | 'needs-followup' | 'blocked' | 'empty' | string;
  decision_ready: boolean;
  simulation_eligible: boolean;
  human_review_required: boolean;
  reasons: string[];
  valid_market_data_count: number;
  insufficient_market_data_count: number;
  recommendation_count: number;
  directional_recommendation_count: number;
  unconfirmed_research_count: number;
  research_statuses: Record<string, number>;
}

export interface AutonomousLoopRun {
  loop_run_id: string;
  status: string;
  trigger_type: string;
  config: Record<string, unknown>;
  summary: Record<string, unknown>;
  decision_readiness?: DecisionReadiness | null;
  started_at: string;
  finished_at?: string | null;
  error?: string | null;
  steps?: AutonomousLoopStep[];
}

export interface ProductRecommendation {
  symbol?: string | null;
  normalized_symbol?: string | null;
  provider?: string | null;
  market_type?: string | null;
  action: string;
  status: string;
  confidence: number;
  score: number;
  horizon?: string | null;
  entry_reference?: number | null;
  target_price?: number | null;
  invalidation_price?: number | null;
  risk_distance_pct?: number | null;
  reward_risk_ratio?: number | null;
  research_context?: Record<string, unknown>;
  rationale?: string[];
  risk_warnings?: string[];
  policy?: Record<string, unknown>;
}

export interface AIDebateMessage {
  message_id?: string | null;
  round_index?: number | null;
  turn_index?: number | null;
  phase_id?: string | null;
  message_type?: string | null;
  reply_to_message_ids?: string[];
  workflow_node_id?: string | null;
  upstream_role_ids?: string[];
  agent_role?: string | null;
  stance?: string | null;
  status: string;
  provider?: string | null;
  model?: string | null;
  content?: Record<string, unknown>;
  error?: string | null;
}

export interface AIDebateCouncil {
  debate_id: string;
  loop_run_id: string;
  research_pipeline_run_id?: string | null;
  operator_report_id?: string | null;
  status: string;
  protocol?: string | null;
  provider?: string | null;
  model?: string | null;
  rounds: number;
  template_id?: string | null;
  summary: Record<string, unknown>;
  round_summaries?: Array<Record<string, unknown>>;
  messages: AIDebateMessage[];
  error?: string | null;
  created_at: string;
}

export interface AITradeDecision extends ProductRecommendation {
  decision_id: string;
  loop_run_id: string;
  debate_id?: string | null;
  source_report_id?: string | null;
  candidate_id?: string | null;
  position_sizing?: Record<string, unknown>;
  evidence_refs?: string[];
  invalidation_conditions?: string[];
  ai_site_id?: string | null;
  ai_model?: string | null;
  prompt_version?: string | null;
  experiment_id?: string | null;
  variant_id?: string | null;
  ai_provenance?: Record<string, unknown>;
  created_at: string;
}

export interface RecommendationEvaluationReport {
  status: string;
  evaluation_run_id?: string;
  summary?: {
    evaluated_count?: number;
    directional_sample_count?: number;
    directional_hit_rate?: number | null;
    average_directional_return_pct?: number | null;
    max_drawdown_pct?: number | null;
    brier_score?: number | null;
    expected_calibration_error?: number | null;
    current_statuses?: Record<string, number>;
  };
  metrics?: Record<string, unknown>;
}

export interface PortfolioRiskReport {
  status: string;
  risk_report_id?: string;
  summary?: {
    risk_status?: string;
    directional_exposure_count?: number;
    gross_notional_pct?: number | null;
    largest_product_concentration_pct?: number | null;
    largest_correlated_cluster_concentration_pct?: number | null;
    worst_hypothetical_stress_loss_pct?: number | null;
    correlation_available_pair_count?: number;
    correlation_pair_count?: number;
    risk_gate_reason_count?: number;
  };
  risk_gate?: Record<string, unknown>;
}

export interface AIGovernanceReport {
  status: string;
  governance_report_id?: string;
  summary?: {
    governance_status?: string;
    invocation_count?: number;
    successful_invocation_count?: number;
    total_tokens?: number;
    estimated_cost_usd?: number | null;
    known_cost_subtotal_usd?: number;
    cost_status?: string;
    claim_count?: number;
    claim_evidence_coverage?: number | null;
    warning_count?: number;
  };
  budget?: Record<string, unknown>;
}

export interface AutonomousStatusPayload {
  status: string;
  generated_at: string;
  data_dir: string;
  scheduler: AutonomousSchedulerSnapshot;
  worker: AutonomousWorkerSnapshot;
  config: Record<string, unknown>;
  recent_runs: AutonomousLoopRun[];
  latest_result_loop_run_id?: string | null;
  latest_decision_readiness?: DecisionReadiness | null;
  latest_recommendations: ProductRecommendation[];
  latest_ai_debates: AIDebateCouncil[];
  latest_ai_decisions: AITradeDecision[];
  latest_universe?: UniverseRun | null;
  latest_evaluation?: RecommendationEvaluationReport | null;
  latest_portfolio_risk?: PortfolioRiskReport | null;
  latest_ai_governance?: AIGovernanceReport | null;
  policy: Record<string, unknown>;
}

export type InstantResearchStatus = 'queued' | 'running' | 'succeeded' | 'partial' | 'failed' | 'cancelled';

export interface InstantProductContext {
  product_id?: string;
  preferred_instrument_id?: string;
  watchlist_id?: string;
  provider?: string;
  market_type?: string;
}

export interface InstantResearchSeed extends InstantProductContext {
  seed_id: string;
  query: string;
  symbols: string[];
  display_name?: string;
}

export interface InstantResearchPipeline {
  run_id: string;
  profile: string;
  status: string;
  triggered_by: string;
  config: Record<string, unknown>;
  summary: Record<string, unknown>;
  started_at: string;
  finished_at?: string | null;
  error?: string | null;
  steps: AutonomousLoopStep[];
}

export interface InstantResearchSessionSummary {
    session_id: string;
    query: string;
    product_context?: InstantProductContext;
    status: InstantResearchStatus;
  stage: 'queued' | 'preparing' | 'collect' | 'analyze' | 'result' | 'failed';
  requested_at: string;
  started_at?: string | null;
  finished_at?: string | null;
  error?: string | null;
  loop_run_id?: string | null;
  progress: {
    completed_steps: number;
    total_steps: number;
      running_step?: string | null;
      queue_status: string;
    };
}

export interface InstantResearchSession extends InstantResearchSessionSummary {
    loop_run?: AutonomousLoopRun | null;
    research_pipeline?: InstantResearchPipeline | null;
  debate?: AIDebateCouncil | null;
  decisions: AITradeDecision[];
  recommendations: ProductRecommendation[];
  policy: {
    execution_allowed: false;
    paper_execution_allowed: false;
    hidden_chain_of_thought_exposed: false;
  };
}

export interface InstantResearchListPayload {
    status: string;
    count: number;
    sessions: InstantResearchSessionSummary[];
}

export interface MarketSnapshot {
  last_price?: number | null;
  bid?: number | null;
  ask?: number | null;
  volume_24h?: number | null;
  turnover_24h?: number | null;
  price_change_pct_24h?: number | null;
  captured_at?: string | null;
}

export type WatchlistResearchMode = 'monitor' | 'research' | 'pinned';

export interface WatchlistItem {
  watchlist_item_id: string;
  watchlist_id: string;
  preferred_instrument_id?: string | null;
  research_mode: WatchlistResearchMode;
  notes: string;
  tags: string[];
  alert_policy: Record<string, unknown>;
  sort_order: number;
  updated_at: string;
  is_preferred?: boolean;
}

export interface CatalogInstrument {
  instrument_id: string;
  product_id: string;
  provider: string;
  market_type: string;
  symbol: string;
  normalized_symbol: string;
  base_asset: string;
  quote_asset?: string | null;
  settle_asset?: string | null;
  active: boolean;
  contract: boolean;
  linear?: boolean | null;
  inverse?: boolean | null;
  contract_size?: number | null;
  expiry?: string | null;
  tick_size?: number | null;
  amount_step?: number | null;
  min_amount?: number | null;
  min_notional?: number | null;
  source_url?: string | null;
  captured_at: string;
  asset_class: string;
  product_type: string;
  display_name: string;
  product_status: string;
  leverage: Record<string, unknown>;
  product_metadata: Record<string, unknown>;
  market_snapshot: MarketSnapshot;
  watchlist_item?: WatchlistItem | null;
}

export interface WatchlistSummary {
  watchlist_id: string;
  name: string;
  description: string;
  is_default: boolean;
  created_at: string;
  updated_at: string;
  item_count?: number;
  monitor_count?: number;
  research_count?: number;
  pinned_count?: number;
}

export interface ProductListPayload {
  status: string;
  page: number;
  page_size: number;
  total: number;
  total_pages: number;
  watchlist?: WatchlistSummary | null;
  items: CatalogInstrument[];
}

export interface CanonicalProduct {
  product_id: string;
  asset_class: string;
  product_type: string;
  base_asset: string;
  quote_asset?: string | null;
  display_name: string;
  status: string;
  metadata: Record<string, unknown>;
  updated_at: string;
}

export interface ProductDetailPayload {
  status: string;
  product: CanonicalProduct;
  watchlist?: WatchlistSummary | null;
  watchlist_item?: WatchlistItem | null;
  instruments: CatalogInstrument[];
}

export interface WatchlistsPayload {
  status: string;
  count: number;
  watchlists: WatchlistSummary[];
}

export interface PaperExecutionAdapterStatus {
  adapter_id: string;
  display_name: string;
  provider: string;
  environment: string;
  enabled: boolean;
  credentials_configured: boolean;
  credentials_verified?: boolean | null;
  status: string;
  dry_run_ready?: boolean;
  blockers: string[];
  credential_probe?: {
    status: string;
    checked_at?: string | null;
    attempt_count: number;
    last_http_status?: number | null;
    last_code?: string | number | null;
    reason: string;
  };
}

export interface PaperExecutionStatusPayload {
  status: string;
  generated_at: string;
  enabled: boolean;
  mode: 'dry_run' | 'submit';
  adapters: PaperExecutionAdapterStatus[];
  policy: {
    max_orders_per_adapter: number;
    max_notional_usdt: number;
    min_confidence: number;
    require_human_review: boolean;
    real_trading_allowed: boolean;
    mainnet_private_api_allowed: boolean;
  };
  recent_runs: Array<Record<string, unknown>>;
  recent_executions: Array<Record<string, unknown>>;
}

export type AccountPnlRange = 'all' | '24h' | '7d' | '30d' | 'custom';

export interface ExchangePositionSnapshot {
  symbol: string;
  side: 'long' | 'short' | 'unknown';
  size: number;
  leverage?: number | null;
  entry_price?: number | null;
  mark_price?: number | null;
  liquidation_price?: number | null;
  position_value_usdt?: number | null;
  margin_usdt?: number | null;
  unrealized_pnl_usdt?: number | null;
  realized_pnl_usdt?: number | null;
  roe_pct?: number | null;
  updated_at?: string | null;
}

export interface ExchangeAccountSnapshot {
  adapter_id: string;
  display_name: string;
  provider: string;
  environment: string;
  status: string;
  currency: string;
  total_equity_usdt?: number | null;
  wallet_balance_usdt?: number | null;
  available_balance_usdt?: number | null;
  margin_used_usdt?: number | null;
  maintenance_margin_usdt?: number | null;
  unrealized_pnl_usdt?: number | null;
  realized_pnl_usdt?: number | null;
  realized_pnl_complete: boolean;
  realized_pnl_record_count: number;
  position_count: number;
  positions: ExchangePositionSnapshot[];
  warnings: string[];
  error?: string | null;
  fetched_at?: string | null;
  metadata: Record<string, unknown>;
}

export interface ExchangeAccountsPayload {
  status: string;
  generated_at: string;
  currency_basis: 'USDT-equivalent';
  pnl_window: {
    mode: AccountPnlRange;
    start_at?: string | null;
    end_at: string;
  };
  totals: {
    total_equity_usdt?: number | null;
    wallet_balance_usdt?: number | null;
    available_balance_usdt?: number | null;
    margin_used_usdt?: number | null;
    maintenance_margin_usdt?: number | null;
    unrealized_pnl_usdt?: number | null;
    realized_pnl_usdt?: number | null;
    total_pnl_usdt?: number | null;
    position_count: number;
    account_count: number;
    realized_pnl_complete: boolean;
  };
  accounts: ExchangeAccountSnapshot[];
  policy: {
    read_only: true;
    simulated_accounts_only: true;
    mainnet_private_api_allowed: false;
    write_requests_allowed: false;
  };
}

export type DecisionReviewStatus = 'pending' | 'approved' | 'rejected' | 'changes_requested';

export interface DecisionReview {
  review_id: string;
  decision_id: string;
  loop_run_id: string;
  status: DecisionReviewStatus;
  reviewer_id: string;
  note: string;
  version: number;
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
  reviewed_at?: string | null;
}

export interface DecisionReviewItem {
  decision: AITradeDecision;
  review: DecisionReview;
  decision_readiness: DecisionReadiness;
  approval_eligible: boolean;
  approval_blockers: string[];
}

export interface DecisionReviewInbox {
  status: string;
  count: number;
  counts: Record<string, number>;
  items: DecisionReviewItem[];
}

export interface PaperExecutionReport {
  status: string;
  execution_run_id: string;
  loop_run_id: string;
  summary: {
    execution_count: number;
    status_counts: Record<string, number>;
    adapter_counts: Record<string, number>;
    reason_count: number;
    reasons: string[];
  };
  executions: Array<Record<string, unknown>>;
}

export interface ResearchHistoryRunSummary {
  loop_run_id: string;
  run_status: string;
  trigger_type: string;
  started_at: string;
  finished_at?: string | null;
  error?: string | null;
  config: Record<string, unknown>;
  summary: Record<string, unknown>;
  decision_readiness?: DecisionReadiness | null;
  total_duration_ms?: number | null;
  decision_count: number;
  directional_count: number;
  research_pipeline_status?: string | null;
}

export interface ResearchHistoryRunDetail extends ResearchHistoryRunSummary {
  steps: AutonomousLoopStep[];
  research_pipeline?: InstantResearchPipeline | null;
  decisions: Array<AITradeDecision & { review?: Partial<DecisionReview> }>;
  portfolio_risk: PortfolioRiskReport | Record<string, unknown>;
  ai_governance: AIGovernanceReport | Record<string, unknown>;
  evaluations: RecommendationEvaluationReport[];
  paper_executions: Array<Record<string, unknown>>;
  oms_orders: Array<Record<string, unknown>>;
  shadow_positions: ShadowPosition[];
  timeline: ResearchTimelineEvent[];
  replays: Array<Record<string, unknown>>;
}

export interface ResearchTimelineEvent {
  event_id: string;
  timestamp: string;
  stage: string;
  event_type: string;
  status: string;
  title: string;
  detail?: string | null;
  entity_type: string;
  entity_id?: string | null;
}

export interface ResearchHistoryList {
  status: string;
  count: number;
  items: ResearchHistoryRunSummary[];
}

export interface ResearchHistoryComparison {
  status: string;
  left_loop_run_id: string;
  right_loop_run_id: string;
  metric_changes: Array<{ field: string; left: unknown; right: unknown; changed: boolean }>;
  decision_changes: Array<{
    key: string;
    left?: Record<string, unknown> | null;
    right?: Record<string, unknown> | null;
    changed: boolean;
  }>;
}

export interface ShadowPosition {
  position_id: string;
  decision_id: string;
  review_id: string;
  loop_run_id: string;
  provider?: string | null;
  market_type?: string | null;
  symbol: string;
  normalized_symbol?: string | null;
  side: 'BUY' | 'SELL' | string;
  status: string;
  quantity: number;
  notional_usdt: number;
  entry_price: number;
  current_price?: number | null;
  exit_price?: number | null;
  unrealized_pnl_usdt?: number | null;
  realized_pnl_usdt?: number | null;
  opened_at: string;
  marked_at?: string | null;
  closed_at?: string | null;
  metadata: Record<string, unknown>;
}

export interface ShadowPortfolioSnapshot {
  snapshot_id?: string;
  status: string;
  source_loop_run_id?: string | null;
  equity_usdt?: number | null;
  cash_usdt?: number | null;
  gross_exposure_usdt?: number | null;
  realized_pnl_usdt?: number | null;
  unrealized_pnl_usdt?: number | null;
  peak_equity_usdt?: number | null;
  drawdown_pct?: number | null;
  positions: ShadowPosition[];
  metrics: {
    position_count?: number;
    open_position_count?: number;
    closed_position_count?: number;
    missing_mark_count?: number;
    mark_coverage?: number | null;
    initial_cash_usdt?: number;
    position_notional_usdt?: number;
  };
  created_at?: string;
}

export interface ResearchNotification {
  notification_id: string;
  category: string;
  severity: string;
  title: string;
  body: string;
  status: 'unread' | 'read' | 'dismissed';
  entity_type?: string | null;
  entity_id?: string | null;
  payload: Record<string, unknown>;
  created_at: string;
  read_at?: string | null;
  dismissed_at?: string | null;
}

export interface ResearchFeedbackPayload {
  status: string;
  generated_at: string;
  shadow_portfolio: ShadowPortfolioSnapshot;
  backtest: {
    status: string;
    sample_count: number;
    evaluated_count: number;
    pending_count: number;
    insufficient_data_count: number;
    hit_rate?: number | null;
    average_return_pct?: number | null;
    cumulative_return_pct?: number | null;
    max_drawdown_pct?: number | null;
    methodology: Record<string, unknown>;
  };
  calibration: {
    status: string;
    sample_count: number;
    brier_score?: number | null;
    expected_calibration_error?: number | null;
    bins: Array<{
      lower: number;
      upper: number;
      sample_count: number;
      average_confidence: number;
      actual_hit_rate: number;
    }>;
  };
  reflections: Array<{
    code: string;
    severity: string;
    title: string;
    detail: string;
    automatic_change_allowed: false;
  }>;
  notifications: {
    status: string;
    count: number;
    counts: Record<string, number>;
    items: ResearchNotification[];
  };
  policy: Record<string, boolean>;
}

export interface JobSubmitResponse {
  status: string;
  job: JobRecord;
}

export interface ProxyDiagnostics {
  status: string;
  runtime: Record<string, unknown>;
  targets: Array<{
    route: string;
    url: string;
    decision: {
      status: string;
      proxy: string;
      reason?: string | null;
      proxy_ip_family?: string;
      policy?: Record<string, unknown>;
    };
  }>;
}

export type ConfigFieldKind = 'string' | 'secret' | 'multiline' | 'select' | 'boolean' | 'integer' | 'number' | 'string_list';

export interface ConfigFieldSpec {
  key: string;
  group: string;
  label: string;
  kind: ConfigFieldKind;
  default?: unknown;
  help?: string;
  sensitive: boolean;
  hot_reload: boolean;
  restart_required: boolean;
  options: string[];
  minimum?: number | null;
  maximum?: number | null;
  multiline?: boolean;
}

export interface ConfigValueState {
  value: unknown;
  configured: boolean;
  source: string;
  sensitive: boolean;
}

export interface SystemConfigPayload {
  status: string;
  version: number;
  runtime_config_path: string;
  proxy_policy_path?: string | null;
  updated_at?: string | null;
  schema: ConfigFieldSpec[];
  values: Record<string, ConfigValueState>;
  hot_reload?: {
    status: string;
    scope: string;
  };
}

export interface SetupProfileSummary {
  profile_id: string;
  display_name: string;
  description: string;
  recommended: boolean;
  highlights: string[];
  value_count: number;
  missing_value_count: number;
  configured_value_count: number;
}

export interface SetupReadinessCheck {
  check_id: string;
  label: string;
  status: 'passed' | 'failed';
  required: boolean;
  detail: string;
}

export interface SetupPayload {
  status: string;
  version: number;
  profiles: SetupProfileSummary[];
  readiness: {
    status: 'ready' | 'needs_attention' | 'blocked';
    passed_count: number;
    check_count: number;
    required_failure_count: number;
    warning_count: number;
    checks: SetupReadinessCheck[];
  };
  defaults: {
    runtime_default_value_count: number;
    configured_runtime_value_count: number;
    ai_site_count: number;
    ai_task_count: number;
    council_role_preset_count: number;
    setup_profile_count: number;
  };
  application?: {
    status: string;
    profile_id: string;
    preserve_existing: boolean;
    applied_keys: string[];
    preserved_keys: string[];
    skipped_keys: string[];
  };
}

export interface AISiteConfig {
  site_id: string;
  display_name: string;
  enabled: boolean;
  base_url: string;
  api_key_configured?: boolean;
  api_key?: string;
  chat_models: string[];
  responses_models: string[];
  default_chat_model?: string | null;
  default_responses_model?: string | null;
  timeout_seconds: number;
  input_cost_per_million_tokens?: number | null;
  output_cost_per_million_tokens?: number | null;
  pricing_model?: string | null;
  pricing_currency?: string | null;
  pricing_basis?: string | null;
  pricing_source_url?: string | null;
  pricing_checked_at?: string | null;
}

export interface AITaskMeta {
  task_id: string;
  label: string;
  description: string;
  default_system_prompt: string;
  default_user_prompt_template: string;
}

export interface AITaskBinding {
  enabled: boolean;
  site_id?: string | null;
  protocol: string;
  model?: string | null;
  fallback_site_ids: string[];
}

export interface AIPromptConfig {
  system_prompt: string;
  user_prompt_template: string;
}

export interface CouncilRoleConfig {
  role_id: string;
  display_name: string;
  stance: string;
  objective: string;
  enabled: boolean;
  order: number;
  site_id?: string | null;
  protocol?: string | null;
  model?: string | null;
  reasoning_effort?: CouncilReasoningEffort;
  fallback_site_ids: string[];
  system_prompt?: string | null;
  user_prompt_template?: string | null;
}

export interface CouncilRolePreset extends CouncilRoleConfig {
  preset_id: string;
}

export interface AIExperimentVariant {
  variant_id: string;
  display_name: string;
  weight: number;
  site_id?: string | null;
  protocol?: string | null;
  model?: string | null;
  system_prompt_append?: string | null;
  user_prompt_template?: string | null;
}

export interface AIExperimentConfig {
  experiment_id: string;
  display_name: string;
  task_id: string;
  enabled: boolean;
  variants: AIExperimentVariant[];
}

export interface CouncilPhaseConfig {
  phase_id: string;
  label: string;
  message_type: string;
  scheduling_mode: 'parallel' | 'round_robin' | 'moderated';
  instructions: string;
  participant_role_ids?: string[];
  moderator_role_id?: string | null;
}

export interface CouncilChairConfig {
  role_id: string;
  display_name: string;
  site_id?: string | null;
  protocol?: string | null;
  model?: string | null;
  reasoning_effort?: CouncilReasoningEffort;
  fallback_site_ids: string[];
  system_prompt?: string | null;
  user_prompt_template?: string | null;
}

export type CouncilReasoningEffort = 'provider_default' | 'none' | 'minimal' | 'low' | 'medium' | 'high' | 'xhigh';

export type CouncilWorkflowNodeType =
  | 'input'
  | 'router'
  | 'deterministic'
  | 'agent'
  | 'gate'
  | 'subflow'
  | 'human_review'
  | 'aggregator'
  | 'chair';

export interface CouncilConditionConfig {
  field: string;
  operator: 'exists' | 'eq' | 'ne' | 'in' | 'not_in' | 'gt' | 'gte' | 'lt' | 'lte' | 'contains' | 'truthy' | 'falsy';
  value?: unknown;
}

export interface CouncilContextPolicyConfig {
  mode: 'upstream' | 'selected' | 'latest' | 'claims_only' | 'summary' | 'none';
  source_node_ids: string[];
  history_rounds: number;
  max_messages: number;
  content_fields: string[];
}

export interface CouncilRetryPolicyConfig {
  max_attempts: number;
  backoff_seconds: number;
}

export interface CouncilWorkflowNodeConfig {
  node_id: string;
  node_type: CouncilWorkflowNodeType;
  role_id?: string | null;
  operation?: string | null;
  phase_ids?: string[];
  config?: Record<string, unknown>;
  context_policy?: CouncilContextPolicyConfig;
  retry_policy?: CouncilRetryPolicyConfig;
  position: {
    x: number;
    y: number;
  };
}

export interface CouncilWorkflowEdgeConfig {
  edge_id: string;
  source_node_id: string;
  target_node_id: string;
  condition?: CouncilConditionConfig;
  activation_group?: string | null;
  activation_mode?: 'all' | 'any';
  context_mode?: 'inherit' | 'include' | 'exclude' | 'latest' | 'claims_only' | 'summary';
  loop?: boolean;
  max_traversals?: number;
}

export interface CouncilWorkflowConfig {
  version: 1 | 2;
  max_steps?: number;
  max_loop_iterations?: number;
  nodes: CouncilWorkflowNodeConfig[];
  edges: CouncilWorkflowEdgeConfig[];
}

export interface CouncilTemplateConfig {
  template_id: string;
  display_name: string;
  enabled: boolean;
  roles: CouncilRoleConfig[];
  phases: CouncilPhaseConfig[];
  chair: CouncilChairConfig;
  workflow: CouncilWorkflowConfig;
  quorum_ratio: number;
  max_roles: number;
  description?: string;
  cost_tier?: 'quick' | 'standard' | 'deep';
  failure_policy?: 'stop' | 'continue' | 'replan';
  round_policy?: {
    default_rounds: number;
    min_rounds: number;
    max_rounds: number;
    stop_condition?: CouncilConditionConfig;
  };
  builtin?: boolean;
  template_kind?: string;
  recommended_for?: string[];
}

export type WorkflowVersionStatus = 'draft' | 'published' | 'archived';

export interface WorkflowVersionRecord {
  workflow_version_id: string;
  template_id: string;
  version_number: number;
  status: WorkflowVersionStatus;
  content: CouncilTemplateConfig;
  checksum: string;
  parent_version_id?: string | null;
  change_note: string;
  created_by: string;
  created_at: string;
  published_at?: string | null;
}

export interface WorkflowNodeTestRecord {
  node_test_id: string;
  template_id: string;
  workflow_version_id?: string | null;
  node_id: string;
  status: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  estimated_tokens?: number | null;
  estimated_cost_usd?: number | null;
  cost_status: string;
  error?: string | null;
  created_at: string;
  finished_at?: string | null;
}

export interface WorkflowVersionsPayload {
  status: string;
  count: number;
  versions: WorkflowVersionRecord[];
  node_tests: WorkflowNodeTestRecord[];
}

export interface WorkflowEstimate {
  status: string;
  template_id: string;
  rounds: number;
  role_count: number;
  invocation_count: number;
  estimated_total_tokens: number;
  estimated_cost_usd?: number | null;
  cost_status: string;
  unknown_pricing_count: number;
  nodes: Array<Record<string, unknown>>;
  assumptions: Record<string, number>;
}

export interface WorkflowSchemaPayload {
  status: string;
  latest_version: 2;
  supported_versions: number[];
  node_types: CouncilWorkflowNodeType[];
  operations: string[];
  condition_operators: CouncilConditionConfig['operator'][];
  activation_modes: Array<'all' | 'any'>;
  context_modes: CouncilContextPolicyConfig['mode'][];
  edge_context_modes: NonNullable<CouncilWorkflowEdgeConfig['context_mode']>[];
  scheduling_modes: CouncilPhaseConfig['scheduling_mode'][];
  reasoning_efforts: CouncilReasoningEffort[];
  depth_policies: Record<string, Record<string, number>>;
  templates: Array<{
    template_id: string;
    display_name: string;
    description: string;
    builtin: boolean;
    template_kind: string;
    recommended_for: string[];
    cost_tier: string;
    workflow_version: number;
    role_count: number;
    node_count: number;
    default_rounds: number;
  }>;
  credential_policy: {
    node_reference_field: 'site_id';
    raw_key_in_workflow_allowed: false;
    raw_key_in_run_log_allowed: false;
  };
  safety: Record<string, boolean>;
}

export interface WorkflowDirectorPlan {
  status: string;
  trigger_type: string;
  objective: string;
  template_id: string;
  template_name: string;
  depth: 'quick' | 'standard' | 'deep';
  cost_tier: string;
  rounds: number;
  facts: Array<string | Record<string, unknown>>;
  assumptions: Array<string | Record<string, unknown>>;
  gaps: Array<string | Record<string, unknown>>;
  steps: Array<Record<string, unknown>>;
  budget_policy: Record<string, number>;
  revisions?: Array<Record<string, unknown>>;
}

export interface WorkflowRunSummary {
  workflow_run_id: string;
  template_id: string;
  workflow_version_id?: string | null;
  trigger_type: string;
  mode: 'dry_run' | 'live';
  status: string;
  depth: string;
  cost_tier: string;
  error?: string | null;
  version: number;
  created_at: string;
  started_at?: string | null;
  updated_at: string;
  finished_at?: string | null;
}

export interface WorkflowLedgerRecord {
  ledger_id: string;
  ledger_type: 'task' | 'progress' | 'reflection' | string;
  revision: number;
  payload: Record<string, unknown>;
  created_at: string;
}

export interface WorkflowCheckpointRecord {
  checkpoint_id: string;
  node_id: string;
  phase_id: string;
  iteration: number;
  node_type: CouncilWorkflowNodeType;
  operation?: string | null;
  status: string;
  attempt: number;
  output: Record<string, unknown>;
  error?: string | null;
  created_at: string;
  updated_at: string;
  completed_at?: string | null;
}

export interface WorkflowRunDetail extends WorkflowRunSummary {
  request: Record<string, unknown>;
  template_snapshot: CouncilTemplateConfig;
  plan: WorkflowDirectorPlan;
  result: Record<string, unknown>;
  ledgers: WorkflowLedgerRecord[];
  checkpoints: WorkflowCheckpointRecord[];
}

export interface CouncilRoleScoreRecord {
  score_id?: string;
  template_id: string;
  role_id: string;
  debate_id?: string;
  score?: number;
  total_score?: number;
  components?: Record<string, number | null>;
  created_at?: string;
  [key: string]: unknown;
}

export interface CouncilMemoryRecord {
  memory_id: string;
  template_id: string;
  role_id?: string | null;
  memory_type?: string;
  content?: Record<string, unknown> | string;
  tags?: string[];
  source_refs?: string[];
  expires_at?: string | null;
  created_at?: string;
  [key: string]: unknown;
}

export interface WorkflowLearningPayload {
  status: string;
  role_scores: CouncilRoleScoreRecord[];
  memories?: CouncilMemoryRecord[];
  memory_count: number;
  policy: Record<string, unknown>;
}

export interface AIConfigPayload {
  status: string;
  version: number;
  config_path: string;
  updated_at?: string | null;
  tasks: AITaskMeta[];
  sites: AISiteConfig[];
  task_bindings: Record<string, AITaskBinding>;
  prompts: Record<string, AIPromptConfig>;
  council_templates: CouncilTemplateConfig[];
  role_presets: CouncilRolePreset[];
  experiments: AIExperimentConfig[];
  workflow_schema?: WorkflowSchemaPayload;
  learning_summary?: Pick<WorkflowLearningPayload, 'role_scores' | 'memory_count' | 'policy'>;
}

export interface AIModelRefreshResponse {
  status: string;
  site_id: string;
  protocol: string;
  models: string[];
  config: AIConfigPayload;
}

export interface ApiError {
  message: string;
}

export interface LeveragePreviewResult {
  status: 'passed' | 'blocked';
  reasons: string[];
  venue: string;
  symbol: string;
  environment: string;
  requested_leverage: number | null;
  max_safe_leverage: number;
  effective_leverage: number;
  quantity: number;
  notional_usdt: number;
  initial_margin_usdt: number;
  estimated_max_loss_usdt: number;
  liquidation_distance_pct: number;
  approximate_liquidation_price: number;
  methodology: Record<string, unknown>;
}

export interface ExecutionRiskGateResult {
  status: 'passed' | 'blocked';
  reasons: Array<{ code: string; actual: string | number; limit: string | number }>;
  metrics: Record<string, number>;
  policy: Record<string, number>;
  ai_can_override: false;
}
