from __future__ import annotations

from urllib.parse import quote_plus, urlencode

import httpx

from finbot.ingestion.adapters.base import BaseAdapter
from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig


class ProviderAPIAdapter(BaseAdapter):
    mode = "provider_api"

    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        provider = source.provider
        if provider == "alpha_vantage":
            if not self.settings.alpha_vantage_api_key:
                return self.blocked_by_credential(source, ["ALPHA_VANTAGE_API_KEY"])
            url = "https://www.alphavantage.co/query?" + urlencode({
                "function": "NEWS_SENTIMENT",
                "tickers": "AAPL,MSFT,BTC",
                "apikey": self.settings.alpha_vantage_api_key,
                "limit": "10",
            })
            return await self._get_json(job, source, url, redact=True)
        if provider == "gdelt_doc":
            query = job.query or "oil"
            url = "https://api.gdeltproject.org/api/v2/doc/doc?" + urlencode({
                "query": query,
                "mode": "artlist",
                "format": "json",
                "maxrecords": str(job.max_results or source.max_results or 5),
                "timespan": "1d",
            })
            return await self._get_json(job, source, url, discover_firecrawl_targets=True)
        if provider == "yfinance":
            query = quote_plus(" ".join(source.asset_scope[:3] or ["market news"]))
            url = f"https://query1.finance.yahoo.com/v1/finance/search?q={query}&newsCount=10"
            return await self._get_json(job, source, url)
        if provider == "openbb":
            if not self.settings.openbb_pat:
                return self.blocked_by_credential(source, ["OPENBB_PAT"], "OpenBB adapter code path ready; OPENBB_PAT missing")
            return AdapterResult(source_id=source.id, status="adapter-ready", detail="OpenBB PAT present; SDK integration is next step", success=False)
        return AdapterResult(source_id=source.id, status="blocked-by-provider", detail=f"Unsupported provider API: {provider}")

    async def _get_json(self, job: FetchJob, source: SourceConfig, url: str, redact: bool = False, discover_firecrawl_targets: bool = False) -> AdapterResult:
        request_url = self._redact(url) if redact else url
        request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", {"url": request_url})
        try:
            async with self.client() as client:
                response = await client.get(url)
            payload, response_path = self._save_response(source.id, job.job_id, response)
            headers_path = self.evidence_store.save_text(source.id, f"{job.job_id}.headers", str(dict(response.headers)))
            json_error = bool(payload.get("json_error")) if isinstance(payload, dict) else False
            success = response.is_success and not json_error
            if response.status_code == 429:
                status = "blocked-by-provider"
                detail = "HTTP 429 rate-limited"
            elif json_error:
                status = "blocked-by-provider"
                detail = f"HTTP {response.status_code}, non-json response"
            elif response.is_success:
                status = "smoke-tested"
                detail = f"HTTP {response.status_code}"
            else:
                status = "blocked-by-provider" if response.status_code in {401, 403, 404, 429} else "failed"
                detail = f"HTTP {response.status_code}"
            discovered_jobs = self._firecrawl_jobs_from_articles(payload, job, source) if success and discover_firecrawl_targets else []
            evidence = RawEvidence(
                source_id=source.id,
                job_id=job.job_id,
                url=request_url,
                status_code=response.status_code,
                success=success,
                request_path=request_path,
                response_path=response_path,
                headers_path=headers_path,
                metadata={"provider": source.provider, "discovered_jobs": len(discovered_jobs)},
            )
            if discovered_jobs:
                detail = f"{detail}, scrape candidates={len(discovered_jobs)}"
            return AdapterResult(source_id=source.id, status=status, detail=detail, success=success, evidence=evidence, discovered_jobs=discovered_jobs)
        except Exception as exc:
            if isinstance(exc, httpx.TimeoutException):
                return self.blocked_by_provider(source, f"Provider timeout: {exc!r}")
            return self.failed(source, job, f"Provider API failed: {exc!r}")

    def _save_response(self, source_id: str, job_id: str, response) -> tuple[dict, str]:
        try:
            payload = response.json() if response.text else {}
            path = self.evidence_store.save_json(source_id, f"{job_id}.response", payload)
            return payload, path
        except Exception:
            payload = {"text": response.text[:2000], "json_error": True}
            path = self.evidence_store.save_json(source_id, f"{job_id}.response", payload)
            return payload, path

    def _redact(self, url: str) -> str:
        if "apikey=" not in url:
            return url
        head, tail = url.split("apikey=", 1)
        if "&" in tail:
            tail = "<redacted>&" + tail.split("&", 1)[1]
        else:
            tail = "<redacted>"
        return head + "apikey=" + tail

    def _firecrawl_jobs_from_articles(self, payload: dict, job: FetchJob, source: SourceConfig) -> list[FetchJob]:
        articles = payload.get("articles")
        if not isinstance(articles, list):
            return []
        limit = job.max_scrape_targets or source.max_scrape_targets or 3
        jobs: list[FetchJob] = []
        seen: set[str] = set()
        for article in articles:
            if not isinstance(article, dict):
                continue
            url = article.get("url")
            if not isinstance(url, str) or not url.startswith(("http://", "https://")) or url in seen:
                continue
            seen.add(url)
            jobs.append(
                FetchJob(
                    source_id="search_firecrawl_global",
                    mode="firecrawl_scrape",
                    priority=source.priority,
                    asset_scope=source.asset_scope,
                    job_type="firecrawl_scrape",
                    url=url,
                    provider="firecrawl",
                    timeout_ms=job.timeout_ms,
                    max_results=job.max_results,
                    max_scrape_targets=job.max_scrape_targets,
                )
            )
            if len(jobs) >= limit:
                break
        return jobs
