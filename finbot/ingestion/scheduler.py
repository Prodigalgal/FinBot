from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.models import FetchJob, SourceConfig


def parse_interval(value: str) -> timedelta:
    match = re.fullmatch(r"\s*(\d+)\s*([smhd])\s*", value or "")
    if not match:
        return timedelta(minutes=30)
    amount = int(match.group(1))
    unit = match.group(2)
    if unit == "s":
        return timedelta(seconds=amount)
    if unit == "m":
        return timedelta(minutes=amount)
    if unit == "h":
        return timedelta(hours=amount)
    if unit == "d":
        return timedelta(days=amount)
    return timedelta(minutes=30)


class SourceScheduler:
    """Builds fetch jobs from source catalog configuration."""

    def __init__(self, topics: TopicWatchlists | None = None, focus_queries: tuple[str, ...] = ()):
        self.topics = topics
        self.focus_queries = tuple(query.strip() for query in focus_queries if query.strip())

    def jobs_for_source(self, source: SourceConfig, now: datetime | None = None) -> list[FetchJob]:
        scheduled_at = now or datetime.now(timezone.utc)
        if self.focus_queries and source.mode in {"firecrawl_search", "firecrawl_search_then_scrape"}:
            limit = max(1, source.max_results or len(self.focus_queries))
            return [
                self._job(source, scheduled_at, query=query, job_type=source.mode)
                for query in self.focus_queries[:limit]
            ]
        if source.mode == "firecrawl_search" and not source.search_queries:
            return self._topic_search_jobs(source, scheduled_at)
        if source.mode == "firecrawl_search_then_scrape":
            queries = source.search_queries or (self.topics.enabled_queries(limit=3) if self.topics else [])
            return [
                self._job(source, scheduled_at, query=query, job_type=source.mode)
                for query in queries[: max(1, source.max_results or 3)]
            ]
        if source.mode == "provider_api" and source.search_queries:
            limit = max(1, source.max_results or len(source.search_queries))
            return [self._job(source, scheduled_at, query=query, job_type=source.mode) for query in source.search_queries[:limit]]
        if source.feed_urls and source.mode in {"rss", "rss_then_firecrawl_scrape"}:
            return [self._job(source, scheduled_at, url=url, job_type=source.mode) for url in source.feed_urls]
        if source.seed_urls:
            limit = max(1, source.max_results or len(source.seed_urls))
            return [self._job(source, scheduled_at, url=url, job_type=source.mode) for url in source.seed_urls[:limit]]
        return [self._job(source, scheduled_at, job_type=source.mode)]

    def due_sources(self, sources: list[SourceConfig], last_checked: dict[str, datetime], now: datetime | None = None) -> list[SourceConfig]:
        current = now or datetime.now(timezone.utc)
        due: list[SourceConfig] = []
        for source in sources:
            previous = last_checked.get(source.id)
            if previous is None or current - previous >= parse_interval(source.poll_interval):
                due.append(source)
        return sorted(due, key=lambda s: s.priority)

    def _topic_search_jobs(self, source: SourceConfig, scheduled_at: datetime) -> list[FetchJob]:
        queries = self.topics.enabled_queries(limit=source.max_results or 5) if self.topics else []
        return [self._job(source, scheduled_at, query=query, job_type=source.mode) for query in queries]

    def _job(
        self,
        source: SourceConfig,
        scheduled_at: datetime,
        job_type: str,
        url: str | None = None,
        query: str | None = None,
    ) -> FetchJob:
        return FetchJob(
            source_id=source.id,
            mode=source.mode,
            priority=source.priority,
            asset_scope=source.asset_scope,
            job_type=job_type,
            url=url,
            query=query,
            provider=source.provider,
            scheduled_at=scheduled_at,
            max_results=source.max_results,
            max_scrape_targets=source.max_scrape_targets,
        )
