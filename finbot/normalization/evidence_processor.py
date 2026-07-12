from __future__ import annotations

import json
import re
import sqlite3
import xml.etree.ElementTree as ET
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from finbot.ingestion.models import EventCandidate, NormalizedDocument, URLCandidate
from finbot.normalization.dedupe import content_hash, normalize_title
from finbot.normalization.url_normalizer import canonicalize_url
from finbot.quality.event_quality import EventQualityEvaluator
from finbot.storage.sqlite_store import SQLiteStore


TITLE_RE = re.compile(r"^\s*#\s+(.+?)\s*$", re.MULTILINE)
HTML_TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)
EVENT_ANCHOR_TERMS = [
    "fomc",
    "fed",
    "rate",
    "inflation",
    "cpi",
    "ppi",
    "pce",
    "payrolls",
    "gdp",
    "yields",
    "dollar",
    "oil",
    "crude",
    "brent",
    "wti",
    "opec",
    "eia",
    "inventory",
    "sanctions",
    "tanker",
    "iran",
    "israel",
    "tariff",
    "war",
    "ceasefire",
    "gold",
    "bitcoin",
    "ethereum",
    "crypto",
    "etf",
    "sec",
    "hack",
    "liquidation",
    "nasdaq",
    "nvidia",
    "apple",
    "microsoft",
    "earnings",
    "ai",
    "chips",
]
EVENT_STOPWORDS = {
    "about",
    "after",
    "again",
    "against",
    "also",
    "amid",
    "and",
    "are",
    "from",
    "has",
    "have",
    "into",
    "latest",
    "market",
    "markets",
    "news",
    "not",
    "over",
    "press",
    "price",
    "prices",
    "release",
    "releases",
    "report",
    "says",
    "the",
    "this",
    "today",
    "with",
}


class EvidenceProcessor:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def process_all(self, limit: int | None = None) -> dict[str, int]:
        self.store.init_schema()
        self.store.clear_derived_data()
        source_map = self.store.source_map()
        counts = {"evidence": 0, "url_candidates": 0, "normalized_inserted": 0, "normalized_duplicate": 0, "events": 0}
        for row in self.store.list_raw_evidence(only_success=True, limit=limit):
            counts["evidence"] += 1
            source = source_map.get(row["source_id"])
            for candidate in self._extract_url_candidates(row):
                self.store.upsert_url_candidate(candidate)
                counts["url_candidates"] += 1
            document = self._normalize_evidence(row, source)
            if not document:
                continue
            inserted = self.store.upsert_normalized_document(document)
            if inserted:
                counts["normalized_inserted"] += 1
            else:
                counts["normalized_duplicate"] += 1
        events = self.build_events()
        counts["events"] = len(events)
        return counts

    def build_events(self) -> list[EventCandidate]:
        rows = self.store.list_normalized_documents()
        market_rows = [row for row in rows if row["category"] == "market_data"]
        quality_evaluator = EventQualityEvaluator(market_rows=market_rows)
        groups: dict[str, list[sqlite3.Row]] = defaultdict(list)
        for row in rows:
            if self._skip_event_category(row["category"]):
                continue
            key = self._event_key(row)
            groups[key].append(row)

        events: list[EventCandidate] = []
        for key, docs in groups.items():
            docs_sorted = sorted(docs, key=lambda r: r["fetched_at"])
            first = self._parse_dt(docs_sorted[0]["fetched_at"])
            last = self._parse_dt(docs_sorted[-1]["fetched_at"])
            source_ids = sorted({row["source_id"] for row in docs_sorted})
            asset_scope = sorted({asset for row in docs_sorted for asset in self._loads(row["asset_scope_json"], [])})
            quality = quality_evaluator.evaluate(docs_sorted)
            base_confidence = min(0.95, 0.25 + 0.12 * len(docs_sorted) + 0.08 * len(source_ids))
            confidence = min(0.98, max(base_confidence, quality["quality_score"]))
            title = docs_sorted[-1]["title"] or key
            summary = self._summary(docs_sorted[-1]["text"])
            event = EventCandidate(
                event_key=key,
                title=title[:240],
                category=docs_sorted[-1]["category"],
                asset_scope=asset_scope,
                document_ids=[row["document_id"] for row in docs_sorted],
                source_ids=source_ids,
                confidence=round(confidence, 3),
                first_seen_at=first,
                last_seen_at=last,
                summary=summary,
                metadata={
                    "document_count": len(docs_sorted),
                    "source_count": len(source_ids),
                    **quality,
                },
            )
            self.store.upsert_event_candidate(event)
            events.append(event)
        return events

    def _skip_event_category(self, category: str | None) -> bool:
        return category in {"market_data", "search_discovery"}

    def _extract_url_candidates(self, row: sqlite3.Row) -> list[URLCandidate]:
        path = row["response_path"]
        if not path or not Path(path).exists():
            return []
        payload = self._load_json(path)
        if not isinstance(payload, dict):
            return []
        results = self._extract_result_items(payload)
        candidates: list[URLCandidate] = []
        for item in results:
            url = item.get("url") or item.get("link")
            canonical = canonicalize_url(url)
            if not url or not canonical:
                continue
            candidates.append(
                URLCandidate(
                    source_id=row["source_id"],
                    evidence_id=row["evidence_id"],
                    url=url,
                    canonical_url=canonical,
                    title=item.get("title"),
                    snippet=item.get("description") or item.get("snippet"),
                    score=0.5,
                    metadata={k: v for k, v in item.items() if k not in {"url", "link", "title", "description", "snippet"}},
                )
            )
        return candidates

    def _normalize_evidence(self, row: sqlite3.Row, source: sqlite3.Row | None) -> NormalizedDocument | None:
        text, title = self._extract_text_and_title(row)
        if not text or len(text.strip()) < 50:
            return None
        canonical_url = canonicalize_url(row["url"])
        fetched_at = self._parse_dt(row["fetched_at"])
        title = title or self._title_from_text(text) or row["query"] or row["url"] or row["source_id"]
        tier = source["tier"] if source else None
        category = source["category"] if source else None
        trust_weight = float(source["trust_weight"]) if source else 0.0
        asset_scope = self._loads(source["asset_scope_json"], []) if source else []
        return NormalizedDocument(
            evidence_id=row["evidence_id"],
            source_id=row["source_id"],
            tier=tier,
            category=category,
            trust_weight=trust_weight,
            canonical_url=canonical_url,
            title=title[:500],
            fetched_at=fetched_at,
            language=self._guess_language(text),
            text=text,
            content_hash=content_hash(text),
            title_key=normalize_title(title),
            asset_scope=asset_scope,
            metadata=self._loads(row["metadata_json"], {}),
        )

    def _extract_text_and_title(self, row: sqlite3.Row) -> tuple[str, str | None]:
        markdown_path = row["markdown_path"]
        if markdown_path and Path(markdown_path).exists():
            text = Path(markdown_path).read_text(encoding="utf-8", errors="ignore")
            return text, self._title_from_text(text)
        response_path = row["response_path"]
        if not response_path or not Path(response_path).exists():
            return "", None
        path = Path(response_path)
        raw = path.read_text(encoding="utf-8", errors="ignore")
        if path.suffix.lower() == ".xml":
            return self._xml_to_text(raw), self._xml_title(raw)
        if path.suffix.lower() == ".html":
            return self._html_to_text(raw), self._html_title(raw)
        payload = self._load_json(path)
        if isinstance(payload, dict):
            return self._json_to_text(payload), self._json_title(payload)
        return raw, None

    def _extract_result_items(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        data = payload.get("data")
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        if isinstance(data, dict):
            items: list[dict[str, Any]] = []
            for key in ("results", "web", "news", "articles"):
                value = data.get(key)
                if isinstance(value, list):
                    items.extend([item for item in value if isinstance(item, dict)])
            if items:
                return items
        for key in ("results", "web", "news", "articles"):
            value = payload.get(key)
            if isinstance(value, list):
                return [item for item in value if isinstance(item, dict)]
        return []

    def _json_to_text(self, payload: dict[str, Any]) -> str:
        markdown = self._nested_get(payload, ["data", "markdown"]) or payload.get("markdown")
        if isinstance(markdown, str):
            return markdown
        items = self._extract_result_items(payload)
        if items:
            lines = []
            for item in items:
                lines.append(str(item.get("title") or ""))
                lines.append(str(item.get("description") or item.get("snippet") or ""))
                lines.append(str(item.get("url") or item.get("link") or ""))
            return "\n".join(line for line in lines if line)
        return json.dumps(payload, ensure_ascii=False, indent=2, default=str)

    def _json_title(self, payload: dict[str, Any]) -> str | None:
        title = self._nested_get(payload, ["data", "metadata", "title"]) or self._nested_get(payload, ["metadata", "title"])
        if isinstance(title, str):
            return title.strip()
        items = self._extract_result_items(payload)
        if items:
            value = items[0].get("title")
            return str(value).strip() if value else None
        return None

    def _xml_to_text(self, text: str) -> str:
        try:
            root = ET.fromstring(text.encode("utf-8"))
        except Exception:
            return text
        lines: list[str] = []
        for item in root.findall(".//item"):
            for tag in ("title", "link", "description", "pubDate"):
                value = item.findtext(tag)
                if value:
                    lines.append(value.strip())
        for entry in root.findall(".//{http://www.w3.org/2005/Atom}entry"):
            for child in entry:
                if child.text and child.tag.endswith(("title", "summary", "updated", "id")):
                    lines.append(child.text.strip())
        return "\n".join(lines) or text

    def _xml_title(self, text: str) -> str | None:
        try:
            root = ET.fromstring(text.encode("utf-8"))
            title = root.findtext(".//channel/title") or root.findtext(".//{http://www.w3.org/2005/Atom}title")
            return title.strip() if title else None
        except Exception:
            return None

    def _html_to_text(self, text: str) -> str:
        value = re.sub(r"<(script|style).*?</\1>", " ", text, flags=re.IGNORECASE | re.DOTALL)
        value = re.sub(r"<[^>]+>", " ", value)
        value = re.sub(r"\s+", " ", value)
        return value.strip()

    def _html_title(self, text: str) -> str | None:
        match = HTML_TITLE_RE.search(text)
        if not match:
            return None
        return re.sub(r"\s+", " ", match.group(1)).strip()

    def _title_from_text(self, text: str) -> str | None:
        match = TITLE_RE.search(text)
        if match:
            return match.group(1).strip()
        for line in text.splitlines():
            line = line.strip()
            if 8 <= len(line) <= 160:
                return line.lstrip("# ").strip()
        return None

    def _event_key(self, row: sqlite3.Row) -> str:
        category = row["category"] or "uncategorized"
        family = self._category_family(category)
        tokens = self._event_anchor_tokens(row)
        asset_anchor = self._asset_anchor(row, tokens)
        if not tokens:
            tokens = [row["source_id"]]
        return f"{family}:{asset_anchor}:{' '.join(tokens)}"

    def _category_family(self, category: str) -> str:
        if category in {"energy", "energy_news"}:
            return "energy"
        if category in {"macro", "macro_data", "macro_market_news"}:
            return "macro"
        if category in {"crypto_news", "exchange_announcements"}:
            return "crypto"
        if category in {"geopolitics", "policy"}:
            return "geopolitics"
        if category in {"broad_market_news", "global_news_discovery"}:
            return "market_news"
        if category in {"social_sentiment", "social"}:
            return "social"
        return category

    def _asset_anchor(self, row: sqlite3.Row, tokens: list[str]) -> str:
        text = f"{row['title_key'] or ''} {' '.join(tokens)} {(row['text'] or '')[:500]}".lower()
        buckets: list[str] = []
        if any(token in text for token in ("oil", "crude", "brent", "wti", "opec", "eia", "tanker")):
            buckets.append("oil")
        if any(token in text for token in ("gold", "silver", "xau", "xag")):
            buckets.append("metals")
        if any(token in text for token in ("bitcoin", "btc", "ethereum", "eth", "crypto", "liquidation", "hack")):
            buckets.append("crypto")
        if any(token in text for token in ("fed", "fomc", "cpi", "ppi", "pce", "payroll", "yield", "dollar", "nasdaq", "nvidia", "apple", "microsoft", "earnings")):
            buckets.append("macro")
        if buckets:
            return "-".join(buckets[:3])

        assets = self._loads(row["asset_scope_json"], [])
        if len(assets) <= 2:
            return "-".join(str(asset).lower().replace("_", "") for asset in assets) or "global"
        return "global"

    def _event_anchor_tokens(self, row: sqlite3.Row) -> list[str]:
        title_key = row["title_key"] or normalize_title(row["title"])
        title_matches = self._anchor_matches(title_key.lower())
        if title_matches:
            return title_matches[:5]

        text = (row["text"] or "")[:1200].lower()
        matched = self._anchor_matches(text)
        if len(matched) >= 2:
            return matched[:5]

        tokens = []
        for token in title_key.split():
            token = token.strip()
            if len(token) < 3 or token in EVENT_STOPWORDS:
                continue
            tokens.append(token)
        return tokens[:5]

    def _anchor_matches(self, text: str) -> list[str]:
        matched = []
        for term in EVENT_ANCHOR_TERMS:
            if re.search(rf"\b{re.escape(term)}\b", text) and term not in matched:
                matched.append(term)
        return matched

    def _summary(self, text: str) -> str:
        value = re.sub(r"\s+", " ", text).strip()
        return value[:500]

    def _guess_language(self, text: str) -> str:
        cjk = sum(1 for char in text[:1000] if "\u4e00" <= char <= "\u9fff")
        return "zh" if cjk > 20 else "en"

    def _load_json(self, path: str | Path) -> Any:
        try:
            return json.loads(Path(path).read_text(encoding="utf-8", errors="ignore"))
        except Exception:
            return None

    def _loads(self, text: str | None, default: Any) -> Any:
        if not text:
            return default
        try:
            return json.loads(text)
        except Exception:
            return default

    def _parse_dt(self, value: str) -> datetime:
        try:
            return datetime.fromisoformat(value)
        except Exception:
            return datetime.now(timezone.utc)

    def _nested_get(self, payload: dict[str, Any], path: list[str]) -> Any:
        current: Any = payload
        for key in path:
            if not isinstance(current, dict):
                return None
            current = current.get(key)
        return current
