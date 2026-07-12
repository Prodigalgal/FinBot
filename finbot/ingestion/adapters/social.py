from __future__ import annotations

from finbot.ingestion.adapters.base import BaseAdapter
from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig


class SocialAdapter(BaseAdapter):
    mode = "social_fetch"

    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        provider = source.provider
        if provider == "stocktwits":
            url = "https://api.stocktwits.com/api/2/streams/symbol/BTC.X.json"
            request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", {"url": url})
            try:
                async with self.client() as client:
                    response = await client.get(url)
                try:
                    payload = response.json() if response.text else {}
                except Exception:
                    payload = {"text": response.text[:2000], "json_error": True}
                response_path = self.evidence_store.save_json(source.id, f"{job.job_id}.response", payload)
                headers_path = self.evidence_store.save_text(source.id, f"{job.job_id}.headers", str(dict(response.headers)))
                evidence = RawEvidence(
                    source_id=source.id,
                    job_id=job.job_id,
                    url=url,
                    status_code=response.status_code,
                    success=response.is_success,
                    request_path=request_path,
                    response_path=response_path,
                    headers_path=headers_path,
                    metadata={"provider": provider},
                )
                if response.is_success and not payload.get("json_error"):
                    status = "smoke-tested"
                elif response.status_code in {401, 403, 404, 429}:
                    status = "blocked-by-provider"
                else:
                    status = "failed"
                return AdapterResult(source_id=source.id, status=status, detail=f"HTTP {response.status_code}", success=response.is_success and status == "smoke-tested", evidence=evidence)
            except Exception as exc:
                return self.failed(source, job, f"StockTwits fetch failed: {exc!r}")
        return AdapterResult(source_id=source.id, status="blocked-by-provider", detail=f"Unsupported social provider: {provider}")
