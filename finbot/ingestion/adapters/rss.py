from __future__ import annotations

import xml.etree.ElementTree as ET

from finbot.ingestion.models import AdapterResult, FetchJob, RawEvidence, SourceConfig
from finbot.ingestion.adapters.base import BaseAdapter


class RSSAdapter(BaseAdapter):
    mode = "rss"

    async def fetch(self, job: FetchJob, source: SourceConfig) -> AdapterResult:
        urls = source.feed_urls or source.seed_urls
        if not urls:
            return self.failed(source, job, "No feed_urls or seed_urls configured")

        url = urls[0]
        request_path = self.evidence_store.save_json(source.id, f"{job.job_id}.request", {"url": url, "mode": source.mode})
        try:
            async with self.client() as client:
                response = await client.get(url)
            headers_path = self.evidence_store.save_text(source.id, f"{job.job_id}.headers", str(dict(response.headers)))
            response_path = self.evidence_store.save_text(source.id, f"{job.job_id}.response", response.text, ".xml")
            item_count = self._count_items(response.text)
            evidence = RawEvidence(
                source_id=source.id,
                job_id=job.job_id,
                url=url,
                status_code=response.status_code,
                success=response.is_success,
                request_path=request_path,
                response_path=response_path,
                headers_path=headers_path,
                metadata={"item_count": item_count, "content_type": response.headers.get("content-type")},
            )
            status = "smoke-tested" if response.is_success else ("blocked-by-provider" if response.status_code in {401, 403, 429} else "failed")
            detail = f"HTTP {response.status_code}, items={item_count}"
            return AdapterResult(source_id=source.id, status=status, detail=detail, success=response.is_success, evidence=evidence)
        except Exception as exc:
            return self.failed(source, job, f"RSS fetch failed: {exc!r}")

    def _count_items(self, text: str) -> int:
        try:
            root = ET.fromstring(text.encode("utf-8"))
        except Exception:
            return 0
        return len(root.findall(".//item")) + len(root.findall(".//{http://www.w3.org/2005/Atom}entry"))
