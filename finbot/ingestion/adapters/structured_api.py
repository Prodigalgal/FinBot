from __future__ import annotations

from urllib.parse import urlencode

from finbot.ingestion.adapters.base import BaseAdapter
from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig


class StructuredAPIAdapter(BaseAdapter):
    mode = "structured_api"

    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        provider = source.provider
        if provider == "fred":
            if not self.settings.fred_api_key:
                return self.blocked_by_credential(source, ["FRED_API_KEY"])
            return await self._get_json(job, source, "https://api.stlouisfed.org/fred/series/observations?" + urlencode({
                "series_id": "DGS10",
                "api_key": self.settings.fred_api_key,
                "file_type": "json",
                "limit": "1",
                "sort_order": "desc",
            }))
        if provider == "bea":
            if not self.settings.bea_api_key:
                return self.blocked_by_credential(source, ["BEA_API_KEY"])
            return await self._get_json(job, source, "https://apps.bea.gov/api/data?" + urlencode({
                "UserID": self.settings.bea_api_key,
                "method": "GETDATASETLIST",
                "ResultFormat": "JSON",
            }))
        if provider == "bls":
            return await self._get_json(job, source, "https://api.bls.gov/publicAPI/v2/timeseries/data/CUUR0000SA0?latest=true")
        if provider == "sec_edgar":
            return AdapterResult(
                source_id=source.id,
                status="adapter-ready",
                detail="SEC adapter ready; enable after CIK list is configured",
                success=False,
            )
        return AdapterResult(source_id=source.id, status="blocked-by-provider", detail=f"Unsupported structured provider: {provider}")

    async def _get_json(self, job: FetchJob, source: SourceConfig, url: str) -> AdapterResult:
        request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", {"url": self._redact(url)})
        try:
            async with self.client() as client:
                response = await client.get(url)
            payload = response.json() if response.text else {}
            response_path = self.evidence_store.save_json(source.id, f"{job.job_id}.response", payload)
            headers_path = self.evidence_store.save_text(source.id, f"{job.job_id}.headers", str(dict(response.headers)))
            evidence = RawEvidence(
                source_id=source.id,
                job_id=job.job_id,
                url=self._redact(url),
                status_code=response.status_code,
                success=response.is_success,
                request_path=request_path,
                response_path=response_path,
                headers_path=headers_path,
                metadata={"provider": source.provider},
            )
            return AdapterResult(source_id=source.id, status="smoke-tested" if response.is_success else "failed", detail=f"HTTP {response.status_code}", success=response.is_success, evidence=evidence)
        except Exception as exc:
            return self.failed(source, job, f"Structured API failed: {exc!r}")

    def _redact(self, url: str) -> str:
        for key in ("api_key=", "UserID="):
            if key in url:
                head, tail = url.split(key, 1)
                if "&" in tail:
                    tail = "<redacted>&" + tail.split("&", 1)[1]
                else:
                    tail = "<redacted>"
                return head + key + tail
        return url
