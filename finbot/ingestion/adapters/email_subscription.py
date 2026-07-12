from __future__ import annotations

from finbot.ingestion.adapters.base import BaseAdapter
from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig


class EmailSubscriptionAdapter(BaseAdapter):
    mode = "email_subscription_then_firecrawl_scrape"

    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        fallback_url = source.seed_urls[-1] if source.seed_urls else None
        if not fallback_url:
            return self.disabled_by_scope(source, "Email subscription ingestion is disabled for this phase")
        request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", {"url": fallback_url, "email": "disabled-by-scope"})
        try:
            async with self.client() as client:
                response = await client.get(fallback_url)
            response_path = self.evidence_store.save_text(source.id, f"{job.job_id}.response", response.text, ".html")
            headers_path = self.evidence_store.save_text(source.id, f"{job.job_id}.headers", str(dict(response.headers)))
            evidence = RawEvidence(
                source_id=source.id,
                job_id=job.job_id,
                url=fallback_url,
                status_code=response.status_code,
                success=response.is_success,
                request_path=request_path,
                response_path=response_path,
                headers_path=headers_path,
                metadata={"email_ingestion": "disabled-by-scope", "fallback": "seed_url_http"},
            )
            status = "disabled-by-scope" if response.is_success else "failed"
            detail = f"Email disabled by scope; fallback HTTP {response.status_code}"
            return AdapterResult(source_id=source.id, status=status, detail=detail, success=response.is_success, evidence=evidence)
        except Exception as exc:
            return self.failed(source, job, f"Email fallback failed: {exc!r}")
