from __future__ import annotations

import asyncio
import json
import tempfile
from typing import Any
from pathlib import Path

import httpx

from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.adapters.base import BaseAdapter
from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig
from finbot.network.proxy_runtime import ProxyRuntime
from finbot.network.proxy_router import ProxyRouteBlocked, ProxyRouteDecision, ProxyRouter


class FirecrawlAdapter(BaseAdapter):
    mode = "firecrawl"
    _PROXY_REQUIRED_DETAIL = (
        "Firecrawl proxy pool is required; configure FIRECRAWL_PROXY, "
        "FIRECRAWL_PROXY_POOL, or FIRECRAWL_PROXY_FILE. Direct Firecrawl "
        "requests are disabled by project policy."
    )

    def __init__(self, *args: Any, topic_watchlists: TopicWatchlists | None = None, **kwargs: Any):
        super().__init__(*args, **kwargs)
        self.topic_watchlists = topic_watchlists
        self.proxy_runtime = ProxyRuntime.from_settings(self.settings)
        self.proxy_router = self.proxy_runtime.router

    def close(self) -> None:
        self.proxy_runtime.close()

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass

    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        if not self.proxy_router.has_proxy("firecrawl"):
            return self.failed(source, job, self._PROXY_REQUIRED_DETAIL)
        if job.job_type == "firecrawl_scrape":
            return await self._scrape(job, source)
        if job.job_type == "firecrawl_search":
            return await self._search(job, source)
        if job.job_type == "firecrawl_search_then_scrape":
            return await self._search(job, source, scrape_first=True)
        if source.mode == "firecrawl_search":
            return await self._search(job, source)
        if source.mode == "firecrawl_search_then_scrape":
            return await self._search(job, source, scrape_first=True)
        return await self._scrape(job, source)

    async def _search(self, job: FetchJob, source: SourceConfig, scrape_first: bool = False) -> AdapterResult:
        query = job.query or self._first_query(source)
        if not query:
            return self.failed(source, job, "No search query configured")
        body = {
            "query": query,
            "limit": job.max_results or source.max_results or 5,
            "sources": ["web", "news"],
            "timeout": job.timeout_ms,
        }
        endpoint = f"{self.settings.firecrawl_api_base.rstrip('/')}/search"
        request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", {"url": endpoint, "body": body})
        try:
            response, proxy_decision = await self._post_firecrawl(endpoint, body)
            response_json = self._safe_json(response)
            response_path = self.evidence_store.save_json(source.id, f"{job.job_id}.response", response_json)
            headers_path = self.evidence_store.save_text(source.id, f"{job.job_id}.headers", str(dict(response.headers)))
            results = self._extract_results(response_json)
            evidence = RawEvidence(
                source_id=source.id,
                job_id=job.job_id,
                query=query,
                status_code=response.status_code,
                success=response.is_success and bool(results),
                request_path=request_path,
                response_path=response_path,
                headers_path=headers_path,
                metadata={
                    "result_count": len(results),
                    "auth_mode": self.settings.firecrawl_auth_mode,
                    "proxy_used": proxy_decision.proxy_redacted,
                    "proxy_pool_size": proxy_decision.proxy_pool_size,
                    "proxy_route": proxy_decision.to_dict(),
                },
            )
            detail = f"HTTP {response.status_code}, results={len(results)}"
            discovered_jobs = self._scrape_jobs_from_results(job, source, results) if scrape_first and results else []
            if scrape_first and results:
                detail += f", scrape candidates={len(discovered_jobs)}"
            status = "smoke-tested" if evidence.success else "blocked-by-provider"
            return AdapterResult(
                source_id=source.id,
                status=status,
                detail=detail,
                success=evidence.success,
                evidence=evidence,
                discovered_jobs=discovered_jobs,
            )
        except Exception as exc:
            return self.failed(source, job, f"Firecrawl search failed: {exc!r}")

    async def _scrape(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        url = job.url or (source.seed_urls[0] if source.seed_urls else None)
        if not url:
            return self.failed(source, job, "No seed URL configured")
        body = {
            "url": url,
            "formats": ["markdown"],
            "onlyMainContent": True,
            "maxAge": 0,
            "timeout": job.timeout_ms,
        }
        endpoint = f"{self.settings.firecrawl_api_base.rstrip('/')}/scrape"
        request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", {"url": endpoint, "body": body})
        try:
            response, proxy_decision = await self._post_firecrawl(endpoint, body)
            response_json = self._safe_json(response)
            response_path = self.evidence_store.save_json(source.id, f"{job.job_id}.response", response_json)
            headers_path = self.evidence_store.save_text(source.id, f"{job.job_id}.headers", str(dict(response.headers)))
            markdown = self._extract_markdown(response_json)
            markdown_path = None
            if markdown:
                markdown_path = self.evidence_store.save_text(source.id, f"{job.job_id}.markdown", markdown, ".md")
            success = response.is_success and len(markdown or "") > 80
            evidence = RawEvidence(
                source_id=source.id,
                job_id=job.job_id,
                url=url,
                status_code=response.status_code,
                success=success,
                request_path=request_path,
                response_path=response_path,
                headers_path=headers_path,
                markdown_path=markdown_path,
                metadata={
                    "markdown_length": len(markdown or ""),
                    "auth_mode": self.settings.firecrawl_auth_mode,
                    "proxy_used": proxy_decision.proxy_redacted,
                    "proxy_pool_size": proxy_decision.proxy_pool_size,
                    "proxy_route": proxy_decision.to_dict(),
                },
            )
            status = "smoke-tested" if success else "blocked-by-provider"
            return AdapterResult(source_id=source.id, status=status, detail=f"HTTP {response.status_code}, markdown={len(markdown or '')}", success=success, evidence=evidence)
        except Exception as exc:
            return self.failed(source, job, f"Firecrawl scrape failed: {exc!r}")

    async def _post_firecrawl(self, endpoint: str, body: dict[str, Any]) -> tuple[httpx.Response, ProxyRouteDecision]:
        if not self.proxy_router.has_proxy("firecrawl"):
            raise RuntimeError(self._PROXY_REQUIRED_DETAIL)
        headers = {"User-Agent": self.settings.http_user_agent}
        if self.settings.firecrawl_api_key and self.settings.firecrawl_auth_mode == "bearer":
            headers["Authorization"] = f"Bearer {self.settings.firecrawl_api_key}"
        last_error: Exception | None = None
        for proxy_decision in self.proxy_router.candidate_decisions("firecrawl", endpoint):
            if not proxy_decision.ok:
                last_error = ProxyRouteBlocked(proxy_decision)
                continue
            proxy = proxy_decision.proxy
            kwargs: dict[str, Any] = {"headers": headers, "timeout": self.timeout_seconds}
            kwargs["proxy"] = proxy
            try:
                async with httpx.AsyncClient(**kwargs) as client:
                    response = await client.post(endpoint, json=body)
                if response.status_code in {407, 429, 502, 503, 504} and self.proxy_router.has_proxy("firecrawl"):
                    last_error = RuntimeError(f"proxy candidate returned HTTP {response.status_code}: {proxy_decision.proxy_redacted}")
                    continue
                return response, proxy_decision
            except Exception as exc:
                if proxy and proxy.lower().startswith("socks"):
                    try:
                        response = await self._post_firecrawl_with_curl(endpoint, body, headers, proxy)
                        if response.status_code in {407, 429, 502, 503, 504} and self.proxy_router.has_proxy("firecrawl"):
                            last_error = RuntimeError(f"curl proxy candidate returned HTTP {response.status_code}: {proxy_decision.proxy_redacted}")
                            continue
                        return response, proxy_decision
                    except Exception as curl_exc:
                        last_error = curl_exc
                        continue
                last_error = exc
                continue
        raise RuntimeError(f"all Firecrawl proxy candidates failed: {last_error!r}")

    async def _post_firecrawl_with_curl(self, endpoint: str, body: dict[str, Any], headers: dict[str, str], proxy: str) -> httpx.Response:
        payload_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as temp:
                json.dump(body, temp, ensure_ascii=False)
                payload_path = Path(temp.name)
            args = [
                "curl.exe",
                "-sS",
                "-X",
                "POST",
                endpoint,
                "--proxy",
                proxy,
                "-H",
                "Content-Type: application/json",
                "-H",
                f"User-Agent: {headers.get('User-Agent', self.settings.http_user_agent)}",
                "--data-binary",
                f"@{payload_path}",
                "-w",
                "\n__FINBOT_HTTP_CODE__:%{http_code}",
            ]
            auth = headers.get("Authorization")
            if auth:
                args.extend(["-H", f"Authorization: {auth}"])
            proc = await asyncio.create_subprocess_exec(
                *args,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=self.timeout_seconds + 10)
            if proc.returncode != 0:
                raise RuntimeError(f"curl Firecrawl request failed: {stderr.decode('utf-8', errors='ignore')[:300]}")
            text = stdout.decode("utf-8", errors="ignore")
            marker = "\n__FINBOT_HTTP_CODE__:"
            if marker not in text:
                raise RuntimeError("curl Firecrawl response missing HTTP status marker")
            body_text, code_text = text.rsplit(marker, 1)
            status_code = int(code_text.strip() or "0")
            return httpx.Response(status_code=status_code, content=body_text.encode("utf-8"), headers={"x-finbot-transport": "curl"})
        finally:
            if payload_path and payload_path.exists():
                payload_path.unlink(missing_ok=True)

    def _first_query(self, source: SourceConfig) -> str | None:
        if source.search_queries:
            return source.search_queries[0]
        if self.topic_watchlists:
            queries = self.topic_watchlists.enabled_queries(limit=1)
            if queries:
                return queries[0]
        return None

    def _safe_json(self, response: httpx.Response) -> dict[str, Any]:
        try:
            return response.json()
        except Exception:
            return {"text": response.text}

    def _extract_results(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        data = payload.get("data")
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            for key in ("results", "web", "news"):
                value = data.get(key)
                if isinstance(value, list):
                    return value
        for key in ("results", "web", "news"):
            value = payload.get(key)
            if isinstance(value, list):
                return value
        return []

    def _extract_markdown(self, payload: dict[str, Any]) -> str:
        data = payload.get("data")
        if isinstance(data, dict):
            value = data.get("markdown")
            if isinstance(value, str):
                return value
        value = payload.get("markdown")
        return value if isinstance(value, str) else ""

    def _scrape_jobs_from_results(self, job: FetchJob, source: SourceConfig, results: list[dict[str, Any]]) -> list[FetchJob]:
        jobs: list[FetchJob] = []
        seen: set[str] = set()
        limit = job.max_scrape_targets or source.max_scrape_targets or 3
        for item in results:
            url = item.get("url") or item.get("link")
            if not url or url in seen:
                continue
            seen.add(url)
            jobs.append(
                FetchJob(
                    source_id=source.id,
                    mode=source.mode,
                    priority=job.priority,
                    asset_scope=job.asset_scope,
                    job_type="firecrawl_scrape",
                    url=url,
                    provider=source.provider,
                    timeout_ms=job.timeout_ms,
                    max_results=job.max_results,
                    max_scrape_targets=job.max_scrape_targets,
                )
            )
            if len(jobs) >= limit:
                break
        return jobs
