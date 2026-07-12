from __future__ import annotations

from finbot.config.settings import Settings
from finbot.config.topic_watchlist import TopicWatchlists
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

    def _adapter_for(self, source: SourceConfig, job: FetchJob | None = None):
        kwargs = {
            "settings": self.settings,
            "evidence_store": self.evidence_store,
            "timeout_seconds": self.timeout_seconds,
        }
        effective_mode = job.job_type if job and job.job_type in {"firecrawl_scrape", "firecrawl_search", "firecrawl_search_then_scrape"} else source.mode
        if effective_mode == "exchange_public_api":
            return ExchangePublicAPIAdapter(**kwargs)
        if effective_mode == "structured_api":
            return StructuredAPIAdapter(**kwargs)
        if effective_mode in {"rss", "rss_then_firecrawl_scrape"}:
            return RSSAdapter(**kwargs)
        if effective_mode in {"firecrawl_scrape", "firecrawl_search", "firecrawl_search_then_scrape"}:
            return FirecrawlAdapter(**kwargs, topic_watchlists=self.topic_watchlists)
        if effective_mode == "provider_api":
            return ProviderAPIAdapter(**kwargs)
        if effective_mode == "email_subscription_then_firecrawl_scrape":
            return EmailSubscriptionAdapter(**kwargs)
        if effective_mode == "social_fetch":
            return SocialAdapter(**kwargs)
        return None
