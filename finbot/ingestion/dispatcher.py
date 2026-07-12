from __future__ import annotations

from finbot.config.settings import Settings
from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.adapters.base import BaseAdapter
from finbot.ingestion.adapters.email_subscription import EmailSubscriptionAdapter
from finbot.ingestion.adapters.exchange_public_api import ExchangePublicAPIAdapter
from finbot.ingestion.adapters.firecrawl import FirecrawlAdapter
from finbot.ingestion.adapters.provider_api import ProviderAPIAdapter
from finbot.ingestion.adapters.rss import RSSAdapter
from finbot.ingestion.adapters.social import SocialAdapter
from finbot.ingestion.adapters.structured_api import StructuredAPIAdapter
from finbot.ingestion.models import AdapterResult, FetchJob, SourceConfig
from finbot.storage.evidence_store import EvidenceStore


class Dispatcher:
    def __init__(self, settings: Settings, evidence_store: EvidenceStore, topic_watchlists: TopicWatchlists | None = None, timeout_seconds: float = 30):
        self.settings = settings
        self.evidence_store = evidence_store
        self.topic_watchlists = topic_watchlists
        self.timeout_seconds = timeout_seconds
        self._adapters: dict[str, BaseAdapter] = {}
        self._closed = False

    async def dispatch(self, source: SourceConfig, force_disabled: bool = False) -> AdapterResult:
        if not source.enabled and not force_disabled:
            return AdapterResult(source_id=source.id, status="disabled", detail="Source disabled in catalog", success=False)
        job = self._job_for(source)
        return await self.dispatch_job(source, job, force_disabled=force_disabled)

    async def dispatch_job(self, source: SourceConfig, job: FetchJob, force_disabled: bool = False) -> AdapterResult:
        if not source.enabled and not force_disabled:
            return AdapterResult(source_id=source.id, status="disabled", detail="Source disabled in catalog", success=False)
        adapter = self._adapter_for(source, job)
        if not adapter:
            return AdapterResult(source_id=source.id, status="blocked-by-provider", detail=f"No adapter for mode: {source.mode}", success=False)
        return await adapter.fetch(job, source)

    def _job_for(self, source: SourceConfig) -> FetchJob:
        return FetchJob(
            source_id=source.id,
            mode=source.mode,
            priority=source.priority,
            asset_scope=source.asset_scope,
            job_type=source.mode,
            url=source.seed_urls[0] if source.seed_urls else None,
            query=source.search_queries[0] if source.search_queries else None,
            provider=source.provider,
            max_results=source.max_results,
            max_scrape_targets=source.max_scrape_targets,
        )

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        adapters = list(self._adapters.values())
        self._adapters.clear()
        errors: list[Exception] = []
        for adapter in adapters:
            close = getattr(adapter, "close", None)
            if not callable(close):
                continue
            try:
                close()
            except Exception as exc:
                errors.append(exc)
        if errors:
            raise RuntimeError(f"Failed to close {len(errors)} ingestion adapter(s)") from errors[0]

    def __enter__(self) -> "Dispatcher":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.close()

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass

    def _adapter_for(self, source: SourceConfig, job: FetchJob | None = None) -> BaseAdapter | None:
        if self._closed:
            raise RuntimeError("Dispatcher is closed")
        kwargs = {
            "settings": self.settings,
            "evidence_store": self.evidence_store,
            "timeout_seconds": self.timeout_seconds,
        }
        effective_mode = job.job_type if job and job.job_type in {"firecrawl_scrape", "firecrawl_search", "firecrawl_search_then_scrape"} else source.mode
        adapter_key = "firecrawl" if effective_mode in {"firecrawl_scrape", "firecrawl_search", "firecrawl_search_then_scrape"} else effective_mode
        cached = self._adapters.get(adapter_key)
        if cached is not None:
            return cached
        if effective_mode == "exchange_public_api":
            adapter = ExchangePublicAPIAdapter(**kwargs)
        elif effective_mode == "structured_api":
            adapter = StructuredAPIAdapter(**kwargs)
        elif effective_mode in {"rss", "rss_then_firecrawl_scrape"}:
            adapter = RSSAdapter(**kwargs)
        elif effective_mode in {"firecrawl_scrape", "firecrawl_search", "firecrawl_search_then_scrape"}:
            adapter = FirecrawlAdapter(**kwargs, topic_watchlists=self.topic_watchlists)
        elif effective_mode == "provider_api":
            adapter = ProviderAPIAdapter(**kwargs)
        elif effective_mode == "email_subscription_then_firecrawl_scrape":
            adapter = EmailSubscriptionAdapter(**kwargs)
        elif effective_mode == "social_fetch":
            adapter = SocialAdapter(**kwargs)
        else:
            return None
        self._adapters[adapter_key] = adapter
        return adapter
