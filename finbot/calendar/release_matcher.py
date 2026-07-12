from __future__ import annotations

import json
from typing import Any

from finbot.calendar.official_release_registry import OfficialRelease
from finbot.storage.sqlite_store import SQLiteStore


class OfficialReleaseMatcher:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def upsert_and_match(self, releases: list[OfficialRelease]) -> dict[str, Any]:
        self.store.init_schema()
        for release in releases:
            self.store.upsert_official_release(release)

        documents = self.store.list_normalized_documents()
        matches = []
        for release in releases:
            release_matches = self._match_release(release, documents)
            strict_matches = [item for item in release_matches if item["match_level"] == "strict"]
            status = "matched" if strict_matches else release.status
            self.store.update_official_release_status(
                release.release_id,
                status=status,
                metadata={
                    **release.metadata,
                    "matched_document_ids": [item["document_id"] for item in strict_matches],
                    "candidate_document_ids": [item["document_id"] for item in release_matches],
                },
            )
            matches.append(
                {
                    "release_id": release.release_id,
                    "provider": release.provider,
                    "release_type": release.release_type,
                    "title": release.title,
                    "status": status,
                    "strict_match_count": len(strict_matches),
                    "matched_documents": release_matches,
                }
            )
        return {
            "total_releases": len(releases),
            "matched_releases": sum(1 for item in matches if item["strict_match_count"] > 0),
            "candidate_releases": sum(1 for item in matches if item["matched_documents"]),
            "matches": matches,
        }

    def _match_release(self, release: OfficialRelease, documents) -> list[dict[str, Any]]:
        terms = [term.lower() for term in release.match_terms or [release.title]]
        configured_source_ids = set(release.source_ids)
        results: list[dict[str, Any]] = []
        for row in documents:
            title = (row["title"] or "").lower()
            text = (row["text"] or "")[:1600].lower()
            source_id = row["source_id"] or ""
            haystack = f"{title}\n{text}"
            term_match = any(term in haystack for term in terms)
            source_match = source_id in configured_source_ids if configured_source_ids else False
            if not term_match:
                continue
            match_level = "strict" if source_match or not configured_source_ids else "candidate"
            results.append(
                {
                    "document_id": row["document_id"],
                    "source_id": source_id,
                    "title": row["title"],
                    "fetched_at": row["fetched_at"],
                    "match_terms": [term for term in terms if term in haystack],
                    "source_match": source_match,
                    "match_level": match_level,
                }
            )
        return results[:10]


def release_row_to_dict(row) -> dict[str, Any]:
    return {
        "release_id": row["release_id"],
        "provider": row["provider"],
        "release_type": row["release_type"],
        "title": row["title"],
        "scheduled_at": row["scheduled_at"],
        "timezone": row["timezone"],
        "asset_scope": _loads(row["asset_scope_json"], []),
        "expected_fields": _loads(row["expected_fields_json"], []),
        "source_url": row["source_url"],
        "status": row["status"],
        "metadata": _loads(row["metadata_json"], {}),
    }


def _loads(value: str | None, default):
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default
