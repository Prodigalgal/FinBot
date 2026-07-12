from __future__ import annotations

import json
import sqlite3
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from finbot.calendar.official_release_registry import OfficialRelease
from finbot.ingestion.models import AdapterResult, EventCandidate, FetchJob, NormalizedDocument, RawEvidence, SourceConfig, URLCandidate


PIPELINE_LINEAGE_TABLES = {
    "ai_compressions",
    "research_cards",
    "research_card_validations",
    "research_card_decisions",
    "research_followup_dispatches",
    "research_watch_items",
    "research_briefs",
    "research_review_verdicts",
    "research_councils",
}

_SCHEMA_READY_PATHS: set[Path] = set()
_SCHEMA_INIT_LOCK = threading.Lock()


class StaleRecordError(RuntimeError):
    pass


class SQLiteStore:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path, timeout=30.0)
        conn.row_factory = sqlite3.Row
        conn.execute("pragma busy_timeout = 30000")
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    def init_schema(self) -> None:
        schema_path = self.path.resolve()
        if schema_path in _SCHEMA_READY_PATHS and self.path.exists():
            return
        with _SCHEMA_INIT_LOCK:
            if schema_path in _SCHEMA_READY_PATHS and self.path.exists():
                return
            with self.connect() as conn:
                conn.execute("pragma journal_mode = WAL")
                conn.execute("pragma synchronous = NORMAL")
                conn.executescript(
                """
                create table if not exists sources (
                  id text primary key,
                  enabled integer not null,
                  tier text not null,
                  category text not null,
                  mode text not null,
                  provider text,
                  trust_weight real not null,
                  priority text not null,
                  asset_scope_json text not null,
                  updated_at text not null
                );

                create table if not exists raw_evidence (
                  evidence_id text primary key,
                  source_id text not null,
                  job_id text not null,
                  fetched_at text not null,
                  url text,
                  query text,
                  status_code integer,
                  success integer not null,
                  request_path text,
                  response_path text,
                  headers_path text,
                  markdown_path text,
                  error text,
                  metadata_json text not null
                );

                create table if not exists fetch_jobs (
                  job_id text primary key,
                  source_id text not null,
                  mode text not null,
                  priority text not null,
                  job_type text not null,
                  url text,
                  query text,
                  provider text,
                  asset_scope_json text not null,
                  scheduled_at text not null,
                  max_results integer,
                  max_scrape_targets integer,
                  status text,
                  detail text,
                  updated_at text not null
                );

                create table if not exists fetch_runs (
                  run_id integer primary key autoincrement,
                  job_id text not null,
                  source_id text not null,
                  status text not null,
                  detail text not null,
                  success integer not null,
                  evidence_id text,
                  required_keys_json text not null,
                  metadata_json text not null,
                  ran_at text not null
                );

                create table if not exists source_health (
                  source_id text primary key,
                  status text not null,
                  detail text not null,
                  success integer not null,
                  required_keys_json text not null,
                  metadata_json text not null,
                  checked_at text not null
                );

                create table if not exists url_candidates (
                  candidate_id text primary key,
                  source_id text not null,
                  evidence_id text not null,
                  url text not null,
                  canonical_url text not null,
                  title text,
                  snippet text,
                  score real not null,
                  metadata_json text not null,
                  created_at text not null
                );

                create table if not exists normalized_documents (
                  document_id text primary key,
                  evidence_id text not null,
                  source_id text not null,
                  tier text,
                  category text,
                  trust_weight real not null,
                  canonical_url text,
                  title text,
                  published_at text,
                  fetched_at text not null,
                  language text,
                  text text not null,
                  content_hash text not null,
                  title_key text not null,
                  asset_scope_json text not null,
                  metadata_json text not null
                );

                create table if not exists dedupe_keys (
                  key_type text not null,
                  key_value text not null,
                  document_id text not null,
                  source_id text not null,
                  created_at text not null,
                  primary key (key_type, key_value)
                );

                create table if not exists event_candidates (
                  event_id text primary key,
                  event_key text not null,
                  title text not null,
                  category text,
                  asset_scope_json text not null,
                  document_ids_json text not null,
                  source_ids_json text not null,
                  confidence real not null,
                  first_seen_at text not null,
                  last_seen_at text not null,
                  summary text,
                  metadata_json text not null
                );

                create table if not exists research_packages (
                  package_id text primary key,
                  generated_at text not null,
                  time_window text not null,
                  payload_json text not null
                );

                create table if not exists official_release_calendar (
                  release_id text primary key,
                  provider text not null,
                  release_type text not null,
                  title text not null,
                  scheduled_at text not null,
                  timezone text,
                  asset_scope_json text not null,
                  expected_fields_json text not null,
                  source_url text,
                  status text not null,
                  metadata_json text not null,
                  updated_at text not null
                );

                create table if not exists market_context_snapshots (
                  snapshot_id text primary key,
                  event_id text not null,
                  event_key text not null,
                  asset_scope_json text not null,
                  provider text not null,
                  captured_at text not null,
                  status text not null,
                  market_document_ids_json text not null,
                  market_source_ids_json text not null,
                  price_change_pct real,
                  volume_change_pct real,
                  volatility_proxy real,
                  note text,
                  metadata_json text not null
                );

                create table if not exists market_quotes (
                  quote_id text primary key,
                  provider text not null,
                  market_type text not null,
                  symbol text not null,
                  normalized_symbol text not null,
                  captured_at text not null,
                  last_price real,
                  bid real,
                  ask real,
                  price_change_pct_24h real,
                  high_24h real,
                  low_24h real,
                  volume_24h real,
                  turnover_24h real,
                  source_url text,
                  payload_json text not null
                );

                create index if not exists idx_market_quotes_symbol
                  on market_quotes(normalized_symbol, provider, captured_at);

                create table if not exists market_candles (
                  candle_id text primary key,
                  provider text not null,
                  market_type text not null,
                  symbol text not null,
                  normalized_symbol text not null,
                  interval text not null,
                  open_time text not null,
                  captured_at text not null,
                  open real,
                  high real,
                  low real,
                  close real,
                  volume real,
                  turnover real,
                  payload_json text not null,
                  unique(provider, market_type, normalized_symbol, interval, open_time)
                );

                create index if not exists idx_market_candles_symbol
                  on market_candles(normalized_symbol, provider, interval, open_time);

                create table if not exists canonical_products (
                  product_id text primary key,
                  asset_class text not null,
                  product_type text not null,
                  base_asset text not null,
                  quote_asset text,
                  display_name text not null,
                  status text not null,
                  metadata_json text not null,
                  updated_at text not null
                );

                create index if not exists idx_canonical_products_assets
                  on canonical_products(asset_class, base_asset, quote_asset, product_type);

                create table if not exists venue_instruments (
                  instrument_id text primary key,
                  product_id text not null,
                  provider text not null,
                  market_type text not null,
                  symbol text not null,
                  normalized_symbol text not null,
                  base_asset text not null,
                  quote_asset text,
                  settle_asset text,
                  active integer not null,
                  contract integer not null,
                  linear integer,
                  inverse integer,
                  contract_size real,
                  expiry text,
                  tick_size real,
                  amount_step real,
                  min_amount real,
                  min_notional real,
                  leverage_json text not null,
                  source_url text,
                  captured_at text not null,
                  payload_json text not null,
                  unique(provider, market_type, symbol)
                );

                create index if not exists idx_venue_instruments_lookup
                  on venue_instruments(provider, market_type, active, quote_asset, normalized_symbol);

                create table if not exists instrument_aliases (
                  alias_key text not null,
                  provider text not null,
                  market_type text not null,
                  instrument_id text not null,
                  alias_type text not null,
                  priority integer not null,
                  updated_at text not null,
                  primary key(alias_key, provider, market_type, instrument_id)
                );

                create index if not exists idx_instrument_aliases_lookup
                  on instrument_aliases(alias_key, priority, provider, market_type);

                create table if not exists instrument_market_snapshots (
                  snapshot_id text primary key,
                  instrument_id text not null,
                  provider text not null,
                  market_type text not null,
                  symbol text not null,
                  captured_at text not null,
                  last_price real,
                  bid real,
                  ask real,
                  volume_24h real,
                  turnover_24h real,
                  price_change_pct_24h real,
                  payload_json text not null
                );

                create index if not exists idx_instrument_snapshots_rank
                  on instrument_market_snapshots(provider, market_type, captured_at, turnover_24h);

                create index if not exists idx_instrument_snapshots_latest
                  on instrument_market_snapshots(instrument_id, captured_at desc);

                create table if not exists watchlists (
                  watchlist_id text primary key,
                  owner_id text not null,
                  name text not null,
                  description text not null,
                  is_default integer not null,
                  created_at text not null,
                  updated_at text not null,
                  unique(owner_id, name)
                );

                create index if not exists idx_watchlists_owner
                  on watchlists(owner_id, is_default, updated_at);

                create table if not exists watchlist_items (
                  watchlist_item_id text primary key,
                  watchlist_id text not null,
                  product_id text not null,
                  preferred_instrument_id text,
                  research_mode text not null,
                  notes text not null,
                  tags_json text not null,
                  alert_policy_json text not null,
                  sort_order integer not null,
                  created_at text not null,
                  updated_at text not null,
                  unique(watchlist_id, product_id)
                );

                create index if not exists idx_watchlist_items_lookup
                  on watchlist_items(watchlist_id, research_mode, sort_order, updated_at);

                create index if not exists idx_watchlist_items_product
                  on watchlist_items(product_id, preferred_instrument_id);

                insert or ignore into watchlists (
                  watchlist_id, owner_id, name, description, is_default, created_at, updated_at
                ) values (
                  'watchlist-local-default', 'local', '默认关注列表',
                  '系统内置的本地产品关注列表', 1,
                  strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
                  strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                );

                create table if not exists universe_runs (
                  universe_run_id text primary key,
                  loop_run_id text,
                  mode text not null,
                  status text not null,
                  config_json text not null,
                  summary_json text not null,
                  created_at text not null
                );

                create index if not exists idx_universe_runs_created
                  on universe_runs(created_at, status);

                create table if not exists universe_items (
                  universe_run_id text not null,
                  instrument_id text not null,
                  rank_index integer not null,
                  score real not null,
                  sources_json text not null,
                  reason_json text not null,
                  created_at text not null,
                  primary key(universe_run_id, instrument_id)
                );

                create index if not exists idx_universe_items_rank
                  on universe_items(universe_run_id, rank_index);

                create table if not exists advisory_reports (
                  report_id text primary key,
                  profile text not null,
                  status text not null,
                  generated_at text not null,
                  payload_json text not null
                );

                create index if not exists idx_advisory_reports_generated
                  on advisory_reports(generated_at, status);

                create table if not exists paper_order_proposals (
                  proposal_id text primary key,
                  report_id text,
                  advice_id text not null,
                  provider text not null,
                  market_type text not null,
                  symbol text not null,
                  action text not null,
                  status text not null,
                  execution_mode text not null,
                  created_at text not null,
                  payload_json text not null
                );

                create index if not exists idx_paper_order_proposals_symbol
                  on paper_order_proposals(symbol, status, created_at);

                create table if not exists paper_execution_runs (
                  execution_run_id text primary key,
                  loop_run_id text not null unique,
                  status text not null,
                  config_json text not null,
                  summary_json text not null,
                  created_at text not null,
                  finished_at text
                );

                create index if not exists idx_paper_execution_runs_created
                  on paper_execution_runs(created_at, status);

                create table if not exists paper_executions (
                  execution_id text primary key,
                  execution_run_id text not null,
                  loop_run_id text not null,
                  decision_id text not null,
                  adapter_id text not null,
                  provider text not null,
                  environment text not null,
                  product_id text,
                  instrument_id text,
                  symbol text not null,
                  market_type text not null,
                  action text not null,
                  status text not null,
                  client_order_id text not null,
                  exchange_order_id text,
                  requested_notional real,
                  requested_quantity real,
                  filled_quantity real,
                  average_fill_price real,
                  request_json text not null,
                  response_json text not null,
                  error text,
                  created_at text not null,
                  updated_at text not null,
                  unique(decision_id, adapter_id)
                );

                create index if not exists idx_paper_executions_loop
                  on paper_executions(loop_run_id, adapter_id, status, created_at);

                create index if not exists idx_paper_executions_symbol
                  on paper_executions(provider, symbol, status, created_at);

                create table if not exists macro_release_facts (
                  fact_id text primary key,
                  source_id text not null,
                  evidence_id text not null,
                  provider text not null,
                  release_type text not null,
                  observed_at text not null,
                  fields_json text not null,
                  asset_scope_json text not null,
                  confidence real not null,
                  notes_json text not null,
                  metadata_json text not null
                );

                create table if not exists source_budget_state (
                  source_id text primary key,
                  provider text,
                  budget_window text not null,
                  requests_used integer not null,
                  credits_used real not null,
                  max_requests integer,
                  max_credits real,
                  throttled_until text,
                  last_error text,
                  status text not null,
                  metadata_json text not null,
                  updated_at text not null
                );

                create table if not exists ai_compressions (
                  compression_id text primary key,
                  pipeline_run_id text,
                  target_type text not null,
                  target_id text not null,
                  provider text not null,
                  protocol text not null,
                  model text,
                  status text not null,
                  summary_json text not null,
                  prompt_hash text not null,
                  source_refs_json text not null,
                  error text,
                  created_at text not null
                );

                create index if not exists idx_ai_compressions_target
                  on ai_compressions(target_type, target_id, created_at);

                create table if not exists research_cards (
                  card_id text primary key,
                  pipeline_run_id text,
                  event_id text not null,
                  event_key text not null,
                  readiness text not null,
                  priority text,
                  freshness_status text not null,
                  time_window text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_cards_event
                  on research_cards(event_id, created_at);

                create table if not exists research_card_validations (
                  validation_id text primary key,
                  pipeline_run_id text,
                  card_id text not null,
                  event_id text not null,
                  status text not null,
                  score real not null,
                  findings_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_card_validations_card
                  on research_card_validations(card_id, created_at);

                create table if not exists research_card_decisions (
                  decision_id text primary key,
                  pipeline_run_id text,
                  card_id text not null,
                  event_id text not null,
                  decision text not null,
                  score real not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_card_decisions_card
                  on research_card_decisions(card_id, created_at);

                create table if not exists research_followup_dispatches (
                  dispatch_id text primary key,
                  pipeline_run_id text,
                  decision_id text not null,
                  card_id text not null,
                  event_id text not null,
                  job_id text not null,
                  status text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_followup_dispatches_card
                  on research_followup_dispatches(card_id, created_at);

                create table if not exists research_watch_items (
                  watch_item_id text primary key,
                  pipeline_run_id text,
                  card_id text not null,
                  event_id text not null,
                  decision text not null,
                  priority text,
                  score real not null,
                  status text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_watch_items_status
                  on research_watch_items(status, priority, score);

                create table if not exists research_briefs (
                  brief_id text primary key,
                  pipeline_run_id text,
                  time_window text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_briefs_time_window
                  on research_briefs(time_window, created_at);

                create table if not exists research_review_verdicts (
                  verdict_id text primary key,
                  pipeline_run_id text,
                  council_id text not null,
                  watch_item_id text not null,
                  card_id text not null,
                  event_id text not null,
                  role_id text not null,
                  stance text not null,
                  confidence real not null,
                  severity text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_review_verdicts_council
                  on research_review_verdicts(council_id, watch_item_id, role_id);

                create table if not exists research_councils (
                  council_id text primary key,
                  pipeline_run_id text,
                  brief_id text,
                  time_window text not null,
                  status text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_councils_time_window
                  on research_councils(time_window, created_at);

                create table if not exists research_pipeline_runs (
                  run_id text primary key,
                  profile text not null,
                  status text not null,
                  triggered_by text,
                  config_json text not null,
                  summary_json text not null,
                  started_at text not null,
                  finished_at text,
                  error text
                );

                create index if not exists idx_research_pipeline_runs_status
                  on research_pipeline_runs(status, started_at);

                create table if not exists research_pipeline_steps (
                  step_id text primary key,
                  run_id text not null,
                  step_name text not null,
                  status text not null,
                  attempt integer not null,
                  started_at text,
                  finished_at text,
                  duration_ms integer,
                  input_json text not null,
                  output_json text not null,
                  error text,
                  created_at text not null,
                  updated_at text not null,
                  unique(run_id, step_name, attempt)
                );

                create index if not exists idx_research_pipeline_steps_run
                  on research_pipeline_steps(run_id, step_name, attempt);

                create table if not exists research_pipeline_artifacts (
                  artifact_id text primary key,
                  run_id text not null,
                  step_name text,
                  artifact_type text not null,
                  path text,
                  ref_id text,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_research_pipeline_artifacts_run
                  on research_pipeline_artifacts(run_id, step_name, artifact_type);

                create table if not exists autonomous_loop_runs (
                  loop_run_id text primary key,
                  status text not null,
                  trigger_type text not null,
                  config_json text not null,
                  summary_json text not null,
                  started_at text not null,
                  finished_at text,
                  error text
                );

                create index if not exists idx_autonomous_loop_runs_status
                  on autonomous_loop_runs(status, started_at);

                create table if not exists autonomous_loop_steps (
                  step_id text primary key,
                  loop_run_id text not null,
                  step_name text not null,
                  status text not null,
                  attempt integer not null,
                  started_at text,
                  finished_at text,
                  duration_ms integer,
                  input_json text not null,
                  output_json text not null,
                  error text,
                  created_at text not null,
                  updated_at text not null,
                  unique(loop_run_id, step_name, attempt)
                );

                create index if not exists idx_autonomous_loop_steps_run
                  on autonomous_loop_steps(loop_run_id, step_name, attempt);

                create table if not exists autonomous_loop_artifacts (
                  artifact_id text primary key,
                  loop_run_id text not null,
                  step_name text,
                  artifact_type text not null,
                  ref_id text,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_autonomous_loop_artifacts_run
                  on autonomous_loop_artifacts(loop_run_id, step_name, artifact_type);

                create table if not exists autonomous_run_requests (
                  request_id text primary key,
                  trigger_type text not null,
                  status text not null,
                  requested_at text not null,
                  available_at text not null,
                  claimed_at text,
                  started_at text,
                  finished_at text,
                  worker_id text,
                  lease_expires_at text,
                  loop_run_id text,
                  attempt integer not null,
                  dedupe_key text,
                  payload_json text not null,
                  result_json text not null,
                  error text
                );

                create unique index if not exists idx_autonomous_requests_dedupe
                  on autonomous_run_requests(dedupe_key) where dedupe_key is not null;

                create index if not exists idx_autonomous_requests_claim
                  on autonomous_run_requests(status, available_at, requested_at);

                create table if not exists autonomous_worker_leases (
                  lease_name text primary key,
                  owner_id text not null,
                  acquired_at text not null,
                  heartbeat_at text not null,
                  expires_at text not null,
                  metadata_json text not null
                );

                create table if not exists autonomous_worker_heartbeats (
                  worker_id text primary key,
                  status text not null,
                  process_id integer,
                  hostname text,
                  started_at text not null,
                  heartbeat_at text not null,
                  current_request_id text,
                  last_loop_run_id text,
                  last_error text,
                  metadata_json text not null
                );

                create index if not exists idx_autonomous_worker_heartbeat
                  on autonomous_worker_heartbeats(heartbeat_at, status);

                create table if not exists autonomous_scheduler_state (
                  schedule_id text primary key,
                  next_run_at text,
                  last_enqueued_at text,
                  last_request_id text,
                  updated_at text not null,
                  metadata_json text not null
                );

                create table if not exists ai_debate_councils (
                  debate_id text primary key,
                  loop_run_id text not null,
                  research_pipeline_run_id text,
                  operator_report_id text,
                  status text not null,
                  protocol text,
                  provider text,
                  model text,
                  rounds integer not null,
                  summary_json text not null,
                  payload_json text not null,
                  error text,
                  created_at text not null
                );

                create index if not exists idx_ai_debate_councils_loop
                  on ai_debate_councils(loop_run_id, created_at);

                create table if not exists ai_debate_messages (
                  message_id text primary key,
                  debate_id text not null,
                  loop_run_id text not null,
                  round_index integer not null,
                  agent_role text not null,
                  stance text,
                  provider text,
                  protocol text,
                  model text,
                  status text not null,
                  content_json text not null,
                  error text,
                  created_at text not null
                );

                create index if not exists idx_ai_debate_messages_debate
                  on ai_debate_messages(debate_id, round_index, agent_role);

                create table if not exists ai_trade_decisions (
                  decision_id text primary key,
                  loop_run_id text not null,
                  debate_id text,
                  source_report_id text,
                  candidate_id text,
                  provider text,
                  market_type text,
                  symbol text not null,
                  normalized_symbol text,
                  action text not null,
                  status text not null,
                  confidence real not null,
                  score real not null,
                  horizon text,
                  entry_reference real,
                  target_price real,
                  invalidation_price real,
                  position_sizing_json text not null,
                  rationale_json text not null,
                  risk_warnings_json text not null,
                  evidence_refs_json text not null,
                  policy_json text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_ai_trade_decisions_loop
                  on ai_trade_decisions(loop_run_id, score, created_at);

                create table if not exists recommendation_evaluation_runs (
                  evaluation_run_id text primary key,
                  loop_run_id text,
                  status text not null,
                  config_json text not null,
                  summary_json text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_recommendation_evaluation_runs_created
                  on recommendation_evaluation_runs(created_at);

                create table if not exists recommendation_outcomes (
                  outcome_id text primary key,
                  evaluation_run_id text not null,
                  decision_id text not null,
                  loop_run_id text not null,
                  horizon_hours real not null,
                  status text not null,
                  action text not null,
                  confidence real not null,
                  provider text,
                  market_type text,
                  symbol text not null,
                  normalized_symbol text,
                  decision_at text not null,
                  horizon_at text not null,
                  evaluated_at text not null,
                  entry_price real,
                  entry_source text,
                  exit_price real,
                  exit_at text,
                  raw_return_pct real,
                  directional_return_pct real,
                  mfe_pct real,
                  mae_pct real,
                  hit integer,
                  target_hit integer,
                  invalidation_hit integer,
                  ai_site_id text,
                  ai_model text,
                  prompt_version text,
                  experiment_id text,
                  variant_id text,
                  payload_json text not null,
                  unique(decision_id, horizon_hours)
                );

                create index if not exists idx_recommendation_outcomes_status
                  on recommendation_outcomes(status, horizon_at, evaluated_at);

                create index if not exists idx_recommendation_outcomes_model
                  on recommendation_outcomes(ai_site_id, ai_model, prompt_version, variant_id);

                create table if not exists portfolio_risk_reports (
                  risk_report_id text primary key,
                  loop_run_id text not null,
                  status text not null,
                  config_json text not null,
                  summary_json text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_portfolio_risk_reports_loop
                  on portfolio_risk_reports(loop_run_id, created_at);

                create table if not exists ai_prompt_versions (
                  prompt_version text primary key,
                  task_id text not null,
                  role_id text,
                  system_prompt text not null,
                  user_prompt_template text not null,
                  created_at text not null
                );

                create index if not exists idx_ai_prompt_versions_task
                  on ai_prompt_versions(task_id, role_id, created_at);

                create table if not exists ai_invocations (
                  invocation_id text primary key,
                  loop_run_id text,
                  debate_id text,
                  message_id text,
                  task_id text not null,
                  role_id text,
                  site_id text,
                  protocol text,
                  model text,
                  prompt_version text,
                  input_hash text,
                  experiment_id text,
                  variant_id text,
                  status text not null,
                  input_tokens integer,
                  output_tokens integer,
                  total_tokens integer,
                  estimated_cost_usd real,
                  cost_status text not null,
                  duration_ms integer,
                  error text,
                  created_at text not null
                );

                create index if not exists idx_ai_invocations_loop
                  on ai_invocations(loop_run_id, created_at);

                create index if not exists idx_ai_invocations_variant
                  on ai_invocations(task_id, site_id, model, prompt_version, experiment_id, variant_id);

                create table if not exists claim_evidence_audits (
                  audit_id text primary key,
                  loop_run_id text,
                  debate_id text not null,
                  message_id text not null,
                  role_id text,
                  phase_id text,
                  claim_id text not null,
                  claim_source text not null,
                  claim_text text,
                  covered integer not null,
                  derived integer not null,
                  evidence_refs_json text not null,
                  created_at text not null
                );

                create index if not exists idx_claim_evidence_audits_debate
                  on claim_evidence_audits(debate_id, message_id, covered);

                create table if not exists ai_governance_reports (
                  governance_report_id text primary key,
                  loop_run_id text not null,
                  status text not null,
                  config_json text not null,
                  summary_json text not null,
                  payload_json text not null,
                  created_at text not null
                );

                create index if not exists idx_ai_governance_reports_loop
                  on ai_governance_reports(loop_run_id, created_at);

                create table if not exists decision_reviews (
                  review_id text primary key,
                  decision_id text not null unique,
                  loop_run_id text not null,
                  status text not null,
                  reviewer_id text not null,
                  note text not null,
                  version integer not null,
                  metadata_json text not null,
                  created_at text not null,
                  updated_at text not null,
                  reviewed_at text
                );

                create index if not exists idx_decision_reviews_inbox
                  on decision_reviews(status, updated_at desc);

                create index if not exists idx_decision_reviews_loop
                  on decision_reviews(loop_run_id, updated_at desc);

                create table if not exists workflow_versions (
                  workflow_version_id text primary key,
                  template_id text not null,
                  version_number integer not null,
                  status text not null,
                  content_json text not null,
                  checksum text not null,
                  parent_version_id text,
                  change_note text not null,
                  created_by text not null,
                  created_at text not null,
                  published_at text,
                  unique(template_id, version_number)
                );

                create index if not exists idx_workflow_versions_template
                  on workflow_versions(template_id, version_number desc);

                create unique index if not exists idx_workflow_versions_published
                  on workflow_versions(template_id) where status = 'published';

                create table if not exists workflow_node_test_runs (
                  node_test_id text primary key,
                  template_id text not null,
                  workflow_version_id text,
                  node_id text not null,
                  status text not null,
                  input_json text not null,
                  output_json text not null,
                  estimated_tokens integer,
                  estimated_cost_usd real,
                  cost_status text not null,
                  error text,
                  created_at text not null,
                  finished_at text
                );

                create index if not exists idx_workflow_node_tests
                  on workflow_node_test_runs(template_id, node_id, created_at desc);

                create table if not exists workflow_runs (
                  workflow_run_id text primary key,
                  template_id text not null,
                  workflow_version_id text,
                  trigger_type text not null,
                  mode text not null,
                  status text not null,
                  depth text not null,
                  cost_tier text not null,
                  request_json text not null,
                  template_snapshot_json text not null,
                  plan_json text not null,
                  result_json text not null,
                  error text,
                  version integer not null,
                  created_at text not null,
                  started_at text,
                  updated_at text not null,
                  finished_at text
                );

                create index if not exists idx_workflow_runs_status
                  on workflow_runs(status, updated_at desc);

                create index if not exists idx_workflow_runs_template
                  on workflow_runs(template_id, created_at desc);

                create table if not exists workflow_ledgers (
                  ledger_id text primary key,
                  workflow_run_id text not null,
                  ledger_type text not null,
                  revision integer not null,
                  payload_json text not null,
                  created_at text not null,
                  unique(workflow_run_id, ledger_type, revision)
                );

                create index if not exists idx_workflow_ledgers_run
                  on workflow_ledgers(workflow_run_id, ledger_type, revision);

                create table if not exists workflow_node_checkpoints (
                  checkpoint_id text primary key,
                  workflow_run_id text not null,
                  node_id text not null,
                  phase_id text not null,
                  iteration integer not null,
                  node_type text not null,
                  operation text,
                  status text not null,
                  attempt integer not null,
                  output_json text not null,
                  error text,
                  created_at text not null,
                  updated_at text not null,
                  completed_at text,
                  unique(workflow_run_id, node_id, phase_id, iteration)
                );

                create index if not exists idx_workflow_checkpoints_run
                  on workflow_node_checkpoints(workflow_run_id, node_id, iteration desc);

                create table if not exists council_role_scores (
                  score_id text primary key,
                  debate_id text not null,
                  template_id text not null,
                  role_id text not null,
                  score real not null,
                  sample_weight real not null,
                  components_json text not null,
                  source_refs_json text not null,
                  created_at text not null,
                  unique(debate_id, role_id)
                );

                create index if not exists idx_council_role_scores_role
                  on council_role_scores(template_id, role_id, created_at desc);

                create table if not exists council_memories (
                  memory_id text primary key,
                  template_id text not null,
                  role_id text,
                  product_id text,
                  symbol text,
                  market_type text,
                  topic text,
                  memory_type text not null,
                  content_json text not null,
                  source_refs_json text not null,
                  quality_score real not null,
                  status text not null,
                  created_at text not null,
                  expires_at text,
                  last_used_at text,
                  use_count integer not null
                );

                create index if not exists idx_council_memories_lookup
                  on council_memories(status, template_id, role_id, symbol, market_type, topic, quality_score desc);

                create table if not exists run_replays (
                  replay_id text primary key,
                  source_loop_run_id text not null,
                  source_pipeline_run_id text,
                  request_id text,
                  mode text not null,
                  status text not null,
                  config_json text not null,
                  created_at text not null,
                  updated_at text not null
                );

                create index if not exists idx_run_replays_source
                  on run_replays(source_loop_run_id, created_at desc);

                create table if not exists shadow_positions (
                  position_id text primary key,
                  decision_id text not null unique,
                  review_id text not null,
                  loop_run_id text not null,
                  provider text,
                  market_type text,
                  symbol text not null,
                  normalized_symbol text,
                  side text not null,
                  status text not null,
                  quantity real not null,
                  notional_usdt real not null,
                  entry_price real not null,
                  current_price real,
                  exit_price real,
                  unrealized_pnl_usdt real,
                  realized_pnl_usdt real,
                  opened_at text not null,
                  marked_at text,
                  closed_at text,
                  metadata_json text not null
                );

                create index if not exists idx_shadow_positions_status
                  on shadow_positions(status, opened_at desc);

                create table if not exists shadow_portfolio_snapshots (
                  snapshot_id text primary key,
                  status text not null,
                  source_loop_run_id text,
                  equity_usdt real,
                  cash_usdt real,
                  gross_exposure_usdt real,
                  realized_pnl_usdt real,
                  unrealized_pnl_usdt real,
                  peak_equity_usdt real,
                  drawdown_pct real,
                  positions_json text not null,
                  metrics_json text not null,
                  created_at text not null
                );

                create index if not exists idx_shadow_snapshots_created
                  on shadow_portfolio_snapshots(created_at desc);

                create table if not exists notification_events (
                  notification_id text primary key,
                  category text not null,
                  severity text not null,
                  title text not null,
                  body text not null,
                  status text not null,
                  entity_type text,
                  entity_id text,
                  dedupe_key text unique,
                  payload_json text not null,
                  created_at text not null,
                  read_at text,
                  dismissed_at text
                );

                create index if not exists idx_notification_inbox
                  on notification_events(status, created_at desc);
                """
                )
                self._ensure_column(conn, "fetch_jobs", "max_results", "integer")
                self._ensure_column(conn, "fetch_jobs", "max_scrape_targets", "integer")
                self._ensure_pipeline_lineage_columns(conn)
                self._ensure_council_columns(conn)
                self._ensure_p1_columns(conn)
            _SCHEMA_READY_PATHS.add(schema_path)

    def _ensure_column(self, conn: sqlite3.Connection, table: str, column: str, definition: str) -> None:
        columns = {row["name"] for row in conn.execute(f"pragma table_info({table})").fetchall()}
        if column not in columns:
            conn.execute(f"alter table {table} add column {column} {definition}")

    def _ensure_pipeline_lineage_columns(self, conn: sqlite3.Connection) -> None:
        for table in PIPELINE_LINEAGE_TABLES:
            self._ensure_column(conn, table, "pipeline_run_id", "text")
            conn.execute(f"create index if not exists idx_{table}_pipeline_run on {table}(pipeline_run_id, created_at)")

    def _ensure_council_columns(self, conn: sqlite3.Connection) -> None:
        self._ensure_column(conn, "ai_debate_councils", "template_id", "text")
        self._ensure_column(conn, "ai_debate_councils", "round_summaries_json", "text")
        self._ensure_column(conn, "ai_debate_messages", "phase_id", "text")
        self._ensure_column(conn, "ai_debate_messages", "message_type", "text")
        self._ensure_column(conn, "ai_debate_messages", "turn_index", "integer")
        self._ensure_column(conn, "ai_debate_messages", "reply_to_json", "text")
        self._ensure_column(conn, "ai_debate_messages", "usage_json", "text")
        self._ensure_column(conn, "ai_debate_messages", "duration_ms", "integer")
        self._ensure_column(conn, "ai_debate_messages", "prompt_hash", "text")
        conn.execute(
            "create index if not exists idx_ai_debate_messages_round "
            "on ai_debate_messages(debate_id, round_index, turn_index)"
        )

    def _ensure_p1_columns(self, conn: sqlite3.Connection) -> None:
        for column, definition in (
            ("prompt_version", "text"),
            ("input_hash", "text"),
            ("experiment_id", "text"),
            ("variant_id", "text"),
            ("estimated_cost_usd", "real"),
            ("cost_status", "text"),
        ):
            self._ensure_column(conn, "ai_debate_messages", column, definition)
        for column in ("ai_site_id", "ai_model", "prompt_version", "experiment_id", "variant_id"):
            self._ensure_column(conn, "ai_trade_decisions", column, "text")

    def upsert_source(self, source: SourceConfig) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into sources (
                  id, enabled, tier, category, mode, provider, trust_weight,
                  priority, asset_scope_json, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                  enabled=excluded.enabled,
                  tier=excluded.tier,
                  category=excluded.category,
                  mode=excluded.mode,
                  provider=excluded.provider,
                  trust_weight=excluded.trust_weight,
                  priority=excluded.priority,
                  asset_scope_json=excluded.asset_scope_json,
                  updated_at=excluded.updated_at
                """,
                (
                    source.id,
                    int(source.enabled),
                    source.tier,
                    source.category,
                    source.mode,
                    source.provider,
                    source.trust_weight,
                    source.priority,
                    json.dumps(source.asset_scope, ensure_ascii=False),
                    datetime.now(timezone.utc).isoformat(),
                ),
            )

    def prune_catalog_sources(self, valid_source_ids: set[str]) -> None:
        if not valid_source_ids:
            return
        placeholders = ",".join("?" for _ in valid_source_ids)
        params = tuple(sorted(valid_source_ids))
        with self.connect() as conn:
            conn.execute(f"delete from sources where id not in ({placeholders})", params)
            conn.execute(f"delete from source_health where source_id not in ({placeholders})", params)
            conn.execute(f"delete from fetch_jobs where source_id not in ({placeholders})", params)
            conn.execute(f"delete from fetch_runs where source_id not in ({placeholders})", params)
            conn.execute(f"delete from raw_evidence where source_id not in ({placeholders})", params)
            conn.execute(f"delete from url_candidates where source_id not in ({placeholders})", params)
            conn.execute(f"delete from normalized_documents where source_id not in ({placeholders})", params)
            conn.execute(f"delete from dedupe_keys where source_id not in ({placeholders})", params)

    def insert_evidence(self, evidence: RawEvidence) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into raw_evidence (
                  evidence_id, source_id, job_id, fetched_at, url, query, status_code,
                  success, request_path, response_path, headers_path, markdown_path,
                  error, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    evidence.evidence_id,
                    evidence.source_id,
                    evidence.job_id,
                    evidence.fetched_at.isoformat(),
                    evidence.url,
                    evidence.query,
                    evidence.status_code,
                    int(evidence.success),
                    evidence.request_path,
                    evidence.response_path,
                    evidence.headers_path,
                    evidence.markdown_path,
                    evidence.error,
                    json.dumps(evidence.metadata, ensure_ascii=False, default=str),
                ),
            )

    def upsert_fetch_job(self, job: FetchJob, status: str | None = None, detail: str | None = None) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into fetch_jobs (
                  job_id, source_id, mode, priority, job_type, url, query, provider,
                  asset_scope_json, scheduled_at, max_results, max_scrape_targets,
                  status, detail, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(job_id) do update set
                  source_id=excluded.source_id,
                  mode=excluded.mode,
                  priority=excluded.priority,
                  job_type=excluded.job_type,
                  url=excluded.url,
                  query=excluded.query,
                  provider=excluded.provider,
                  asset_scope_json=excluded.asset_scope_json,
                  scheduled_at=excluded.scheduled_at,
                  max_results=excluded.max_results,
                  max_scrape_targets=excluded.max_scrape_targets,
                  status=excluded.status,
                  detail=excluded.detail,
                  updated_at=excluded.updated_at
                """,
                (
                    job.job_id,
                    job.source_id,
                    job.mode,
                    job.priority,
                    job.job_type,
                    job.url,
                    job.query,
                    job.provider,
                    json.dumps(job.asset_scope, ensure_ascii=False),
                    job.scheduled_at.isoformat(),
                    job.max_results,
                    job.max_scrape_targets,
                    status,
                    detail,
                    datetime.now(timezone.utc).isoformat(),
                ),
            )

    def insert_fetch_run(self, job: FetchJob, result: AdapterResult) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into fetch_runs (
                  job_id, source_id, status, detail, success, evidence_id,
                  required_keys_json, metadata_json, ran_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    job.job_id,
                    result.source_id,
                    result.status,
                    result.detail,
                    int(result.success),
                    result.evidence.evidence_id if result.evidence else None,
                    json.dumps(result.required_keys, ensure_ascii=False),
                    json.dumps(result.metadata, ensure_ascii=False, default=str),
                    datetime.now(timezone.utc).isoformat(),
                ),
            )
        self.upsert_fetch_job(job, status=result.status, detail=result.detail)

    def upsert_health(self, result: AdapterResult) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into source_health (
                  source_id, status, detail, success, required_keys_json,
                  metadata_json, checked_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict(source_id) do update set
                  status=excluded.status,
                  detail=excluded.detail,
                  success=excluded.success,
                  required_keys_json=excluded.required_keys_json,
                  metadata_json=excluded.metadata_json,
                  checked_at=excluded.checked_at
                """,
                (
                    result.source_id,
                    result.status,
                    result.detail,
                    int(result.success),
                    json.dumps(result.required_keys, ensure_ascii=False),
                    json.dumps(result.metadata, ensure_ascii=False, default=str),
                    datetime.now(timezone.utc).isoformat(),
                ),
            )

    def list_raw_evidence(self, only_success: bool = True, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from raw_evidence"
        params: list[Any] = []
        if only_success:
            query += " where success = 1"
        query += " order by fetched_at desc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def list_fetch_jobs_by_status(self, status: str, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from fetch_jobs where status = ? order by scheduled_at, updated_at, job_id"
        params: list[Any] = [status]
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def delete_fetch_jobs_by_status(self, status: str) -> int:
        with self.connect() as conn:
            cursor = conn.execute("delete from fetch_jobs where status = ?", (status,))
            return cursor.rowcount

    def clear_derived_data(self, include_research_packages: bool = False) -> None:
        with self.connect() as conn:
            conn.execute("delete from url_candidates")
            conn.execute("delete from normalized_documents")
            conn.execute("delete from dedupe_keys")
            conn.execute("delete from event_candidates")
            if include_research_packages:
                conn.execute("delete from research_packages")

    def source_map(self) -> dict[str, sqlite3.Row]:
        with self.connect() as conn:
            rows = conn.execute("select * from sources").fetchall()
        return {row["id"]: row for row in rows}

    def upsert_url_candidate(self, candidate: URLCandidate) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into url_candidates (
                  candidate_id, source_id, evidence_id, url, canonical_url, title,
                  snippet, score, metadata_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    candidate.candidate_id,
                    candidate.source_id,
                    candidate.evidence_id,
                    candidate.url,
                    candidate.canonical_url,
                    candidate.title,
                    candidate.snippet,
                    candidate.score,
                    json.dumps(candidate.metadata, ensure_ascii=False, default=str),
                    datetime.now(timezone.utc).isoformat(),
                ),
            )

    def upsert_normalized_document(self, document: NormalizedDocument) -> bool:
        with self.connect() as conn:
            duplicate = conn.execute(
                "select document_id from dedupe_keys where key_type = ? and key_value = ?",
                ("content_hash", document.content_hash),
            ).fetchone()
            if duplicate:
                return False
            conn.execute(
                """
                insert or replace into normalized_documents (
                  document_id, evidence_id, source_id, tier, category, trust_weight,
                  canonical_url, title, published_at, fetched_at, language, text,
                  content_hash, title_key, asset_scope_json, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    document.document_id,
                    document.evidence_id,
                    document.source_id,
                    document.tier,
                    document.category,
                    document.trust_weight,
                    document.canonical_url,
                    document.title,
                    document.published_at.isoformat() if document.published_at else None,
                    document.fetched_at.isoformat(),
                    document.language,
                    document.text,
                    document.content_hash,
                    document.title_key,
                    json.dumps(document.asset_scope, ensure_ascii=False),
                    json.dumps(document.metadata, ensure_ascii=False, default=str),
                ),
            )
            self._insert_dedupe_key(conn, "content_hash", document.content_hash, document)
            if document.canonical_url:
                self._insert_dedupe_key(conn, "canonical_url", document.canonical_url, document)
            if document.title_key:
                self._insert_dedupe_key(conn, "title_key", document.title_key, document)
            return True

    def _insert_dedupe_key(self, conn: sqlite3.Connection, key_type: str, key_value: str, document: NormalizedDocument) -> None:
        conn.execute(
            """
            insert or ignore into dedupe_keys (
              key_type, key_value, document_id, source_id, created_at
            )
            values (?, ?, ?, ?, ?)
            """,
            (
                key_type,
                key_value,
                document.document_id,
                document.source_id,
                datetime.now(timezone.utc).isoformat(),
            ),
        )

    def list_normalized_documents(self, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from normalized_documents order by fetched_at desc"
        params: list[Any] = []
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def upsert_event_candidate(self, event: EventCandidate) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into event_candidates (
                  event_id, event_key, title, category, asset_scope_json,
                  document_ids_json, source_ids_json, confidence, first_seen_at,
                  last_seen_at, summary, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event.event_id,
                    event.event_key,
                    event.title,
                    event.category,
                    json.dumps(event.asset_scope, ensure_ascii=False),
                    json.dumps(event.document_ids, ensure_ascii=False),
                    json.dumps(event.source_ids, ensure_ascii=False),
                    event.confidence,
                    event.first_seen_at.isoformat(),
                    event.last_seen_at.isoformat(),
                    event.summary,
                    json.dumps(event.metadata, ensure_ascii=False, default=str),
                ),
            )

    def list_event_candidates(self, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from event_candidates order by last_seen_at desc, confidence desc"
        params: list[Any] = []
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_research_package(self, package_id: str, time_window: str, payload: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_packages (
                  package_id, generated_at, time_window, payload_json
                )
                values (?, ?, ?, ?)
                """,
                (
                    package_id,
                    datetime.now(timezone.utc).isoformat(),
                    time_window,
                    json.dumps(payload, ensure_ascii=False, default=str),
                ),
            )

    def upsert_official_release(self, release: OfficialRelease) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into official_release_calendar (
                  release_id, provider, release_type, title, scheduled_at, timezone,
                  asset_scope_json, expected_fields_json, source_url, status,
                  metadata_json, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(release_id) do update set
                  provider=excluded.provider,
                  release_type=excluded.release_type,
                  title=excluded.title,
                  scheduled_at=excluded.scheduled_at,
                  timezone=excluded.timezone,
                  asset_scope_json=excluded.asset_scope_json,
                  expected_fields_json=excluded.expected_fields_json,
                  source_url=excluded.source_url,
                  status=excluded.status,
                  metadata_json=excluded.metadata_json,
                  updated_at=excluded.updated_at
                """,
                (
                    release.release_id,
                    release.provider,
                    release.release_type,
                    release.title,
                    release.scheduled_at.isoformat(),
                    release.timezone,
                    json.dumps(release.asset_scope, ensure_ascii=False),
                    json.dumps(release.expected_fields, ensure_ascii=False),
                    release.source_url,
                    release.status,
                    json.dumps({**release.metadata, "match_terms": release.match_terms}, ensure_ascii=False, default=str),
                    datetime.now(timezone.utc).isoformat(),
                ),
            )

    def update_official_release_status(self, release_id: str, status: str, metadata: dict[str, Any] | None = None) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                update official_release_calendar
                set status = ?,
                    metadata_json = ?,
                    updated_at = ?
                where release_id = ?
                """,
                (
                    status,
                    json.dumps(metadata or {}, ensure_ascii=False, default=str),
                    datetime.now(timezone.utc).isoformat(),
                    release_id,
                ),
            )

    def list_official_releases(self) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute("select * from official_release_calendar order by scheduled_at, release_id"))

    def clear_market_context_snapshots(self) -> None:
        with self.connect() as conn:
            conn.execute("delete from market_context_snapshots")

    def insert_market_context_snapshot(self, snapshot: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into market_context_snapshots (
                  snapshot_id, event_id, event_key, asset_scope_json, provider,
                  captured_at, status, market_document_ids_json,
                  market_source_ids_json, price_change_pct, volume_change_pct,
                  volatility_proxy, note, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    snapshot["snapshot_id"],
                    snapshot["event_id"],
                    snapshot["event_key"],
                    json.dumps(snapshot.get("asset_scope", []), ensure_ascii=False),
                    snapshot["provider"],
                    snapshot["captured_at"],
                    snapshot["status"],
                    json.dumps(snapshot.get("market_document_ids", []), ensure_ascii=False),
                    json.dumps(snapshot.get("market_source_ids", []), ensure_ascii=False),
                    snapshot.get("price_change_pct"),
                    snapshot.get("volume_change_pct"),
                    snapshot.get("volatility_proxy"),
                    snapshot.get("note"),
                    json.dumps(snapshot, ensure_ascii=False, default=str),
                ),
            )

    def list_market_context_snapshots(self) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute("select * from market_context_snapshots order by captured_at desc, event_id"))

    def insert_market_quote(self, quote: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into market_quotes (
                  quote_id, provider, market_type, symbol, normalized_symbol,
                  captured_at, last_price, bid, ask, price_change_pct_24h,
                  high_24h, low_24h, volume_24h, turnover_24h, source_url,
                  payload_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    quote["quote_id"],
                    quote["provider"],
                    quote["market_type"],
                    quote["symbol"],
                    quote["normalized_symbol"],
                    quote["captured_at"],
                    quote.get("last_price"),
                    quote.get("bid"),
                    quote.get("ask"),
                    quote.get("price_change_pct_24h"),
                    quote.get("high_24h"),
                    quote.get("low_24h"),
                    quote.get("volume_24h"),
                    quote.get("turnover_24h"),
                    quote.get("source_url"),
                    json.dumps(quote, ensure_ascii=False, default=str),
                ),
            )

    def insert_market_candle(self, candle: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into market_candles (
                  candle_id, provider, market_type, symbol, normalized_symbol,
                  interval, open_time, captured_at, open, high, low, close,
                  volume, turnover, payload_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    candle["candle_id"],
                    candle["provider"],
                    candle["market_type"],
                    candle["symbol"],
                    candle["normalized_symbol"],
                    candle["interval"],
                    candle["open_time"],
                    candle["captured_at"],
                    candle.get("open"),
                    candle.get("high"),
                    candle.get("low"),
                    candle.get("close"),
                    candle.get("volume"),
                    candle.get("turnover"),
                    json.dumps(candle, ensure_ascii=False, default=str),
                ),
            )

    def sync_instrument_catalog(
        self,
        provider: str,
        market_type: str,
        instruments: list[dict[str, Any]],
        captured_at: str,
    ) -> dict[str, int]:
        with self.connect() as conn:
            conn.execute(
                "update venue_instruments set active = 0, captured_at = ? where provider = ? and market_type = ?",
                (captured_at, provider, market_type),
            )
            for instrument in instruments:
                product = instrument["canonical_product"]
                conn.execute(
                    """
                    insert into canonical_products (
                      product_id, asset_class, product_type, base_asset, quote_asset,
                      display_name, status, metadata_json, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict(product_id) do update set
                      display_name=excluded.display_name,
                      status=excluded.status,
                      metadata_json=excluded.metadata_json,
                      updated_at=excluded.updated_at
                    """,
                    (
                        product["product_id"],
                        product["asset_class"],
                        product["product_type"],
                        product["base_asset"],
                        product.get("quote_asset"),
                        product["display_name"],
                        product.get("status", "active"),
                        json.dumps(product.get("metadata", {}), ensure_ascii=False, default=str),
                        captured_at,
                    ),
                )
                conn.execute(
                    """
                    insert into venue_instruments (
                      instrument_id, product_id, provider, market_type, symbol,
                      normalized_symbol, base_asset, quote_asset, settle_asset,
                      active, contract, linear, inverse, contract_size, expiry,
                      tick_size, amount_step, min_amount, min_notional,
                      leverage_json, source_url, captured_at, payload_json
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict(instrument_id) do update set
                      product_id=excluded.product_id,
                      symbol=excluded.symbol,
                      normalized_symbol=excluded.normalized_symbol,
                      base_asset=excluded.base_asset,
                      quote_asset=excluded.quote_asset,
                      settle_asset=excluded.settle_asset,
                      active=excluded.active,
                      contract=excluded.contract,
                      linear=excluded.linear,
                      inverse=excluded.inverse,
                      contract_size=excluded.contract_size,
                      expiry=excluded.expiry,
                      tick_size=excluded.tick_size,
                      amount_step=excluded.amount_step,
                      min_amount=excluded.min_amount,
                      min_notional=excluded.min_notional,
                      leverage_json=excluded.leverage_json,
                      source_url=excluded.source_url,
                      captured_at=excluded.captured_at,
                      payload_json=excluded.payload_json
                    """,
                    (
                        instrument["instrument_id"],
                        product["product_id"],
                        instrument["provider"],
                        instrument["market_type"],
                        instrument["symbol"],
                        instrument["normalized_symbol"],
                        instrument["base_asset"],
                        instrument.get("quote_asset"),
                        instrument.get("settle_asset"),
                        1 if instrument.get("active", True) else 0,
                        1 if instrument.get("contract", False) else 0,
                        _sqlite_bool(instrument.get("linear")),
                        _sqlite_bool(instrument.get("inverse")),
                        instrument.get("contract_size"),
                        instrument.get("expiry"),
                        instrument.get("tick_size"),
                        instrument.get("amount_step"),
                        instrument.get("min_amount"),
                        instrument.get("min_notional"),
                        json.dumps(instrument.get("leverage", {}), ensure_ascii=False, default=str),
                        instrument.get("source_url"),
                        captured_at,
                        json.dumps(instrument, ensure_ascii=False, default=str),
                    ),
                )
                conn.execute("delete from instrument_aliases where instrument_id = ?", (instrument["instrument_id"],))
                for alias in instrument.get("aliases", []):
                    conn.execute(
                        """
                        insert or replace into instrument_aliases (
                          alias_key, provider, market_type, instrument_id,
                          alias_type, priority, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            alias["alias_key"],
                            instrument["provider"],
                            instrument["market_type"],
                            instrument["instrument_id"],
                            alias["alias_type"],
                            int(alias.get("priority", 0)),
                            captured_at,
                        ),
                    )
                snapshot = instrument.get("market_snapshot")
                if isinstance(snapshot, dict):
                    conn.execute(
                        """
                        insert or replace into instrument_market_snapshots (
                          snapshot_id, instrument_id, provider, market_type, symbol,
                          captured_at, last_price, bid, ask, volume_24h,
                          turnover_24h, price_change_pct_24h, payload_json
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        (
                            snapshot["snapshot_id"],
                            instrument["instrument_id"],
                            instrument["provider"],
                            instrument["market_type"],
                            instrument["symbol"],
                            captured_at,
                            snapshot.get("last_price"),
                            snapshot.get("bid"),
                            snapshot.get("ask"),
                            snapshot.get("volume_24h"),
                            snapshot.get("turnover_24h"),
                            snapshot.get("price_change_pct_24h"),
                            json.dumps(snapshot, ensure_ascii=False, default=str),
                        ),
                    )
        return {
            "instrument_count": len(instruments),
            "active_count": sum(1 for instrument in instruments if instrument.get("active", True)),
        }

    def list_venue_instruments(
        self,
        active_only: bool = True,
        providers: tuple[str, ...] = (),
        market_types: tuple[str, ...] = (),
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        if active_only:
            clauses.append("vi.active = 1")
        if providers:
            clauses.append(f"vi.provider in ({','.join('?' for _ in providers)})")
            params.extend(providers)
        if market_types:
            clauses.append(f"vi.market_type in ({','.join('?' for _ in market_types)})")
            params.extend(market_types)
        where_sql = f"where {' and '.join(clauses)}" if clauses else ""
        query = f"""
            select vi.*,
                   cp.asset_class,
                   cp.product_type,
                   cp.display_name,
                   ims.last_price,
                   ims.bid,
                   ims.ask,
                   ims.volume_24h,
                   ims.turnover_24h,
                   ims.price_change_pct_24h,
                   ims.captured_at as snapshot_captured_at
            from venue_instruments vi
            join canonical_products cp on cp.product_id = vi.product_id
            left join instrument_market_snapshots ims on ims.snapshot_id = (
              select latest.snapshot_id
              from instrument_market_snapshots latest
              where latest.instrument_id = vi.instrument_id
              order by latest.captured_at desc
              limit 1
            )
            {where_sql}
            order by coalesce(ims.turnover_24h, 0) desc, vi.provider, vi.market_type, vi.symbol
        """
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def list_catalog_instruments_page(
        self,
        *,
        watchlist_id: str,
        search: str | None = None,
        provider: str | None = None,
        market_type: str | None = None,
        product_type: str | None = None,
        active_only: bool = True,
        watched_only: bool = False,
        limit: int = 25,
        offset: int = 0,
    ) -> tuple[list[sqlite3.Row], int]:
        clauses: list[str] = []
        filter_params: list[Any] = []
        if active_only:
            clauses.append("vi.active = 1")
        if provider:
            clauses.append("vi.provider = ?")
            filter_params.append(provider)
        if market_type:
            clauses.append("vi.market_type = ?")
            filter_params.append(market_type)
        if product_type:
            clauses.append("cp.product_type = ?")
            filter_params.append(product_type)
        if search:
            pattern = f"%{search.lower()}%"
            clauses.append(
                "(lower(vi.symbol) like ? or lower(vi.normalized_symbol) like ? "
                "or lower(cp.display_name) like ? or lower(vi.base_asset) like ? "
                "or lower(coalesce(vi.quote_asset, '')) like ?)"
            )
            filter_params.extend([pattern] * 5)
        if watched_only:
            clauses.append("wi.watchlist_item_id is not null")
        where_sql = f"where {' and '.join(clauses)}" if clauses else ""
        joins = """
            from venue_instruments vi
            join canonical_products cp on cp.product_id = vi.product_id
            left join watchlist_items wi
              on wi.watchlist_id = ? and wi.product_id = vi.product_id
        """
        params: list[Any] = [watchlist_id, *filter_params]
        count_query = f"select count(*) as total {joins} {where_sql}"
        query = f"""
            select vi.*,
                   cp.asset_class,
                   cp.product_type,
                   cp.display_name,
                   cp.status as product_status,
                   cp.metadata_json as product_metadata_json,
                   ims.last_price,
                   ims.bid,
                   ims.ask,
                   ims.volume_24h,
                   ims.turnover_24h,
                   ims.price_change_pct_24h,
                   ims.captured_at as snapshot_captured_at,
                   wi.watchlist_item_id,
                   wi.watchlist_id,
                   wi.preferred_instrument_id,
                   wi.research_mode,
                   wi.notes as watchlist_notes,
                   wi.tags_json as watchlist_tags_json,
                   wi.alert_policy_json as watchlist_alert_policy_json,
                   wi.sort_order as watchlist_sort_order,
                   wi.updated_at as watchlist_updated_at
            {joins}
            left join instrument_market_snapshots ims on ims.snapshot_id = (
              select latest.snapshot_id
              from instrument_market_snapshots latest
              where latest.instrument_id = vi.instrument_id
              order by latest.captured_at desc
              limit 1
            )
            {where_sql}
            order by case when wi.watchlist_item_id is null then 1 else 0 end,
                     coalesce(ims.turnover_24h, 0) desc,
                     vi.provider, vi.market_type, vi.symbol
            limit ? offset ?
        """
        with self.connect() as conn:
            total = int(conn.execute(count_query, params).fetchone()["total"])
            rows = list(conn.execute(query, [*params, limit, offset]))
        return rows, total

    def get_canonical_product(self, product_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from canonical_products where product_id = ?",
                (product_id,),
            ).fetchone()

    def get_venue_instrument(self, instrument_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from venue_instruments where instrument_id = ?",
                (instrument_id,),
            ).fetchone()

    def list_product_instruments(self, product_id: str, watchlist_id: str) -> list[sqlite3.Row]:
        query = """
            select vi.*,
                   cp.asset_class,
                   cp.product_type,
                   cp.display_name,
                   cp.status as product_status,
                   cp.metadata_json as product_metadata_json,
                   ims.last_price,
                   ims.bid,
                   ims.ask,
                   ims.volume_24h,
                   ims.turnover_24h,
                   ims.price_change_pct_24h,
                   ims.captured_at as snapshot_captured_at,
                   wi.watchlist_item_id,
                   wi.watchlist_id,
                   wi.preferred_instrument_id,
                   wi.research_mode,
                   wi.notes as watchlist_notes,
                   wi.tags_json as watchlist_tags_json,
                   wi.alert_policy_json as watchlist_alert_policy_json,
                   wi.sort_order as watchlist_sort_order,
                   wi.updated_at as watchlist_updated_at
            from venue_instruments vi
            join canonical_products cp on cp.product_id = vi.product_id
            left join watchlist_items wi
              on wi.watchlist_id = ? and wi.product_id = vi.product_id
            left join instrument_market_snapshots ims on ims.snapshot_id = (
              select latest.snapshot_id
              from instrument_market_snapshots latest
              where latest.instrument_id = vi.instrument_id
              order by latest.captured_at desc
              limit 1
            )
            where vi.product_id = ?
            order by vi.active desc, coalesce(ims.turnover_24h, 0) desc,
                     vi.provider, vi.market_type, vi.symbol
        """
        with self.connect() as conn:
            return list(conn.execute(query, (watchlist_id, product_id)))

    def list_watchlists(self, owner_id: str) -> list[sqlite3.Row]:
        query = """
            select wl.*,
                   count(wi.watchlist_item_id) as item_count,
                   sum(case when wi.research_mode = 'monitor' then 1 else 0 end) as monitor_count,
                   sum(case when wi.research_mode = 'research' then 1 else 0 end) as research_count,
                   sum(case when wi.research_mode = 'pinned' then 1 else 0 end) as pinned_count
            from watchlists wl
            left join watchlist_items wi on wi.watchlist_id = wl.watchlist_id
            where wl.owner_id = ?
            group by wl.watchlist_id
            order by wl.is_default desc, lower(wl.name), wl.created_at
        """
        with self.connect() as conn:
            return list(conn.execute(query, (owner_id,)))

    def get_watchlist(self, watchlist_id: str, owner_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from watchlists where watchlist_id = ? and owner_id = ?",
                (watchlist_id, owner_id),
            ).fetchone()

    def get_default_watchlist(self, owner_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from watchlists where owner_id = ? order by is_default desc, created_at limit 1",
                (owner_id,),
            ).fetchone()

    def insert_watchlist(self, watchlist: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into watchlists (
                  watchlist_id, owner_id, name, description, is_default, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    watchlist["watchlist_id"],
                    watchlist["owner_id"],
                    watchlist["name"],
                    watchlist.get("description", ""),
                    1 if watchlist.get("is_default") else 0,
                    watchlist["created_at"],
                    watchlist["updated_at"],
                ),
            )

    def update_watchlist(self, watchlist_id: str, owner_id: str, name: str, description: str, updated_at: str) -> bool:
        with self.connect() as conn:
            cursor = conn.execute(
                """
                update watchlists
                set name = ?, description = ?, updated_at = ?
                where watchlist_id = ? and owner_id = ?
                """,
                (name, description, updated_at, watchlist_id, owner_id),
            )
            return cursor.rowcount > 0

    def delete_watchlist(self, watchlist_id: str, owner_id: str) -> bool:
        with self.connect() as conn:
            row = conn.execute(
                "select watchlist_id from watchlists where watchlist_id = ? and owner_id = ?",
                (watchlist_id, owner_id),
            ).fetchone()
            if row is None:
                return False
            conn.execute("delete from watchlist_items where watchlist_id = ?", (watchlist_id,))
            conn.execute("delete from watchlists where watchlist_id = ?", (watchlist_id,))
            return True

    def list_watchlist_items(self, watchlist_id: str) -> list[sqlite3.Row]:
        query = """
            select wi.*, cp.asset_class, cp.product_type, cp.base_asset,
                   cp.quote_asset, cp.display_name, cp.status as product_status,
                   vi.provider as preferred_provider,
                   vi.market_type as preferred_market_type,
                   vi.symbol as preferred_symbol,
                   vi.active as preferred_active
            from watchlist_items wi
            join canonical_products cp on cp.product_id = wi.product_id
            left join venue_instruments vi on vi.instrument_id = wi.preferred_instrument_id
            where wi.watchlist_id = ?
            order by wi.sort_order, wi.updated_at desc, lower(cp.display_name)
        """
        with self.connect() as conn:
            return list(conn.execute(query, (watchlist_id,)))

    def get_watchlist_item(self, watchlist_id: str, product_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from watchlist_items where watchlist_id = ? and product_id = ?",
                (watchlist_id, product_id),
            ).fetchone()

    def upsert_watchlist_item(self, item: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into watchlist_items (
                  watchlist_item_id, watchlist_id, product_id, preferred_instrument_id,
                  research_mode, notes, tags_json, alert_policy_json, sort_order,
                  created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(watchlist_id, product_id) do update set
                  preferred_instrument_id=excluded.preferred_instrument_id,
                  research_mode=excluded.research_mode,
                  notes=excluded.notes,
                  tags_json=excluded.tags_json,
                  alert_policy_json=excluded.alert_policy_json,
                  sort_order=excluded.sort_order,
                  updated_at=excluded.updated_at
                """,
                (
                    item["watchlist_item_id"],
                    item["watchlist_id"],
                    item["product_id"],
                    item.get("preferred_instrument_id"),
                    item["research_mode"],
                    item.get("notes", ""),
                    json.dumps(item.get("tags", []), ensure_ascii=False, default=str),
                    json.dumps(item.get("alert_policy", {}), ensure_ascii=False, default=str),
                    int(item.get("sort_order") or 0),
                    item["created_at"],
                    item["updated_at"],
                ),
            )

    def delete_watchlist_item(self, watchlist_id: str, product_id: str) -> bool:
        with self.connect() as conn:
            cursor = conn.execute(
                "delete from watchlist_items where watchlist_id = ? and product_id = ?",
                (watchlist_id, product_id),
            )
            return cursor.rowcount > 0

    def list_watchlist_universe_items(self, owner_id: str) -> list[sqlite3.Row]:
        query = """
            select wi.watchlist_item_id,
                   wi.watchlist_id,
                   wi.product_id,
                   wi.research_mode,
                   coalesce(
                     wi.preferred_instrument_id,
                     (
                       select candidate.instrument_id
                       from venue_instruments candidate
                       left join instrument_market_snapshots snapshot on snapshot.snapshot_id = (
                         select latest.snapshot_id
                         from instrument_market_snapshots latest
                         where latest.instrument_id = candidate.instrument_id
                         order by latest.captured_at desc
                         limit 1
                       )
                       where candidate.product_id = wi.product_id and candidate.active = 1
                       order by coalesce(snapshot.turnover_24h, 0) desc,
                                candidate.provider, candidate.market_type, candidate.symbol
                       limit 1
                     )
                   ) as instrument_id
            from watchlist_items wi
            join watchlists wl on wl.watchlist_id = wi.watchlist_id
            where wl.owner_id = ? and wi.research_mode in ('research', 'pinned')
            order by case wi.research_mode when 'pinned' then 0 else 1 end,
                     wi.sort_order, wi.updated_at desc
        """
        with self.connect() as conn:
            return list(conn.execute(query, (owner_id,)))

    def find_instruments_by_alias(
        self,
        alias_key: str,
        providers: tuple[str, ...] = (),
        market_types: tuple[str, ...] = (),
    ) -> list[sqlite3.Row]:
        clauses = ["ia.alias_key = ?", "vi.active = 1"]
        params: list[Any] = [alias_key]
        if providers:
            clauses.append(f"vi.provider in ({','.join('?' for _ in providers)})")
            params.extend(providers)
        if market_types:
            clauses.append(f"vi.market_type in ({','.join('?' for _ in market_types)})")
            params.extend(market_types)
        query = f"""
            select vi.*, ia.alias_type, ia.priority
            from instrument_aliases ia
            join venue_instruments vi on vi.instrument_id = ia.instrument_id
            where {' and '.join(clauses)}
            order by ia.priority desc, vi.provider, vi.market_type, vi.symbol
        """
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_universe_run(self, run: dict[str, Any], items: list[dict[str, Any]]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into universe_runs (
                  universe_run_id, loop_run_id, mode, status, config_json,
                  summary_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run["universe_run_id"],
                    run.get("loop_run_id"),
                    run["mode"],
                    run["status"],
                    json.dumps(run.get("config", {}), ensure_ascii=False, default=str),
                    json.dumps(run.get("summary", {}), ensure_ascii=False, default=str),
                    run["created_at"],
                ),
            )
            conn.execute("delete from universe_items where universe_run_id = ?", (run["universe_run_id"],))
            for rank_index, item in enumerate(items, start=1):
                conn.execute(
                    """
                    insert into universe_items (
                      universe_run_id, instrument_id, rank_index, score,
                      sources_json, reason_json, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        run["universe_run_id"],
                        item["instrument_id"],
                        rank_index,
                        float(item.get("score") or 0.0),
                        json.dumps(item.get("sources", []), ensure_ascii=False, default=str),
                        json.dumps(item.get("reasons", []), ensure_ascii=False, default=str),
                        run["created_at"],
                    ),
                )

    def list_universe_runs(self, limit: int = 20) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute("select * from universe_runs order by created_at desc limit ?", (limit,)))

    def latest_universe(self) -> dict[str, Any] | None:
        with self.connect() as conn:
            run = conn.execute("select * from universe_runs order by created_at desc limit 1").fetchone()
            if run is None:
                return None
            items = conn.execute(
                """
                select ui.rank_index, ui.score, ui.sources_json, ui.reason_json,
                       vi.instrument_id, vi.provider, vi.market_type, vi.symbol,
                       vi.normalized_symbol, vi.base_asset, vi.quote_asset,
                       vi.contract, vi.linear, vi.inverse, vi.contract_size,
                       vi.expiry, vi.tick_size, vi.amount_step, vi.min_amount,
                       vi.min_notional, vi.leverage_json
                from universe_items ui
                join venue_instruments vi on vi.instrument_id = ui.instrument_id
                where ui.universe_run_id = ?
                order by ui.rank_index
                """,
                (run["universe_run_id"],),
            ).fetchall()
        return {
            "universe_run_id": run["universe_run_id"],
            "loop_run_id": run["loop_run_id"],
            "mode": run["mode"],
            "status": run["status"],
            "config": _loads(run["config_json"], {}),
            "summary": _loads(run["summary_json"], {}),
            "created_at": run["created_at"],
            "instruments": [
                {
                    **dict(item),
                    "contract": bool(item["contract"]),
                    "linear": None if item["linear"] is None else bool(item["linear"]),
                    "inverse": None if item["inverse"] is None else bool(item["inverse"]),
                    "sources": _loads(item["sources_json"], []),
                    "reasons": _loads(item["reason_json"], []),
                    "leverage": _loads(item["leverage_json"], {}),
                }
                for item in items
            ],
        }

    def insert_advisory_report(self, report: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into advisory_reports (
                  report_id, profile, status, generated_at, payload_json
                )
                values (?, ?, ?, ?, ?)
                """,
                (
                    report["report_id"],
                    report["profile"],
                    report["status"],
                    report["generated_at"],
                    json.dumps(report, ensure_ascii=False, default=str),
                ),
            )

    def list_advisory_reports(self, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from advisory_reports order by generated_at desc"
        params: list[Any] = []
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_paper_order_proposal(self, proposal: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into paper_order_proposals (
                  proposal_id, report_id, advice_id, provider, market_type,
                  symbol, action, status, execution_mode, created_at, payload_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    proposal["proposal_id"],
                    proposal.get("report_id"),
                    proposal["advice_id"],
                    proposal["provider"],
                    proposal["market_type"],
                    proposal["symbol"],
                    proposal["action"],
                    proposal["status"],
                    proposal["execution_mode"],
                    proposal["created_at"],
                    json.dumps(proposal, ensure_ascii=False, default=str),
                ),
            )

    def list_paper_order_proposals(self, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from paper_order_proposals order by created_at desc"
        params: list[Any] = []
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_paper_execution_run(self, run: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into paper_execution_runs (
                  execution_run_id, loop_run_id, status, config_json,
                  summary_json, created_at, finished_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                on conflict(loop_run_id) do update set
                  status=excluded.status,
                  config_json=excluded.config_json,
                  summary_json=excluded.summary_json,
                  finished_at=excluded.finished_at
                """,
                (
                    run["execution_run_id"],
                    run["loop_run_id"],
                    run["status"],
                    json.dumps(run.get("config", {}), ensure_ascii=False, default=str),
                    json.dumps(run.get("summary", {}), ensure_ascii=False, default=str),
                    run["created_at"],
                    run.get("finished_at"),
                ),
            )

    def get_paper_execution_run(self, loop_run_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from paper_execution_runs where loop_run_id = ?",
                (loop_run_id,),
            ).fetchone()

    def list_paper_execution_runs(self, limit: int = 20) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    "select * from paper_execution_runs order by created_at desc limit ?",
                    (max(1, min(limit, 200)),),
                )
            )

    def insert_paper_execution_if_absent(self, execution: dict[str, Any]) -> bool:
        with self.connect() as conn:
            cursor = conn.execute(
                """
                insert or ignore into paper_executions (
                  execution_id, execution_run_id, loop_run_id, decision_id,
                  adapter_id, provider, environment, product_id, instrument_id,
                  symbol, market_type, action, status, client_order_id,
                  exchange_order_id, requested_notional, requested_quantity,
                  filled_quantity, average_fill_price, request_json, response_json,
                  error, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    execution["execution_id"],
                    execution["execution_run_id"],
                    execution["loop_run_id"],
                    execution["decision_id"],
                    execution["adapter_id"],
                    execution["provider"],
                    execution["environment"],
                    execution.get("product_id"),
                    execution.get("instrument_id"),
                    execution["symbol"],
                    execution["market_type"],
                    execution["action"],
                    execution["status"],
                    execution["client_order_id"],
                    execution.get("exchange_order_id"),
                    execution.get("requested_notional"),
                    execution.get("requested_quantity"),
                    execution.get("filled_quantity"),
                    execution.get("average_fill_price"),
                    json.dumps(execution.get("request", {}), ensure_ascii=False, default=str),
                    json.dumps(execution.get("response", {}), ensure_ascii=False, default=str),
                    execution.get("error"),
                    execution["created_at"],
                    execution["updated_at"],
                ),
            )
            return cursor.rowcount > 0

    def update_paper_execution(
        self,
        execution_id: str,
        *,
        status: str,
        request: dict[str, Any] | None = None,
        response: dict[str, Any] | None = None,
        exchange_order_id: str | None = None,
        requested_notional: float | None = None,
        requested_quantity: float | None = None,
        filled_quantity: float | None = None,
        average_fill_price: float | None = None,
        error: str | None = None,
        updated_at: str,
    ) -> bool:
        with self.connect() as conn:
            current = conn.execute(
                "select request_json, response_json from paper_executions where execution_id = ?",
                (execution_id,),
            ).fetchone()
            if current is None:
                return False
            cursor = conn.execute(
                """
                update paper_executions
                set status = ?, request_json = ?, response_json = ?,
                    exchange_order_id = ?, requested_notional = ?,
                    requested_quantity = ?, filled_quantity = ?,
                    average_fill_price = ?, error = ?, updated_at = ?
                where execution_id = ?
                """,
                (
                    status,
                    json.dumps(request, ensure_ascii=False, default=str) if request is not None else current["request_json"],
                    json.dumps(response, ensure_ascii=False, default=str) if response is not None else current["response_json"],
                    exchange_order_id,
                    requested_notional,
                    requested_quantity,
                    filled_quantity,
                    average_fill_price,
                    error,
                    updated_at,
                    execution_id,
                ),
            )
            return cursor.rowcount > 0

    def get_paper_execution(self, decision_id: str, adapter_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from paper_executions where decision_id = ? and adapter_id = ?",
                (decision_id, adapter_id),
            ).fetchone()

    def list_paper_executions(
        self,
        *,
        loop_run_id: str | None = None,
        adapter_id: str | None = None,
        limit: int = 100,
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        if loop_run_id:
            clauses.append("loop_run_id = ?")
            params.append(loop_run_id)
        if adapter_id:
            clauses.append("adapter_id = ?")
            params.append(adapter_id)
        query = "select * from paper_executions"
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by created_at desc limit ?"
        params.append(max(1, min(limit, 500)))
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_macro_release_facts(self) -> None:
        with self.connect() as conn:
            conn.execute("delete from macro_release_facts")

    def insert_macro_release_fact(self, fact: Any) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into macro_release_facts (
                  fact_id, source_id, evidence_id, provider, release_type,
                  observed_at, fields_json, asset_scope_json, confidence,
                  notes_json, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    fact.fact_id,
                    fact.source_id,
                    fact.evidence_id,
                    fact.provider,
                    fact.release_type,
                    fact.observed_at,
                    json.dumps(fact.fields, ensure_ascii=False, default=str),
                    json.dumps(fact.asset_scope, ensure_ascii=False),
                    fact.confidence,
                    json.dumps(fact.notes, ensure_ascii=False),
                    json.dumps(fact.metadata, ensure_ascii=False, default=str),
                ),
            )

    def list_macro_release_facts(self) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute("select * from macro_release_facts order by observed_at desc, provider, release_type"))

    def clear_source_budget_state(self) -> None:
        with self.connect() as conn:
            conn.execute("delete from source_budget_state")

    def upsert_source_budget_state(self, state: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into source_budget_state (
                  source_id, provider, budget_window, requests_used, credits_used,
                  max_requests, max_credits, throttled_until, last_error, status,
                  metadata_json, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    state["source_id"],
                    state.get("provider"),
                    state["budget_window"],
                    state["requests_used"],
                    state["credits_used"],
                    state.get("max_requests"),
                    state.get("max_credits"),
                    state.get("throttled_until"),
                    state.get("last_error"),
                    state["status"],
                    json.dumps(state.get("metadata", {}), ensure_ascii=False, default=str),
                    datetime.now(timezone.utc).isoformat(),
                ),
            )

    def list_source_budget_state(self) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute("select * from source_budget_state order by status desc, source_id"))

    def get_source_budget_state(self, source_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute("select * from source_budget_state where source_id = ?", (source_id,)).fetchone()

    def mark_source_throttled(
        self,
        source_id: str,
        provider: str | None,
        detail: str,
        minutes: int = 30,
        status: str = "throttled",
    ) -> None:
        now = datetime.now(timezone.utc)
        throttled_until = now + timedelta(minutes=max(1, minutes))
        existing = self.get_source_budget_state(source_id)
        metadata = _loads(existing["metadata_json"], {}) if existing else {}
        metadata["last_throttle_reason"] = detail
        metadata["throttle_minutes"] = max(1, minutes)
        state = {
            "source_id": source_id,
            "provider": provider or (existing["provider"] if existing else None),
            "budget_window": existing["budget_window"] if existing else "runtime-throttle",
            "requests_used": existing["requests_used"] if existing else 0,
            "credits_used": existing["credits_used"] if existing else 0.0,
            "max_requests": existing["max_requests"] if existing else None,
            "max_credits": existing["max_credits"] if existing else None,
            "throttled_until": throttled_until.isoformat(),
            "last_error": detail,
            "status": status,
            "metadata": metadata,
        }
        self.upsert_source_budget_state(state)

    def clear_ai_compressions(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from ai_compressions where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from ai_compressions")

    def insert_ai_compression(self, compression: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into ai_compressions (
                  compression_id, pipeline_run_id, target_type, target_id, provider, protocol,
                  model, status, summary_json, prompt_hash, source_refs_json,
                  error, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    compression["compression_id"],
                    compression.get("pipeline_run_id"),
                    compression["target_type"],
                    compression["target_id"],
                    compression["provider"],
                    compression["protocol"],
                    compression.get("model"),
                    compression["status"],
                    json.dumps(compression.get("summary", {}), ensure_ascii=False, default=str),
                    compression["prompt_hash"],
                    json.dumps(compression.get("source_refs", []), ensure_ascii=False, default=str),
                    compression.get("error"),
                    compression["created_at"],
                ),
            )

    def list_ai_compressions(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from ai_compressions"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc, target_type, target_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_cards(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_cards where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_cards")

    def insert_research_card(self, card: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_cards (
                  card_id, pipeline_run_id, event_id, event_key, readiness, priority,
                  freshness_status, time_window, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    card["card_id"],
                    card.get("pipeline_run_id"),
                    card["event_id"],
                    card["event_key"],
                    card["readiness"],
                    card.get("priority"),
                    card["freshness_status"],
                    card["time_window"],
                    json.dumps(card, ensure_ascii=False, default=str),
                    card["created_at"],
                ),
            )

    def list_research_cards(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_cards"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc, priority, event_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_card_validations(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_card_validations where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_card_validations")

    def insert_research_card_validation(self, validation: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_card_validations (
                  validation_id, pipeline_run_id, card_id, event_id, status, score,
                  findings_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    validation["validation_id"],
                    validation.get("pipeline_run_id"),
                    validation["card_id"],
                    validation["event_id"],
                    validation["status"],
                    validation["score"],
                    json.dumps(validation.get("findings", []), ensure_ascii=False, default=str),
                    validation["created_at"],
                ),
            )

    def list_research_card_validations(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_card_validations"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc, status, card_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_card_decisions(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_card_decisions where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_card_decisions")

    def insert_research_card_decision(self, decision: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_card_decisions (
                  decision_id, pipeline_run_id, card_id, event_id, decision, score,
                  payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    decision["decision_id"],
                    decision.get("pipeline_run_id"),
                    decision["card_id"],
                    decision["event_id"],
                    decision["decision"],
                    decision["score"],
                    json.dumps(decision, ensure_ascii=False, default=str),
                    decision["created_at"],
                ),
            )

    def list_research_card_decisions(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_card_decisions"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc, score desc, card_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_followup_dispatches(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_followup_dispatches where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_followup_dispatches")

    def insert_research_followup_dispatch(self, dispatch: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_followup_dispatches (
                  dispatch_id, pipeline_run_id, decision_id, card_id, event_id, job_id,
                  status, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    dispatch["dispatch_id"],
                    dispatch.get("pipeline_run_id"),
                    dispatch["decision_id"],
                    dispatch["card_id"],
                    dispatch["event_id"],
                    dispatch["job_id"],
                    dispatch["status"],
                    json.dumps(dispatch, ensure_ascii=False, default=str),
                    dispatch["created_at"],
                ),
            )

    def list_research_followup_dispatches(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_followup_dispatches"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc, card_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_watch_items(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_watch_items where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_watch_items")

    def insert_research_watch_item(self, item: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_watch_items (
                  watch_item_id, pipeline_run_id, card_id, event_id, decision, priority, score,
                  status, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    item["watch_item_id"],
                    item.get("pipeline_run_id"),
                    item["card_id"],
                    item["event_id"],
                    item["decision"],
                    item.get("priority"),
                    item["score"],
                    item["status"],
                    json.dumps(item, ensure_ascii=False, default=str),
                    item["created_at"],
                ),
            )

    def list_research_watch_items(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_watch_items"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by status, priority, score desc, created_at desc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_briefs(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_briefs where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_briefs")

    def insert_research_brief(self, brief: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_briefs (
                  brief_id, pipeline_run_id, time_window, payload_json, created_at
                )
                values (?, ?, ?, ?, ?)
                """,
                (
                    brief["brief_id"],
                    brief.get("pipeline_run_id"),
                    brief["time_window"],
                    json.dumps(brief, ensure_ascii=False, default=str),
                    brief["created_at"],
                ),
            )

    def list_research_briefs(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_briefs"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_review_verdicts(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_review_verdicts where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_review_verdicts")

    def insert_research_review_verdict(self, verdict: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_review_verdicts (
                  verdict_id, pipeline_run_id, council_id, watch_item_id, card_id, event_id,
                  role_id, stance, confidence, severity, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    verdict["verdict_id"],
                    verdict.get("pipeline_run_id"),
                    verdict["council_id"],
                    verdict["watch_item_id"],
                    verdict["card_id"],
                    verdict["event_id"],
                    verdict["role_id"],
                    verdict["stance"],
                    verdict["confidence"],
                    verdict["severity"],
                    json.dumps(verdict, ensure_ascii=False, default=str),
                    verdict["created_at"],
                ),
            )

    def list_research_review_verdicts(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_review_verdicts"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc, council_id, watch_item_id, role_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def clear_research_councils(self, pipeline_run_id: str | None = None) -> None:
        with self.connect() as conn:
            if pipeline_run_id:
                conn.execute("delete from research_councils where pipeline_run_id = ?", (pipeline_run_id,))
            else:
                conn.execute("delete from research_councils")

    def insert_research_council(self, council: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_councils (
                  council_id, pipeline_run_id, brief_id, time_window, status, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    council["council_id"],
                    council.get("pipeline_run_id"),
                    council.get("brief_id"),
                    council["time_window"],
                    council["status"],
                    json.dumps(council, ensure_ascii=False, default=str),
                    council["created_at"],
                ),
            )

    def list_research_councils(self, limit: int | None = None, pipeline_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from research_councils"
        params: list[Any] = []
        if pipeline_run_id:
            query += " where pipeline_run_id = ?"
            params.append(pipeline_run_id)
        query += " order by created_at desc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_research_pipeline_run(self, run: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_pipeline_runs (
                  run_id, profile, status, triggered_by, config_json,
                  summary_json, started_at, finished_at, error
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run["run_id"],
                    run["profile"],
                    run["status"],
                    run.get("triggered_by"),
                    json.dumps(run.get("config", {}), ensure_ascii=False, default=str),
                    json.dumps(run.get("summary", {}), ensure_ascii=False, default=str),
                    run["started_at"],
                    run.get("finished_at"),
                    run.get("error"),
                ),
            )

    def update_research_pipeline_run(
        self,
        run_id: str,
        status: str,
        summary: dict[str, Any] | None = None,
        finished_at: str | None = None,
        error: str | None = None,
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                update research_pipeline_runs
                set status = ?,
                    summary_json = ?,
                    finished_at = ?,
                    error = ?
                where run_id = ?
                """,
                (
                    status,
                    json.dumps(summary or {}, ensure_ascii=False, default=str),
                    finished_at,
                    error,
                    run_id,
                ),
            )

    def insert_research_pipeline_step(self, step: dict[str, Any]) -> None:
        now = datetime.now(timezone.utc).isoformat()
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_pipeline_steps (
                  step_id, run_id, step_name, status, attempt, started_at,
                  finished_at, duration_ms, input_json, output_json, error,
                  created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    step["step_id"],
                    step["run_id"],
                    step["step_name"],
                    step["status"],
                    step.get("attempt", 1),
                    step.get("started_at"),
                    step.get("finished_at"),
                    step.get("duration_ms"),
                    json.dumps(step.get("input", {}), ensure_ascii=False, default=str),
                    json.dumps(step.get("output", {}), ensure_ascii=False, default=str),
                    step.get("error"),
                    step.get("created_at") or now,
                    step.get("updated_at") or now,
                ),
            )

    def update_research_pipeline_step(
        self,
        step_id: str,
        status: str,
        output: dict[str, Any] | None = None,
        finished_at: str | None = None,
        duration_ms: int | None = None,
        error: str | None = None,
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                update research_pipeline_steps
                set status = ?,
                    output_json = ?,
                    finished_at = ?,
                    duration_ms = ?,
                    error = ?,
                    updated_at = ?
                where step_id = ?
                """,
                (
                    status,
                    json.dumps(output or {}, ensure_ascii=False, default=str),
                    finished_at,
                    duration_ms,
                    error,
                    datetime.now(timezone.utc).isoformat(),
                    step_id,
                ),
            )

    def insert_research_pipeline_artifact(self, artifact: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into research_pipeline_artifacts (
                  artifact_id, run_id, step_name, artifact_type, path,
                  ref_id, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    artifact["artifact_id"],
                    artifact["run_id"],
                    artifact.get("step_name"),
                    artifact["artifact_type"],
                    artifact.get("path"),
                    artifact.get("ref_id"),
                    json.dumps(artifact.get("payload", {}), ensure_ascii=False, default=str),
                    artifact["created_at"],
                ),
            )

    def list_research_pipeline_runs(self, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from research_pipeline_runs order by started_at desc"
        params: list[Any] = []
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def get_research_pipeline_run(self, run_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute("select * from research_pipeline_runs where run_id = ?", (run_id,)).fetchone()

    def get_research_pipeline_run_by_trigger(self, triggered_by: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from research_pipeline_runs where triggered_by = ? order by started_at desc limit 1",
                (triggered_by,),
            ).fetchone()

    def list_research_pipeline_steps(self, run_id: str | None = None, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from research_pipeline_steps"
        params: list[Any] = []
        if run_id:
            query += " where run_id = ?"
            params.append(run_id)
        query += " order by created_at desc, step_name"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def latest_research_pipeline_step(self, run_id: str, step_name: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                """
                select *
                from research_pipeline_steps
                where run_id = ? and step_name = ?
                order by attempt desc
                limit 1
                """,
                (run_id, step_name),
            ).fetchone()

    def next_research_pipeline_step_attempt(self, run_id: str, step_name: str) -> int:
        with self.connect() as conn:
            row = conn.execute(
                """
                select max(attempt) as max_attempt
                from research_pipeline_steps
                where run_id = ? and step_name = ?
                """,
                (run_id, step_name),
            ).fetchone()
        return int(row["max_attempt"] or 0) + 1

    def latest_research_pipeline_steps(self, run_id: str) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    """
                    select steps.*
                    from research_pipeline_steps steps
                    join (
                      select step_name, max(attempt) as max_attempt
                      from research_pipeline_steps
                      where run_id = ?
                      group by step_name
                    ) latest
                      on steps.step_name = latest.step_name
                     and steps.attempt = latest.max_attempt
                    where steps.run_id = ?
                    order by steps.created_at, steps.step_name
                    """,
                    (run_id, run_id),
                )
            )

    def list_research_pipeline_artifacts(self, run_id: str | None = None, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from research_pipeline_artifacts"
        params: list[Any] = []
        if run_id:
            query += " where run_id = ?"
            params.append(run_id)
        query += " order by created_at desc, artifact_type"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def enqueue_autonomous_request(self, request: dict[str, Any]) -> sqlite3.Row:
        with self.connect() as conn:
            conn.execute(
                """
                insert into autonomous_run_requests (
                  request_id, trigger_type, status, requested_at, available_at,
                  attempt, dedupe_key, payload_json, result_json
                ) values (?, ?, 'queued', ?, ?, 0, ?, ?, '{}')
                on conflict do nothing
                """,
                (
                    request["request_id"],
                    request["trigger_type"],
                    request["requested_at"],
                    request.get("available_at") or request["requested_at"],
                    request.get("dedupe_key"),
                    json.dumps(request.get("payload", {}), ensure_ascii=False, default=str),
                ),
            )
            if request.get("dedupe_key"):
                row = conn.execute(
                    "select * from autonomous_run_requests where dedupe_key = ?",
                    (request["dedupe_key"],),
                ).fetchone()
            else:
                row = conn.execute(
                    "select * from autonomous_run_requests where request_id = ?",
                    (request["request_id"],),
                ).fetchone()
        if row is None:
            raise RuntimeError("Failed to enqueue autonomous request")
        return row

    def claim_autonomous_request(
        self,
        worker_id: str,
        now: str,
        lease_expires_at: str,
    ) -> sqlite3.Row | None:
        with self.connect() as conn:
            conn.execute("begin immediate")
            conn.execute(
                """
                update autonomous_run_requests
                set status = 'queued', worker_id = null, claimed_at = null,
                    lease_expires_at = null, available_at = ?
                where status = 'running' and lease_expires_at is not null and lease_expires_at < ?
                """,
                (now, now),
            )
            row = conn.execute(
                """
                select * from autonomous_run_requests
                where status = 'queued' and available_at <= ?
                order by case when trigger_type = 'instant-research' then 0 else 1 end,
                         requested_at, request_id
                limit 1
                """,
                (now,),
            ).fetchone()
            if row is None:
                return None
            changed = conn.execute(
                """
                update autonomous_run_requests
                set status = 'running', worker_id = ?, claimed_at = ?, started_at = ?,
                    lease_expires_at = ?, attempt = attempt + 1, error = null
                where request_id = ? and status = 'queued'
                """,
                (worker_id, now, now, lease_expires_at, row["request_id"]),
            ).rowcount
            if changed != 1:
                return None
            return conn.execute(
                "select * from autonomous_run_requests where request_id = ?",
                (row["request_id"],),
            ).fetchone()

    def reconcile_stale_autonomous_runs(
        self,
        now: str,
        orphaned_before: str,
    ) -> dict[str, int]:
        with self.connect() as conn:
            conn.execute("begin immediate")
            stale_rows = list(
                conn.execute(
                    """
                    select loops.*
                    from autonomous_loop_runs loops
                    where loops.status = 'running'
                      and not exists (
                        select 1
                        from autonomous_run_requests requests
                        where requests.loop_run_id = loops.loop_run_id
                          and requests.status = 'running'
                          and requests.lease_expires_at is not null
                          and requests.lease_expires_at >= ?
                      )
                      and (
                        loops.started_at < ?
                        or exists (
                          select 1
                          from autonomous_run_requests requests
                          where requests.loop_run_id = loops.loop_run_id
                            and (
                              requests.status <> 'running'
                              or requests.lease_expires_at is null
                              or requests.lease_expires_at < ?
                            )
                        )
                      )
                    """,
                    (now, orphaned_before, now),
                )
            )
            loop_count = 0
            pipeline_count = 0
            for row in stale_rows:
                loop_run_id = str(row["loop_run_id"])
                stored_summary = _loads(row["summary_json"], {})
                summary = stored_summary if isinstance(stored_summary, dict) else {}
                summary["recovery"] = {
                    "status": "abandoned",
                    "reason": "worker_lease_expired",
                    "reconciled_at": now,
                }
                loop_count += conn.execute(
                    """
                    update autonomous_loop_runs
                    set status = 'abandoned', summary_json = ?, finished_at = ?, error = ?
                    where loop_run_id = ? and status = 'running'
                    """,
                    (
                        json.dumps(summary, ensure_ascii=False, default=str),
                        now,
                        "worker_lease_expired",
                        loop_run_id,
                    ),
                ).rowcount
                conn.execute(
                    """
                    update autonomous_loop_steps
                    set status = 'failed', finished_at = ?, error = ?, updated_at = ?
                    where loop_run_id = ? and status = 'running'
                    """,
                    (now, "worker_lease_expired", now, loop_run_id),
                )
                pipeline_rows = list(
                    conn.execute(
                        """
                        select run_id
                        from research_pipeline_runs
                        where triggered_by = ? and status = 'running'
                        """,
                        (f"autonomous:{loop_run_id}",),
                    )
                )
                for pipeline_row in pipeline_rows:
                    run_id = str(pipeline_row["run_id"])
                    pipeline_count += conn.execute(
                        """
                        update research_pipeline_runs
                        set status = 'failed', finished_at = ?, error = ?
                        where run_id = ? and status = 'running'
                        """,
                        (now, "parent_loop_abandoned", run_id),
                    ).rowcount
                    conn.execute(
                        """
                        update research_pipeline_steps
                        set status = 'failed', finished_at = ?, error = ?, updated_at = ?
                        where run_id = ? and status = 'running'
                        """,
                        (now, "parent_loop_abandoned", now, run_id),
                    )
        return {"loop_count": loop_count, "pipeline_count": pipeline_count}

    def heartbeat_autonomous_request(self, request_id: str, worker_id: str, lease_expires_at: str) -> bool:
        with self.connect() as conn:
            changed = conn.execute(
                """
                update autonomous_run_requests
                set lease_expires_at = ?
                where request_id = ? and worker_id = ? and status = 'running'
                """,
                (lease_expires_at, request_id, worker_id),
            ).rowcount
        return changed == 1

    def finish_autonomous_request(
        self,
        request_id: str,
        worker_id: str,
        status: str,
        finished_at: str,
        loop_run_id: str | None,
        result: dict[str, Any],
        error: str | None,
    ) -> bool:
        if status not in {"succeeded", "partial", "failed", "cancelled"}:
            raise ValueError(f"Unsupported autonomous request status: {status}")
        with self.connect() as conn:
            changed = conn.execute(
                """
                update autonomous_run_requests
                set status = ?, finished_at = ?, loop_run_id = ?, result_json = ?,
                    error = ?, lease_expires_at = null
                where request_id = ? and worker_id = ? and status = 'running'
                """,
                (
                    status,
                    finished_at,
                    loop_run_id,
                    json.dumps(result, ensure_ascii=False, default=str),
                    error,
                    request_id,
                    worker_id,
                ),
            ).rowcount
        return changed == 1

    def list_autonomous_requests(self, limit: int = 20) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    "select * from autonomous_run_requests order by requested_at desc limit ?",
                    (max(1, limit),),
                )
            )

    def get_autonomous_request(self, request_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from autonomous_run_requests where request_id = ?",
                (request_id,),
            ).fetchone()

    def link_autonomous_request_loop(self, request_id: str, loop_run_id: str) -> bool:
        with self.connect() as conn:
            changed = conn.execute(
                """
                update autonomous_run_requests
                set loop_run_id = ?
                where request_id = ? and status = 'running'
                """,
                (loop_run_id, request_id),
            ).rowcount
        return changed == 1

    def renew_autonomous_worker_lease(
        self,
        lease_name: str,
        owner_id: str,
        now: str,
        expires_at: str,
        metadata: dict[str, Any] | None = None,
    ) -> bool:
        with self.connect() as conn:
            conn.execute("begin immediate")
            conn.execute(
                """
                insert into autonomous_worker_leases (
                  lease_name, owner_id, acquired_at, heartbeat_at, expires_at, metadata_json
                ) values (?, ?, ?, ?, ?, ?)
                on conflict(lease_name) do update set
                  owner_id=excluded.owner_id,
                  acquired_at=case when autonomous_worker_leases.owner_id = excluded.owner_id
                                   then autonomous_worker_leases.acquired_at else excluded.acquired_at end,
                  heartbeat_at=excluded.heartbeat_at,
                  expires_at=excluded.expires_at,
                  metadata_json=excluded.metadata_json
                where autonomous_worker_leases.owner_id = excluded.owner_id
                   or autonomous_worker_leases.expires_at < ?
                """,
                (
                    lease_name,
                    owner_id,
                    now,
                    now,
                    expires_at,
                    json.dumps(metadata or {}, ensure_ascii=False, default=str),
                    now,
                ),
            )
            row = conn.execute(
                "select owner_id from autonomous_worker_leases where lease_name = ?",
                (lease_name,),
            ).fetchone()
        return row is not None and row["owner_id"] == owner_id

    def release_autonomous_worker_lease(self, lease_name: str, owner_id: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "delete from autonomous_worker_leases where lease_name = ? and owner_id = ?",
                (lease_name, owner_id),
            )

    def upsert_autonomous_worker_heartbeat(self, heartbeat: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into autonomous_worker_heartbeats (
                  worker_id, status, process_id, hostname, started_at, heartbeat_at,
                  current_request_id, last_loop_run_id, last_error, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(worker_id) do update set
                  status=excluded.status,
                  heartbeat_at=excluded.heartbeat_at,
                  current_request_id=excluded.current_request_id,
                  last_loop_run_id=excluded.last_loop_run_id,
                  last_error=excluded.last_error,
                  metadata_json=excluded.metadata_json
                """,
                (
                    heartbeat["worker_id"],
                    heartbeat["status"],
                    heartbeat.get("process_id"),
                    heartbeat.get("hostname"),
                    heartbeat["started_at"],
                    heartbeat["heartbeat_at"],
                    heartbeat.get("current_request_id"),
                    heartbeat.get("last_loop_run_id"),
                    heartbeat.get("last_error"),
                    json.dumps(heartbeat.get("metadata", {}), ensure_ascii=False, default=str),
                ),
            )

    def autonomous_worker_snapshot(self, request_limit: int = 20) -> dict[str, Any]:
        with self.connect() as conn:
            queue_rows = conn.execute(
                "select status, count(*) as count from autonomous_run_requests group by status"
            ).fetchall()
            workers = conn.execute(
                "select * from autonomous_worker_heartbeats order by heartbeat_at desc"
            ).fetchall()
            leases = conn.execute(
                "select * from autonomous_worker_leases order by lease_name"
            ).fetchall()
            scheduler = conn.execute(
                "select * from autonomous_scheduler_state where schedule_id = 'autonomous-loop'"
            ).fetchone()
        return {
            "queue": {row["status"]: row["count"] for row in queue_rows},
            "workers": [dict(row) for row in workers],
            "leases": [dict(row) for row in leases],
            "scheduler": dict(scheduler) if scheduler else None,
            "recent_requests": [dict(row) for row in self.list_autonomous_requests(limit=request_limit)],
        }

    def get_autonomous_scheduler_state(self) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from autonomous_scheduler_state where schedule_id = 'autonomous-loop'"
            ).fetchone()

    def upsert_autonomous_scheduler_state(
        self,
        next_run_at: str | None,
        last_enqueued_at: str | None,
        last_request_id: str | None,
        updated_at: str,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into autonomous_scheduler_state (
                  schedule_id, next_run_at, last_enqueued_at, last_request_id,
                  updated_at, metadata_json
                ) values ('autonomous-loop', ?, ?, ?, ?, ?)
                on conflict(schedule_id) do update set
                  next_run_at=excluded.next_run_at,
                  last_enqueued_at=excluded.last_enqueued_at,
                  last_request_id=excluded.last_request_id,
                  updated_at=excluded.updated_at,
                  metadata_json=excluded.metadata_json
                """,
                (
                    next_run_at,
                    last_enqueued_at,
                    last_request_id,
                    updated_at,
                    json.dumps(metadata or {}, ensure_ascii=False, default=str),
                ),
            )

    def insert_autonomous_loop_run(self, run: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into autonomous_loop_runs (
                  loop_run_id, status, trigger_type, config_json,
                  summary_json, started_at, finished_at, error
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run["loop_run_id"],
                    run["status"],
                    run["trigger_type"],
                    json.dumps(run.get("config", {}), ensure_ascii=False, default=str),
                    json.dumps(run.get("summary", {}), ensure_ascii=False, default=str),
                    run["started_at"],
                    run.get("finished_at"),
                    run.get("error"),
                ),
            )

    def update_autonomous_loop_run(
        self,
        loop_run_id: str,
        status: str,
        summary: dict[str, Any] | None = None,
        finished_at: str | None = None,
        error: str | None = None,
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                update autonomous_loop_runs
                set status = ?,
                    summary_json = ?,
                    finished_at = ?,
                    error = ?
                where loop_run_id = ?
                """,
                (
                    status,
                    json.dumps(summary or {}, ensure_ascii=False, default=str),
                    finished_at,
                    error,
                    loop_run_id,
                ),
            )

    def list_autonomous_loop_runs(self, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from autonomous_loop_runs order by started_at desc"
        params: list[Any] = []
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def get_autonomous_loop_run(self, loop_run_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from autonomous_loop_runs where loop_run_id = ?",
                (loop_run_id,),
            ).fetchone()

    def insert_autonomous_loop_step(self, step: dict[str, Any]) -> None:
        now = datetime.now(timezone.utc).isoformat()
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into autonomous_loop_steps (
                  step_id, loop_run_id, step_name, status, attempt, started_at,
                  finished_at, duration_ms, input_json, output_json, error,
                  created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    step["step_id"],
                    step["loop_run_id"],
                    step["step_name"],
                    step["status"],
                    step.get("attempt", 1),
                    step.get("started_at"),
                    step.get("finished_at"),
                    step.get("duration_ms"),
                    json.dumps(step.get("input", {}), ensure_ascii=False, default=str),
                    json.dumps(step.get("output", {}), ensure_ascii=False, default=str),
                    step.get("error"),
                    step.get("created_at") or now,
                    step.get("updated_at") or now,
                ),
            )

    def update_autonomous_loop_step(
        self,
        step_id: str,
        status: str,
        output: dict[str, Any] | None = None,
        finished_at: str | None = None,
        duration_ms: int | None = None,
        error: str | None = None,
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                update autonomous_loop_steps
                set status = ?,
                    output_json = ?,
                    finished_at = ?,
                    duration_ms = ?,
                    error = ?,
                    updated_at = ?
                where step_id = ?
                """,
                (
                    status,
                    json.dumps(output or {}, ensure_ascii=False, default=str),
                    finished_at,
                    duration_ms,
                    error,
                    datetime.now(timezone.utc).isoformat(),
                    step_id,
                ),
            )

    def next_autonomous_loop_step_attempt(self, loop_run_id: str, step_name: str) -> int:
        with self.connect() as conn:
            row = conn.execute(
                """
                select max(attempt) as max_attempt
                from autonomous_loop_steps
                where loop_run_id = ? and step_name = ?
                """,
                (loop_run_id, step_name),
            ).fetchone()
        return int(row["max_attempt"] or 0) + 1

    def latest_autonomous_loop_steps(self, loop_run_id: str) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    """
                    select steps.*
                    from autonomous_loop_steps steps
                    join (
                      select step_name, max(attempt) as max_attempt
                      from autonomous_loop_steps
                      where loop_run_id = ?
                      group by step_name
                    ) latest
                      on steps.step_name = latest.step_name
                     and steps.attempt = latest.max_attempt
                    where steps.loop_run_id = ?
                    order by steps.created_at, steps.step_name
                    """,
                    (loop_run_id, loop_run_id),
                )
            )

    def list_autonomous_loop_steps(self, loop_run_id: str | None = None, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from autonomous_loop_steps"
        params: list[Any] = []
        if loop_run_id:
            query += " where loop_run_id = ?"
            params.append(loop_run_id)
        query += " order by created_at desc, step_name"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_autonomous_loop_artifact(self, artifact: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into autonomous_loop_artifacts (
                  artifact_id, loop_run_id, step_name, artifact_type,
                  ref_id, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    artifact["artifact_id"],
                    artifact["loop_run_id"],
                    artifact.get("step_name"),
                    artifact["artifact_type"],
                    artifact.get("ref_id"),
                    json.dumps(artifact.get("payload", {}), ensure_ascii=False, default=str),
                    artifact["created_at"],
                ),
            )

    def list_autonomous_loop_artifacts(self, loop_run_id: str | None = None, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from autonomous_loop_artifacts"
        params: list[Any] = []
        if loop_run_id:
            query += " where loop_run_id = ?"
            params.append(loop_run_id)
        query += " order by created_at desc, artifact_type"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_ai_debate_council(self, council: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into ai_debate_councils (
                  debate_id, loop_run_id, research_pipeline_run_id, operator_report_id,
                  status, protocol, provider, model, rounds, summary_json,
                  payload_json, error, created_at, template_id, round_summaries_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    council["debate_id"],
                    council["loop_run_id"],
                    council.get("research_pipeline_run_id"),
                    council.get("operator_report_id"),
                    council["status"],
                    council.get("protocol"),
                    council.get("provider"),
                    council.get("model"),
                    int(council.get("rounds") or 0),
                    json.dumps(council.get("summary", {}), ensure_ascii=False, default=str),
                    json.dumps(council.get("payload", {}), ensure_ascii=False, default=str),
                    council.get("error"),
                    council["created_at"],
                    council.get("template_id"),
                    json.dumps(council.get("round_summaries", []), ensure_ascii=False, default=str),
                ),
            )

    def list_ai_debate_councils(self, limit: int | None = None, loop_run_id: str | None = None) -> list[sqlite3.Row]:
        query = "select * from ai_debate_councils"
        params: list[Any] = []
        if loop_run_id:
            query += " where loop_run_id = ?"
            params.append(loop_run_id)
        query += " order by created_at desc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def get_ai_debate_council(self, debate_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from ai_debate_councils where debate_id = ?",
                (debate_id,),
            ).fetchone()

    def insert_ai_debate_message(self, message: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into ai_debate_messages (
                  message_id, debate_id, loop_run_id, round_index, agent_role,
                  stance, provider, protocol, model, status, content_json,
                  error, created_at, phase_id, message_type, turn_index,
                  reply_to_json, usage_json, duration_ms, prompt_hash,
                  prompt_version, input_hash, experiment_id, variant_id,
                  estimated_cost_usd, cost_status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    message["message_id"],
                    message["debate_id"],
                    message["loop_run_id"],
                    int(message.get("round_index") or 0),
                    message["agent_role"],
                    message.get("stance"),
                    message.get("provider"),
                    message.get("protocol"),
                    message.get("model"),
                    message["status"],
                    json.dumps(message.get("content", {}), ensure_ascii=False, default=str),
                    message.get("error"),
                    message["created_at"],
                    message.get("phase_id"),
                    message.get("message_type"),
                    int(message.get("turn_index") or 0),
                    json.dumps(message.get("reply_to_message_ids", []), ensure_ascii=False, default=str),
                    json.dumps(message.get("usage", {}), ensure_ascii=False, default=str),
                    message.get("duration_ms"),
                    message.get("prompt_hash"),
                    message.get("prompt_version"),
                    message.get("input_hash") or message.get("prompt_hash"),
                    message.get("experiment_id"),
                    message.get("variant_id"),
                    message.get("estimated_cost_usd"),
                    message.get("cost_status") or "unknown",
                ),
            )

    def list_ai_debate_messages(self, debate_id: str | None = None, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from ai_debate_messages"
        params: list[Any] = []
        if debate_id:
            query += " where debate_id = ?"
            params.append(debate_id)
        query += " order by round_index desc, turn_index desc, created_at desc, agent_role"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_ai_trade_decision(self, decision: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into ai_trade_decisions (
                  decision_id, loop_run_id, debate_id, source_report_id, candidate_id,
                  provider, market_type, symbol, normalized_symbol, action, status,
                  confidence, score, horizon, entry_reference, target_price,
                  invalidation_price, position_sizing_json, rationale_json,
                  risk_warnings_json, evidence_refs_json, policy_json, payload_json,
                  created_at, ai_site_id, ai_model, prompt_version, experiment_id,
                  variant_id
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    decision["decision_id"],
                    decision["loop_run_id"],
                    decision.get("debate_id"),
                    decision.get("source_report_id"),
                    decision.get("candidate_id"),
                    decision.get("provider"),
                    decision.get("market_type"),
                    decision["symbol"],
                    decision.get("normalized_symbol"),
                    decision["action"],
                    decision["status"],
                    float(decision.get("confidence") or 0.0),
                    float(decision.get("score") or 0.0),
                    decision.get("horizon"),
                    decision.get("entry_reference"),
                    decision.get("target_price"),
                    decision.get("invalidation_price"),
                    json.dumps(decision.get("position_sizing", {}), ensure_ascii=False, default=str),
                    json.dumps(decision.get("rationale", []), ensure_ascii=False, default=str),
                    json.dumps(decision.get("risk_warnings", []), ensure_ascii=False, default=str),
                    json.dumps(decision.get("evidence_refs", []), ensure_ascii=False, default=str),
                    json.dumps(decision.get("policy", {}), ensure_ascii=False, default=str),
                    json.dumps(decision, ensure_ascii=False, default=str),
                    decision["created_at"],
                    decision.get("ai_site_id"),
                    decision.get("ai_model"),
                    decision.get("prompt_version"),
                    decision.get("experiment_id"),
                    decision.get("variant_id"),
                ),
            )

    def list_ai_trade_decisions(
        self,
        limit: int | None = None,
        loop_run_id: str | None = None,
        debate_id: str | None = None,
    ) -> list[sqlite3.Row]:
        query = "select * from ai_trade_decisions"
        params: list[Any] = []
        clauses: list[str] = []
        if loop_run_id:
            clauses.append("loop_run_id = ?")
            params.append(loop_run_id)
        if debate_id:
            clauses.append("debate_id = ?")
            params.append(debate_id)
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by created_at desc, score desc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def get_ai_trade_decision(self, decision_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from ai_trade_decisions where decision_id = ?",
                (decision_id,),
            ).fetchone()

    def get_decision_review(self, decision_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from decision_reviews where decision_id = ?",
                (decision_id,),
            ).fetchone()

    def list_decision_reviews(
        self,
        *,
        status: str | None = None,
        loop_run_id: str | None = None,
        limit: int = 100,
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        if status:
            clauses.append("status = ?")
            params.append(status)
        if loop_run_id:
            clauses.append("loop_run_id = ?")
            params.append(loop_run_id)
        query = "select * from decision_reviews"
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by updated_at desc limit ?"
        params.append(max(1, min(limit, 500)))
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def save_decision_review(
        self,
        review: dict[str, Any],
        *,
        expected_version: int | None = None,
    ) -> sqlite3.Row:
        with self.connect() as conn:
            current = conn.execute(
                "select version, created_at from decision_reviews where decision_id = ?",
                (review["decision_id"],),
            ).fetchone()
            if current is None:
                if expected_version not in {None, 0}:
                    raise StaleRecordError("复核记录已变化，请刷新后重试")
                version = 1
                conn.execute(
                    """
                    insert into decision_reviews (
                      review_id, decision_id, loop_run_id, status, reviewer_id,
                      note, version, metadata_json, created_at, updated_at, reviewed_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        review["review_id"], review["decision_id"], review["loop_run_id"],
                        review["status"], review["reviewer_id"], review.get("note", ""), version,
                        json.dumps(review.get("metadata", {}), ensure_ascii=False, default=str),
                        review["created_at"], review["updated_at"], review.get("reviewed_at"),
                    ),
                )
            else:
                current_version = int(current["version"])
                if expected_version is not None and expected_version != current_version:
                    raise StaleRecordError("复核记录已变化，请刷新后重试")
                version = current_version + 1
                cursor = conn.execute(
                    """
                    update decision_reviews
                    set status = ?, reviewer_id = ?, note = ?, version = ?,
                        metadata_json = ?, updated_at = ?, reviewed_at = ?
                    where decision_id = ? and version = ?
                    """,
                    (
                        review["status"], review["reviewer_id"], review.get("note", ""), version,
                        json.dumps(review.get("metadata", {}), ensure_ascii=False, default=str),
                        review["updated_at"], review.get("reviewed_at"), review["decision_id"],
                        current_version,
                    ),
                )
                if cursor.rowcount != 1:
                    raise StaleRecordError("复核记录已变化，请刷新后重试")
            saved = conn.execute(
                "select * from decision_reviews where decision_id = ?",
                (review["decision_id"],),
            ).fetchone()
            if saved is None:
                raise RuntimeError("复核记录保存失败")
            return saved

    def insert_run_replay(self, replay: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into run_replays (
                  replay_id, source_loop_run_id, source_pipeline_run_id, request_id,
                  mode, status, config_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    replay["replay_id"], replay["source_loop_run_id"], replay.get("source_pipeline_run_id"),
                    replay.get("request_id"), replay["mode"], replay["status"],
                    json.dumps(replay.get("config", {}), ensure_ascii=False, default=str),
                    replay["created_at"], replay["updated_at"],
                ),
            )

    def list_run_replays(self, source_loop_run_id: str | None = None, limit: int = 100) -> list[sqlite3.Row]:
        query = "select * from run_replays"
        params: list[Any] = []
        if source_loop_run_id:
            query += " where source_loop_run_id = ?"
            params.append(source_loop_run_id)
        query += " order by created_at desc limit ?"
        params.append(max(1, min(limit, 500)))
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_workflow_version_if_absent(self, version: dict[str, Any]) -> bool:
        with self.connect() as conn:
            cursor = conn.execute(
                """
                insert or ignore into workflow_versions (
                  workflow_version_id, template_id, version_number, status,
                  content_json, checksum, parent_version_id, change_note,
                  created_by, created_at, published_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    version["workflow_version_id"], version["template_id"], version["version_number"],
                    version["status"], json.dumps(version["content"], ensure_ascii=False, default=str),
                    version["checksum"], version.get("parent_version_id"), version.get("change_note", ""),
                    version.get("created_by", "local"), version["created_at"], version.get("published_at"),
                ),
            )
            return cursor.rowcount > 0

    def get_workflow_version(self, workflow_version_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from workflow_versions where workflow_version_id = ?",
                (workflow_version_id,),
            ).fetchone()

    def list_workflow_versions(self, template_id: str | None = None, limit: int = 200) -> list[sqlite3.Row]:
        query = "select * from workflow_versions"
        params: list[Any] = []
        if template_id:
            query += " where template_id = ?"
            params.append(template_id)
        query += " order by template_id, version_number desc limit ?"
        params.append(max(1, min(limit, 1000)))
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def next_workflow_version_number(self, template_id: str) -> int:
        with self.connect() as conn:
            row = conn.execute(
                "select coalesce(max(version_number), 0) as current from workflow_versions where template_id = ?",
                (template_id,),
            ).fetchone()
            return int(row["current"] or 0) + 1

    def save_workflow_draft(
        self,
        version: dict[str, Any],
        *,
        expected_checksum: str | None = None,
    ) -> sqlite3.Row:
        with self.connect() as conn:
            current = conn.execute(
                "select * from workflow_versions where workflow_version_id = ?",
                (version["workflow_version_id"],),
            ).fetchone()
            if current is None:
                conn.execute(
                    """
                    insert into workflow_versions (
                      workflow_version_id, template_id, version_number, status,
                      content_json, checksum, parent_version_id, change_note,
                      created_by, created_at, published_at
                    ) values (?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?, null)
                    """,
                    (
                        version["workflow_version_id"], version["template_id"], version["version_number"],
                        json.dumps(version["content"], ensure_ascii=False, default=str), version["checksum"],
                        version.get("parent_version_id"), version.get("change_note", ""),
                        version.get("created_by", "local"), version["created_at"],
                    ),
                )
            else:
                if current["status"] != "draft":
                    raise ValueError("已发布或归档版本不可原地修改")
                if expected_checksum is not None and current["checksum"] != expected_checksum:
                    raise StaleRecordError("工作流草稿已变化，请刷新后重试")
                cursor = conn.execute(
                    """
                    update workflow_versions
                    set content_json = ?, checksum = ?, change_note = ?, parent_version_id = ?
                    where workflow_version_id = ? and status = 'draft'
                    """,
                    (
                        json.dumps(version["content"], ensure_ascii=False, default=str), version["checksum"],
                        version.get("change_note", ""), version.get("parent_version_id"),
                        version["workflow_version_id"],
                    ),
                )
                if cursor.rowcount != 1:
                    raise StaleRecordError("工作流草稿已变化，请刷新后重试")
            saved = conn.execute(
                "select * from workflow_versions where workflow_version_id = ?",
                (version["workflow_version_id"],),
            ).fetchone()
            if saved is None:
                raise RuntimeError("工作流草稿保存失败")
            return saved

    def publish_workflow_version(self, workflow_version_id: str, published_at: str) -> sqlite3.Row:
        with self.connect() as conn:
            current = conn.execute(
                "select * from workflow_versions where workflow_version_id = ?",
                (workflow_version_id,),
            ).fetchone()
            if current is None:
                raise LookupError(f"未找到工作流版本：{workflow_version_id}")
            if current["status"] != "draft":
                raise ValueError("只有 draft 版本可以发布")
            conn.execute(
                "update workflow_versions set status = 'archived' where template_id = ? and status = 'published'",
                (current["template_id"],),
            )
            conn.execute(
                "update workflow_versions set status = 'published', published_at = ? where workflow_version_id = ?",
                (published_at, workflow_version_id),
            )
            published = conn.execute(
                "select * from workflow_versions where workflow_version_id = ?",
                (workflow_version_id,),
            ).fetchone()
            if published is None:
                raise RuntimeError("工作流版本发布失败")
            return published

    def insert_workflow_node_test(self, test_run: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into workflow_node_test_runs (
                  node_test_id, template_id, workflow_version_id, node_id, status,
                  input_json, output_json, estimated_tokens, estimated_cost_usd,
                  cost_status, error, created_at, finished_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    test_run["node_test_id"], test_run["template_id"], test_run.get("workflow_version_id"),
                    test_run["node_id"], test_run["status"],
                    json.dumps(test_run.get("input", {}), ensure_ascii=False, default=str),
                    json.dumps(test_run.get("output", {}), ensure_ascii=False, default=str),
                    test_run.get("estimated_tokens"), test_run.get("estimated_cost_usd"),
                    test_run.get("cost_status", "unknown"), test_run.get("error"),
                    test_run["created_at"], test_run.get("finished_at"),
                ),
            )

    def list_workflow_node_tests(self, template_id: str, limit: int = 50) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    "select * from workflow_node_test_runs where template_id = ? order by created_at desc limit ?",
                    (template_id, max(1, min(limit, 500))),
                )
            )

    def insert_workflow_run(self, workflow_run: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into workflow_runs (
                  workflow_run_id, template_id, workflow_version_id, trigger_type,
                  mode, status, depth, cost_tier, request_json,
                  template_snapshot_json, plan_json, result_json, error, version,
                  created_at, started_at, updated_at, finished_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    workflow_run["workflow_run_id"], workflow_run["template_id"],
                    workflow_run.get("workflow_version_id"), workflow_run["trigger_type"],
                    workflow_run["mode"], workflow_run["status"], workflow_run["depth"],
                    workflow_run["cost_tier"],
                    json.dumps(workflow_run.get("request", {}), ensure_ascii=False, default=str),
                    json.dumps(workflow_run.get("template_snapshot", {}), ensure_ascii=False, default=str),
                    json.dumps(workflow_run.get("plan", {}), ensure_ascii=False, default=str),
                    json.dumps(workflow_run.get("result", {}), ensure_ascii=False, default=str),
                    workflow_run.get("error"), int(workflow_run.get("version") or 0),
                    workflow_run["created_at"], workflow_run.get("started_at"),
                    workflow_run["updated_at"], workflow_run.get("finished_at"),
                ),
            )

    def update_workflow_run(
        self,
        workflow_run_id: str,
        *,
        status: str,
        result: dict[str, Any] | None,
        error: str | None,
        updated_at: str,
        started_at: str | None = None,
        finished_at: str | None = None,
        expected_version: int | None = None,
    ) -> sqlite3.Row:
        with self.connect() as conn:
            current = conn.execute(
                "select * from workflow_runs where workflow_run_id = ?",
                (workflow_run_id,),
            ).fetchone()
            if current is None:
                raise LookupError(f"未找到工作流运行：{workflow_run_id}")
            if expected_version is not None and int(current["version"]) != expected_version:
                raise StaleRecordError("工作流运行状态已变化，请刷新后重试")
            conn.execute(
                """
                update workflow_runs
                set status = ?, result_json = ?, error = ?, version = version + 1,
                    started_at = coalesce(started_at, ?), updated_at = ?,
                    finished_at = coalesce(?, finished_at)
                where workflow_run_id = ?
                """,
                (
                    status,
                    json.dumps(result or {}, ensure_ascii=False, default=str),
                    error,
                    started_at,
                    updated_at,
                    finished_at,
                    workflow_run_id,
                ),
            )
            updated = conn.execute(
                "select * from workflow_runs where workflow_run_id = ?",
                (workflow_run_id,),
            ).fetchone()
            if updated is None:
                raise RuntimeError("工作流运行更新失败")
            return updated

    def get_workflow_run(self, workflow_run_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from workflow_runs where workflow_run_id = ?",
                (workflow_run_id,),
            ).fetchone()

    def list_workflow_runs(
        self,
        *,
        template_id: str | None = None,
        status: str | None = None,
        limit: int = 100,
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        if template_id:
            clauses.append("template_id = ?")
            params.append(template_id)
        if status:
            clauses.append("status = ?")
            params.append(status)
        query = "select * from workflow_runs"
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by created_at desc limit ?"
        params.append(max(1, min(limit, 1000)))
        with self.connect() as conn:
            return list(conn.execute(query, tuple(params)))

    def insert_workflow_ledger(self, ledger: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or ignore into workflow_ledgers (
                  ledger_id, workflow_run_id, ledger_type, revision, payload_json, created_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
                (
                    ledger["ledger_id"], ledger["workflow_run_id"], ledger["ledger_type"],
                    int(ledger["revision"]),
                    json.dumps(ledger.get("payload", {}), ensure_ascii=False, default=str),
                    ledger["created_at"],
                ),
            )

    def list_workflow_ledgers(self, workflow_run_id: str) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    """
                    select * from workflow_ledgers
                    where workflow_run_id = ?
                    order by ledger_type, revision
                    """,
                    (workflow_run_id,),
                )
            )

    def upsert_workflow_node_checkpoint(self, checkpoint: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into workflow_node_checkpoints (
                  checkpoint_id, workflow_run_id, node_id, phase_id, iteration,
                  node_type, operation, status, attempt, output_json, error,
                  created_at, updated_at, completed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(workflow_run_id, node_id, phase_id, iteration) do update set
                  status = case
                    when workflow_node_checkpoints.status in ('completed', 'skipped')
                     and excluded.status in ('pending', 'running')
                    then workflow_node_checkpoints.status
                    else excluded.status
                  end,
                  attempt = max(workflow_node_checkpoints.attempt, excluded.attempt),
                  output_json = case
                    when excluded.output_json = '{}' then workflow_node_checkpoints.output_json
                    else excluded.output_json
                  end,
                  error = excluded.error,
                  updated_at = excluded.updated_at,
                  completed_at = coalesce(excluded.completed_at, workflow_node_checkpoints.completed_at)
                """,
                (
                    checkpoint["checkpoint_id"], checkpoint["workflow_run_id"],
                    checkpoint["node_id"], checkpoint.get("phase_id") or "",
                    int(checkpoint.get("iteration") or 0), checkpoint["node_type"],
                    checkpoint.get("operation"), checkpoint["status"],
                    int(checkpoint.get("attempt") or 0),
                    json.dumps(checkpoint.get("output", {}), ensure_ascii=False, default=str),
                    checkpoint.get("error"), checkpoint["created_at"],
                    checkpoint["updated_at"], checkpoint.get("completed_at"),
                ),
            )

    def list_workflow_node_checkpoints(self, workflow_run_id: str) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    """
                    select * from workflow_node_checkpoints
                    where workflow_run_id = ?
                    order by node_id, iteration, updated_at
                    """,
                    (workflow_run_id,),
                )
            )

    def upsert_council_role_score(self, score: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into council_role_scores (
                  score_id, debate_id, template_id, role_id, score,
                  sample_weight, components_json, source_refs_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(debate_id, role_id) do update set
                  score=excluded.score,
                  sample_weight=excluded.sample_weight,
                  components_json=excluded.components_json,
                  source_refs_json=excluded.source_refs_json,
                  created_at=excluded.created_at
                """,
                (
                    score["score_id"], score["debate_id"], score["template_id"],
                    score["role_id"], float(score["score"]),
                    float(score.get("sample_weight") or 0.0),
                    json.dumps(score.get("components", {}), ensure_ascii=False, default=str),
                    json.dumps(score.get("source_refs", []), ensure_ascii=False, default=str),
                    score["created_at"],
                ),
            )

    def list_council_role_scores(
        self,
        *,
        debate_id: str | None = None,
        template_id: str | None = None,
        role_id: str | None = None,
        limit: int = 500,
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        for column, value in (
            ("debate_id", debate_id),
            ("template_id", template_id),
            ("role_id", role_id),
        ):
            if value:
                clauses.append(f"{column} = ?")
                params.append(value)
        query = "select * from council_role_scores"
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by created_at desc limit ?"
        params.append(max(1, min(limit, 5000)))
        with self.connect() as conn:
            return list(conn.execute(query, tuple(params)))

    def insert_council_memory_if_absent(self, memory: dict[str, Any]) -> bool:
        with self.connect() as conn:
            cursor = conn.execute(
                """
                insert or ignore into council_memories (
                  memory_id, template_id, role_id, product_id, symbol, market_type,
                  topic, memory_type, content_json, source_refs_json, quality_score,
                  status, created_at, expires_at, last_used_at, use_count
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    memory["memory_id"], memory["template_id"], memory.get("role_id"),
                    memory.get("product_id"), memory.get("symbol"), memory.get("market_type"),
                    memory.get("topic"), memory["memory_type"],
                    json.dumps(memory.get("content", {}), ensure_ascii=False, default=str),
                    json.dumps(memory.get("source_refs", []), ensure_ascii=False, default=str),
                    float(memory.get("quality_score") or 0.0), memory.get("status") or "active",
                    memory["created_at"], memory.get("expires_at"), memory.get("last_used_at"),
                    int(memory.get("use_count") or 0),
                ),
            )
            return cursor.rowcount > 0

    def list_council_memories(
        self,
        *,
        status: str | None = "active",
        template_id: str | None = None,
        limit: int = 1000,
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        if status:
            clauses.append("status = ?")
            params.append(status)
        if template_id:
            clauses.append("template_id = ?")
            params.append(template_id)
        query = "select * from council_memories"
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by quality_score desc, created_at desc limit ?"
        params.append(max(1, min(limit, 5000)))
        with self.connect() as conn:
            return list(conn.execute(query, tuple(params)))

    def mark_council_memories_used(self, memory_ids: list[str], used_at: str) -> None:
        if not memory_ids:
            return
        placeholders = ",".join("?" for _ in memory_ids)
        with self.connect() as conn:
            conn.execute(
                f"""
                update council_memories
                set last_used_at = ?, use_count = use_count + 1
                where memory_id in ({placeholders})
                """,
                (used_at, *memory_ids),
            )

    def get_shadow_position(self, decision_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from shadow_positions where decision_id = ?",
                (decision_id,),
            ).fetchone()

    def upsert_shadow_position(self, position: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into shadow_positions (
                  position_id, decision_id, review_id, loop_run_id, provider,
                  market_type, symbol, normalized_symbol, side, status, quantity,
                  notional_usdt, entry_price, current_price, exit_price,
                  unrealized_pnl_usdt, realized_pnl_usdt, opened_at, marked_at,
                  closed_at, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(decision_id) do update set
                  review_id=excluded.review_id,
                  status=excluded.status,
                  current_price=excluded.current_price,
                  exit_price=excluded.exit_price,
                  unrealized_pnl_usdt=excluded.unrealized_pnl_usdt,
                  realized_pnl_usdt=excluded.realized_pnl_usdt,
                  marked_at=excluded.marked_at,
                  closed_at=excluded.closed_at,
                  metadata_json=excluded.metadata_json
                """,
                (
                    position["position_id"], position["decision_id"], position["review_id"],
                    position["loop_run_id"], position.get("provider"), position.get("market_type"),
                    position["symbol"], position.get("normalized_symbol"), position["side"],
                    position["status"], position["quantity"], position["notional_usdt"],
                    position["entry_price"], position.get("current_price"), position.get("exit_price"),
                    position.get("unrealized_pnl_usdt"), position.get("realized_pnl_usdt"),
                    position["opened_at"], position.get("marked_at"), position.get("closed_at"),
                    json.dumps(position.get("metadata", {}), ensure_ascii=False, default=str),
                ),
            )

    def list_shadow_positions(self, status: str | None = None, limit: int = 500) -> list[sqlite3.Row]:
        query = "select * from shadow_positions"
        params: list[Any] = []
        if status:
            query += " where status = ?"
            params.append(status)
        query += " order by opened_at desc limit ?"
        params.append(max(1, min(limit, 2000)))
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_shadow_portfolio_snapshot(self, snapshot: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into shadow_portfolio_snapshots (
                  snapshot_id, status, source_loop_run_id, equity_usdt, cash_usdt,
                  gross_exposure_usdt, realized_pnl_usdt, unrealized_pnl_usdt,
                  peak_equity_usdt, drawdown_pct, positions_json, metrics_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    snapshot["snapshot_id"], snapshot["status"], snapshot.get("source_loop_run_id"),
                    snapshot.get("equity_usdt"), snapshot.get("cash_usdt"),
                    snapshot.get("gross_exposure_usdt"), snapshot.get("realized_pnl_usdt"),
                    snapshot.get("unrealized_pnl_usdt"), snapshot.get("peak_equity_usdt"),
                    snapshot.get("drawdown_pct"),
                    json.dumps(snapshot.get("positions", []), ensure_ascii=False, default=str),
                    json.dumps(snapshot.get("metrics", {}), ensure_ascii=False, default=str),
                    snapshot["created_at"],
                ),
            )

    def list_shadow_portfolio_snapshots(self, limit: int = 100) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(
                conn.execute(
                    "select * from shadow_portfolio_snapshots order by created_at desc limit ?",
                    (max(1, min(limit, 1000)),),
                )
            )

    def insert_notification_if_absent(self, notification: dict[str, Any]) -> bool:
        with self.connect() as conn:
            cursor = conn.execute(
                """
                insert or ignore into notification_events (
                  notification_id, category, severity, title, body, status,
                  entity_type, entity_id, dedupe_key, payload_json, created_at,
                  read_at, dismissed_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    notification["notification_id"], notification["category"], notification["severity"],
                    notification["title"], notification["body"], notification.get("status", "unread"),
                    notification.get("entity_type"), notification.get("entity_id"),
                    notification.get("dedupe_key"),
                    json.dumps(notification.get("payload", {}), ensure_ascii=False, default=str),
                    notification["created_at"], notification.get("read_at"), notification.get("dismissed_at"),
                ),
            )
            return cursor.rowcount > 0

    def list_notifications(self, status: str | None = None, limit: int = 100) -> list[sqlite3.Row]:
        query = "select * from notification_events"
        params: list[Any] = []
        if status:
            query += " where status = ?"
            params.append(status)
        query += " order by created_at desc limit ?"
        params.append(max(1, min(limit, 500)))
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def update_notification_status(self, notification_id: str, status: str, changed_at: str) -> bool:
        read_at = changed_at if status == "read" else None
        dismissed_at = changed_at if status == "dismissed" else None
        with self.connect() as conn:
            cursor = conn.execute(
                """
                update notification_events
                set status = ?, read_at = coalesce(?, read_at), dismissed_at = coalesce(?, dismissed_at)
                where notification_id = ?
                """,
                (status, read_at, dismissed_at, notification_id),
            )
            return cursor.rowcount > 0

    def list_market_candles(
        self,
        *,
        provider: str | None = None,
        market_type: str | None = None,
        normalized_symbol: str | None = None,
        interval: str | None = None,
        start_at: str | None = None,
        end_at: str | None = None,
        limit: int | None = None,
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        for column, value in (
            ("provider", provider),
            ("market_type", market_type),
            ("normalized_symbol", normalized_symbol),
            ("interval", interval),
        ):
            if value:
                clauses.append(f"{column} = ?")
                params.append(value)
        if start_at:
            clauses.append("open_time >= ?")
            params.append(start_at)
        if end_at:
            clauses.append("open_time <= ?")
            params.append(end_at)
        query = "select * from market_candles"
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by open_time asc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def list_market_quotes(
        self,
        *,
        provider: str | None = None,
        market_type: str | None = None,
        normalized_symbol: str | None = None,
        end_at: str | None = None,
        limit: int | None = None,
    ) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        for column, value in (
            ("provider", provider),
            ("market_type", market_type),
            ("normalized_symbol", normalized_symbol),
        ):
            if value:
                clauses.append(f"{column} = ?")
                params.append(value)
        if end_at:
            clauses.append("captured_at <= ?")
            params.append(end_at)
        query = "select * from market_quotes"
        if clauses:
            query += " where " + " and ".join(clauses)
        query += " order by captured_at desc"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_recommendation_evaluation_run(self, run: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into recommendation_evaluation_runs (
                  evaluation_run_id, loop_run_id, status, config_json,
                  summary_json, payload_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run["evaluation_run_id"],
                    run.get("loop_run_id"),
                    run["status"],
                    json.dumps(run.get("config", {}), ensure_ascii=False, default=str),
                    json.dumps(run.get("summary", {}), ensure_ascii=False, default=str),
                    json.dumps(run, ensure_ascii=False, default=str),
                    run["created_at"],
                ),
            )

    def insert_recommendation_outcome(self, outcome: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert into recommendation_outcomes (
                  outcome_id, evaluation_run_id, decision_id, loop_run_id,
                  horizon_hours, status, action, confidence, provider, market_type,
                  symbol, normalized_symbol, decision_at, horizon_at, evaluated_at,
                  entry_price, entry_source, exit_price, exit_at, raw_return_pct,
                  directional_return_pct, mfe_pct, mae_pct, hit, target_hit,
                  invalidation_hit, ai_site_id, ai_model, prompt_version,
                  experiment_id, variant_id, payload_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(decision_id, horizon_hours) do update set
                  outcome_id=excluded.outcome_id,
                  evaluation_run_id=excluded.evaluation_run_id,
                  status=excluded.status,
                  evaluated_at=excluded.evaluated_at,
                  entry_price=excluded.entry_price,
                  entry_source=excluded.entry_source,
                  exit_price=excluded.exit_price,
                  exit_at=excluded.exit_at,
                  raw_return_pct=excluded.raw_return_pct,
                  directional_return_pct=excluded.directional_return_pct,
                  mfe_pct=excluded.mfe_pct,
                  mae_pct=excluded.mae_pct,
                  hit=excluded.hit,
                  target_hit=excluded.target_hit,
                  invalidation_hit=excluded.invalidation_hit,
                  ai_site_id=excluded.ai_site_id,
                  ai_model=excluded.ai_model,
                  prompt_version=excluded.prompt_version,
                  experiment_id=excluded.experiment_id,
                  variant_id=excluded.variant_id,
                  payload_json=excluded.payload_json
                """,
                (
                    outcome["outcome_id"], outcome["evaluation_run_id"], outcome["decision_id"],
                    outcome["loop_run_id"], float(outcome["horizon_hours"]), outcome["status"],
                    outcome["action"], float(outcome.get("confidence") or 0.0), outcome.get("provider"),
                    outcome.get("market_type"), outcome["symbol"], outcome.get("normalized_symbol"),
                    outcome["decision_at"], outcome["horizon_at"], outcome["evaluated_at"],
                    outcome.get("entry_price"), outcome.get("entry_source"), outcome.get("exit_price"),
                    outcome.get("exit_at"), outcome.get("raw_return_pct"),
                    outcome.get("directional_return_pct"), outcome.get("mfe_pct"), outcome.get("mae_pct"),
                    _sqlite_bool(outcome.get("hit")), _sqlite_bool(outcome.get("target_hit")),
                    _sqlite_bool(outcome.get("invalidation_hit")), outcome.get("ai_site_id"),
                    outcome.get("ai_model"), outcome.get("prompt_version"), outcome.get("experiment_id"),
                    outcome.get("variant_id"), json.dumps(outcome, ensure_ascii=False, default=str),
                ),
            )

    def list_recommendation_outcomes(self, limit: int | None = None, status: str | None = None) -> list[sqlite3.Row]:
        query = "select * from recommendation_outcomes"
        params: list[Any] = []
        if status:
            query += " where status = ?"
            params.append(status)
        query += " order by evaluated_at desc, decision_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def list_recommendation_evaluation_runs(self, limit: int = 20) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute(
                "select * from recommendation_evaluation_runs order by created_at desc limit ?",
                (max(1, limit),),
            ))

    def insert_portfolio_risk_report(self, report: dict[str, Any]) -> None:
        self._insert_p1_report("portfolio_risk_reports", "risk_report_id", report)

    def list_portfolio_risk_reports(self, limit: int = 20) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute(
                "select * from portfolio_risk_reports order by created_at desc limit ?",
                (max(1, limit),),
            ))

    def latest_portfolio_risk_report(self, loop_run_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from portfolio_risk_reports where loop_run_id = ? order by created_at desc limit 1",
                (loop_run_id,),
            ).fetchone()

    def upsert_ai_prompt_version(self, version: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or ignore into ai_prompt_versions (
                  prompt_version, task_id, role_id, system_prompt,
                  user_prompt_template, created_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
                (
                    version["prompt_version"], version["task_id"], version.get("role_id"),
                    version["system_prompt"], version["user_prompt_template"], version["created_at"],
                ),
            )

    def insert_ai_invocation(self, invocation: dict[str, Any]) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                insert or replace into ai_invocations (
                  invocation_id, loop_run_id, debate_id, message_id, task_id,
                  role_id, site_id, protocol, model, prompt_version, input_hash,
                  experiment_id, variant_id, status, input_tokens, output_tokens,
                  total_tokens, estimated_cost_usd, cost_status, duration_ms,
                  error, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    invocation["invocation_id"], invocation.get("loop_run_id"), invocation.get("debate_id"),
                    invocation.get("message_id"), invocation["task_id"], invocation.get("role_id"),
                    invocation.get("site_id"), invocation.get("protocol"), invocation.get("model"),
                    invocation.get("prompt_version"), invocation.get("input_hash"),
                    invocation.get("experiment_id"), invocation.get("variant_id"), invocation["status"],
                    invocation.get("input_tokens"), invocation.get("output_tokens"), invocation.get("total_tokens"),
                    invocation.get("estimated_cost_usd"), invocation.get("cost_status") or "unknown",
                    invocation.get("duration_ms"), invocation.get("error"), invocation["created_at"],
                ),
            )

    def list_ai_invocations(self, loop_run_id: str | None = None, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from ai_invocations"
        params: list[Any] = []
        if loop_run_id:
            query += " where loop_run_id = ?"
            params.append(loop_run_id)
        query += " order by created_at desc, invocation_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def replace_claim_evidence_audits(self, debate_id: str, audits: list[dict[str, Any]]) -> None:
        with self.connect() as conn:
            conn.execute("delete from claim_evidence_audits where debate_id = ?", (debate_id,))
            for audit in audits:
                conn.execute(
                    """
                    insert into claim_evidence_audits (
                      audit_id, loop_run_id, debate_id, message_id, role_id, phase_id,
                      claim_id, claim_source, claim_text, covered, derived,
                      evidence_refs_json, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        audit["audit_id"], audit.get("loop_run_id"), audit["debate_id"], audit["message_id"],
                        audit.get("role_id"), audit.get("phase_id"), audit["claim_id"], audit["claim_source"],
                        audit.get("claim_text"), 1 if audit.get("covered") else 0,
                        1 if audit.get("derived") else 0,
                        json.dumps(audit.get("evidence_refs", []), ensure_ascii=False, default=str),
                        audit["created_at"],
                    ),
                )

    def list_claim_evidence_audits(self, debate_id: str | None = None, limit: int | None = None) -> list[sqlite3.Row]:
        query = "select * from claim_evidence_audits"
        params: list[Any] = []
        if debate_id:
            query += " where debate_id = ?"
            params.append(debate_id)
        query += " order by created_at desc, message_id, claim_id"
        if limit:
            query += " limit ?"
            params.append(limit)
        with self.connect() as conn:
            return list(conn.execute(query, params))

    def insert_ai_governance_report(self, report: dict[str, Any]) -> None:
        self._insert_p1_report("ai_governance_reports", "governance_report_id", report)

    def list_ai_governance_reports(self, limit: int = 20) -> list[sqlite3.Row]:
        with self.connect() as conn:
            return list(conn.execute(
                "select * from ai_governance_reports order by created_at desc limit ?",
                (max(1, limit),),
            ))

    def latest_ai_governance_report(self, loop_run_id: str) -> sqlite3.Row | None:
        with self.connect() as conn:
            return conn.execute(
                "select * from ai_governance_reports where loop_run_id = ? order by created_at desc limit 1",
                (loop_run_id,),
            ).fetchone()

    def _insert_p1_report(self, table: str, id_column: str, report: dict[str, Any]) -> None:
        allowed = {
            ("portfolio_risk_reports", "risk_report_id"),
            ("ai_governance_reports", "governance_report_id"),
        }
        if (table, id_column) not in allowed:
            raise ValueError(f"Unsupported P1 report table: {table}")
        with self.connect() as conn:
            conn.execute(
                f"""
                insert or replace into {table} (
                  {id_column}, loop_run_id, status, config_json,
                  summary_json, payload_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    report[id_column], report["loop_run_id"], report["status"],
                    json.dumps(report.get("config", {}), ensure_ascii=False, default=str),
                    json.dumps(report.get("summary", {}), ensure_ascii=False, default=str),
                    json.dumps(report, ensure_ascii=False, default=str), report["created_at"],
                ),
            )

    def has_pipeline_lineage_rows(self, table: str, pipeline_run_id: str) -> bool:
        if table not in PIPELINE_LINEAGE_TABLES:
            raise ValueError(f"Unsupported pipeline lineage table: {table}")
        with self.connect() as conn:
            row = conn.execute(
                f"select 1 from {table} where pipeline_run_id = ? limit 1",
                (pipeline_run_id,),
            ).fetchone()
        return row is not None

    def prune_research_pipeline_artifacts(
        self,
        keep_runs: int | None = None,
        older_than_days: int | None = None,
    ) -> dict[str, Any]:
        keep_run_ids: set[str] = set()
        if keep_runs and keep_runs > 0:
            keep_run_ids = {row["run_id"] for row in self.list_research_pipeline_runs(limit=keep_runs)}
        cutoff: str | None = None
        if older_than_days and older_than_days > 0:
            cutoff = (datetime.now(timezone.utc) - timedelta(days=older_than_days)).isoformat()

        clauses: list[str] = []
        params: list[Any] = []
        if cutoff:
            clauses.append("created_at < ?")
            params.append(cutoff)
        if keep_run_ids:
            placeholders = ",".join("?" for _ in keep_run_ids)
            clauses.append(f"run_id not in ({placeholders})")
            params.extend(sorted(keep_run_ids))
        if not clauses:
            return {"deleted_artifacts": 0, "keep_runs": keep_runs, "older_than_days": older_than_days}

        where_sql = " and ".join(clauses)
        with self.connect() as conn:
            deleted = conn.execute(
                f"delete from research_pipeline_artifacts where {where_sql}",
                tuple(params),
            ).rowcount
        return {
            "deleted_artifacts": int(deleted or 0),
            "keep_runs": keep_runs,
            "older_than_days": older_than_days,
            "kept_run_ids": sorted(keep_run_ids),
            "cutoff": cutoff,
        }


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


def _sqlite_bool(value: Any) -> int | None:
    if value is None:
        return None
    return 1 if bool(value) else 0
