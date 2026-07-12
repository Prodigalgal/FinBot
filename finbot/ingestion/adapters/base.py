from __future__ import annotations

from abc import ABC, abstractmethod

import httpx

from finbot.config.settings import Settings
from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig
from finbot.storage.evidence_store import EvidenceStore


class BaseAdapter(ABC):
    mode = "base"

    def __init__(self, settings: Settings, evidence_store: EvidenceStore, timeout_seconds: float = 30):
        self.settings = settings
        self.evidence_store = evidence_store
        self.timeout_seconds = timeout_seconds

    @abstractmethod
    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        raise NotImplementedError

    def blocked_by_credential(self, source: SourceConfig, keys: list[str], detail: str | None = None) -> AdapterResult:
        return AdapterResult(
            source_id=source.id,
            status="blocked-by-credential",
            detail=detail or f"Missing required credential(s): {', '.join(keys)}",
            success=False,
            required_keys=keys,
        )

    def disabled_by_scope(self, source: SourceConfig, detail: str) -> AdapterResult:
        return AdapterResult(
            source_id=source.id,
            status="disabled-by-scope",
            detail=detail,
            success=False,
        )

    def blocked_by_provider(self, source: SourceConfig, detail: str) -> AdapterResult:
        return AdapterResult(
            source_id=source.id,
            status="blocked-by-provider",
            detail=detail,
            success=False,
        )

    def failed(self, source: SourceConfig, job: FetchJob, error: str) -> AdapterResult:
        if not error:
            error = "Unknown error"
        evidence = RawEvidence(
            source_id=source.id,
            job_id=job.job_id,
            url=job.url,
            query=job.query,
            success=False,
            error=error,
        )
        return AdapterResult(
            source_id=source.id,
            status="failed",
            detail=error,
            success=False,
            evidence=evidence,
        )

    def client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            timeout=self.timeout_seconds,
            headers={"User-Agent": self.settings.http_user_agent},
            follow_redirects=True,
        )
