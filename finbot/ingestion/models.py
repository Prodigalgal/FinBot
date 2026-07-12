from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, Field


STATUS_CONFIGURED = "configured"
STATUS_ADAPTER_READY = "adapter-ready"
STATUS_SMOKE_TESTED = "smoke-tested"
STATUS_BLOCKED_BY_CREDENTIAL = "blocked-by-credential"
STATUS_BLOCKED_BY_PROVIDER = "blocked-by-provider"
STATUS_DISABLED_BY_SCOPE = "disabled-by-scope"
STATUS_DISABLED = "disabled"
STATUS_FAILED = "failed"


class SourceConfig(BaseModel):
    id: str
    enabled: bool = True
    tier: str
    category: str
    mode: str
    provider: str | None = None
    trust_weight: float = 0.5
    poll_interval: str = "30m"
    priority: str = "P2"
    asset_scope: list[str] = Field(default_factory=list)
    feed_urls: list[str] = Field(default_factory=list)
    seed_urls: list[str] = Field(default_factory=list)
    search_queries: list[str] = Field(default_factory=list)
    data_types: list[str] = Field(default_factory=list)
    data_series: list[str] = Field(default_factory=list)
    datasets: list[str] = Field(default_factory=list)
    notes: str | None = None
    max_results: int | None = None
    max_scrape_targets: int | None = None
    crawl_policy: dict[str, Any] = Field(default_factory=dict)
    budget: dict[str, Any] = Field(default_factory=dict)


class FetchJob(BaseModel):
    job_id: str = Field(default_factory=lambda: uuid4().hex)
    source_id: str
    mode: str
    priority: str
    asset_scope: list[str] = Field(default_factory=list)
    job_type: str
    url: str | None = None
    query: str | None = None
    provider: str | None = None
    scheduled_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    timeout_ms: int = 60000
    max_results: int | None = None
    max_scrape_targets: int | None = None


class RawEvidence(BaseModel):
    evidence_id: str = Field(default_factory=lambda: uuid4().hex)
    source_id: str
    job_id: str
    fetched_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    url: str | None = None
    query: str | None = None
    status_code: int | None = None
    success: bool = False
    request_path: str | None = None
    response_path: str | None = None
    headers_path: str | None = None
    markdown_path: str | None = None
    error: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class AdapterResult(BaseModel):
    source_id: str
    status: str
    detail: str
    success: bool = False
    evidence: RawEvidence | None = None
    discovered_jobs: list[FetchJob] = Field(default_factory=list)
    required_keys: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class SmokeReport(BaseModel):
    started_at: datetime
    ended_at: datetime | None = None
    total_sources: int = 0
    statuses: dict[str, int] = Field(default_factory=dict)
    results: list[AdapterResult] = Field(default_factory=list)
    required_keys: dict[str, list[str]] = Field(default_factory=dict)


class NormalizedDocument(BaseModel):
    document_id: str = Field(default_factory=lambda: uuid4().hex)
    evidence_id: str
    source_id: str
    tier: str | None = None
    category: str | None = None
    trust_weight: float = 0.0
    canonical_url: str | None = None
    title: str | None = None
    published_at: datetime | None = None
    fetched_at: datetime
    language: str | None = None
    text: str
    content_hash: str
    title_key: str
    asset_scope: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class URLCandidate(BaseModel):
    candidate_id: str = Field(default_factory=lambda: uuid4().hex)
    source_id: str
    evidence_id: str
    url: str
    canonical_url: str
    title: str | None = None
    snippet: str | None = None
    score: float = 0.0
    metadata: dict[str, Any] = Field(default_factory=dict)


class EventCandidate(BaseModel):
    event_id: str = Field(default_factory=lambda: uuid4().hex)
    event_key: str
    title: str
    category: str | None = None
    asset_scope: list[str] = Field(default_factory=list)
    document_ids: list[str] = Field(default_factory=list)
    source_ids: list[str] = Field(default_factory=list)
    confidence: float = 0.0
    first_seen_at: datetime
    last_seen_at: datetime
    summary: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
