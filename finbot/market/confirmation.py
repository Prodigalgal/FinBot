from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

from finbot.storage.sqlite_store import SQLiteStore


class MarketConfirmationBuilder:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def build(self, limit_events: int | None = None) -> dict[str, Any]:
        self.store.init_schema()
        events = self.store.list_event_candidates(limit=limit_events)
        documents = self.store.list_normalized_documents()
        market_docs = [row for row in documents if row["category"] == "market_data"]
        evidence_map = self._raw_evidence_map([row["evidence_id"] for row in market_docs])
        self.store.clear_market_context_snapshots()

        snapshots = []
        missing = []
        for event in events:
            metadata = _loads(event["metadata_json"], {})
            priority = metadata.get("priority") or "P3"
            if priority not in {"P0", "P1", "P2"}:
                continue
            assets = _loads(event["asset_scope_json"], [])
            matched_docs = self._matching_market_docs(assets, market_docs)
            if not matched_docs:
                missing.append({"event_id": event["event_id"], "title": event["title"], "asset_scope": assets})
                continue
            market_points = [
                point
                for row in matched_docs
                for point in self._market_points(row, evidence_map.get(row["evidence_id"]))
            ]
            price_change_pct = _average([point.get("price_change_pct") for point in market_points])
            volume_values = [point.get("volume_24h") for point in market_points if point.get("volume_24h") is not None]
            snapshot = {
                "snapshot_id": uuid4().hex,
                "event_id": event["event_id"],
                "event_key": event["event_key"],
                "asset_scope": assets,
                "provider": "mixed-public-market-data",
                "captured_at": datetime.now(timezone.utc).isoformat(),
                "status": "available",
                "market_document_ids": [row["document_id"] for row in matched_docs[:8]],
                "market_source_ids": sorted({row["source_id"] for row in matched_docs}),
                "price_change_pct": price_change_pct,
                "volume_change_pct": None,
                "volatility_proxy": _average([point.get("volatility_proxy") for point in market_points]),
                "note": "Public market data context only; not a trading signal.",
                "market_points": market_points,
                "volume_observations": volume_values[:8],
            }
            self.store.insert_market_context_snapshot(snapshot)
            snapshots.append(snapshot)
        return {
            "snapshots_created": len(snapshots),
            "missing_market_context": len(missing),
            "snapshots": snapshots,
            "missing": missing,
        }

    def _matching_market_docs(self, assets: list[str], market_docs) -> list[Any]:
        if not assets:
            return []
        event_assets = set(assets)
        matched = []
        for row in market_docs:
            row_assets = set(_loads(row["asset_scope_json"], []))
            if event_assets.intersection(row_assets):
                matched.append(row)
        return matched

    def _raw_evidence_map(self, evidence_ids: list[str]) -> dict[str, Any]:
        if not evidence_ids:
            return {}
        placeholders = ",".join("?" for _ in evidence_ids)
        with self.store.connect() as conn:
            rows = conn.execute(
                f"select * from raw_evidence where evidence_id in ({placeholders})",
                tuple(evidence_ids),
            ).fetchall()
        return {row["evidence_id"]: row for row in rows}

    def _market_points(self, document, evidence) -> list[dict[str, Any]]:
        if not evidence or not evidence["response_path"]:
            return []
        payload = _load_json(evidence["response_path"])
        source_id = document["source_id"]
        if isinstance(payload, dict) and isinstance(payload.get("quotes"), list):
            return self._quote_points(payload["quotes"], source_id)
        if source_id == "market_bybit_public":
            return self._bybit_points(payload, source_id)
        if source_id == "market_gate_public":
            return self._gate_points(payload, source_id)
        return []

    def _quote_points(self, quotes: list[dict[str, Any]], source_id: str) -> list[dict[str, Any]]:
        points = []
        for quote in quotes:
            last_price = _to_float(quote.get("last_price"))
            high = _to_float(quote.get("high_24h"))
            low = _to_float(quote.get("low_24h"))
            points.append(
                {
                    "source_id": source_id,
                    "provider": quote.get("provider"),
                    "symbol": quote.get("symbol"),
                    "last_price": last_price,
                    "price_change_pct": _to_float(quote.get("price_change_pct_24h")),
                    "volume_24h": _to_float(quote.get("volume_24h")),
                    "turnover_24h": _to_float(quote.get("turnover_24h")),
                    "bid": _to_float(quote.get("bid")),
                    "ask": _to_float(quote.get("ask")),
                    "volatility_proxy": _range_pct(high, low, last_price),
                }
            )
        return points

    def _bybit_points(self, payload: Any, source_id: str) -> list[dict[str, Any]]:
        items = (((payload or {}).get("result") or {}).get("list") or []) if isinstance(payload, dict) else []
        points = []
        for item in items:
            last_price = _to_float(item.get("lastPrice"))
            high = _to_float(item.get("highPrice24h"))
            low = _to_float(item.get("lowPrice24h"))
            points.append(
                {
                    "source_id": source_id,
                    "symbol": item.get("symbol"),
                    "last_price": last_price,
                    "price_change_pct": _scale_ratio_pct(_to_float(item.get("price24hPcnt"))),
                    "volume_24h": _to_float(item.get("volume24h")),
                    "turnover_24h": _to_float(item.get("turnover24h")),
                    "open_interest": _to_float(item.get("openInterest")),
                    "funding_rate": _to_float(item.get("fundingRate")),
                    "volatility_proxy": _range_pct(high, low, last_price),
                }
            )
        return points

    def _gate_points(self, payload: Any, source_id: str) -> list[dict[str, Any]]:
        items = payload if isinstance(payload, list) else []
        points = []
        for item in items:
            last_price = _to_float(item.get("last"))
            high = _to_float(item.get("high_24h"))
            low = _to_float(item.get("low_24h"))
            points.append(
                {
                    "source_id": source_id,
                    "symbol": item.get("currency_pair"),
                    "last_price": last_price,
                    "price_change_pct": _to_float(item.get("change_percentage")),
                    "volume_24h": _to_float(item.get("base_volume")),
                    "turnover_24h": _to_float(item.get("quote_volume")),
                    "bid": _to_float(item.get("highest_bid")),
                    "ask": _to_float(item.get("lowest_ask")),
                    "volatility_proxy": _range_pct(high, low, last_price),
                }
            )
        return points


def _loads(value: str | None, default):
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


def _load_json(path: str | None) -> Any:
    if not path:
        return None
    try:
        return json.loads(Path(path).read_text(encoding="utf-8", errors="ignore"))
    except Exception:
        return None


def _to_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _scale_ratio_pct(value: float | None) -> float | None:
    if value is None:
        return None
    return round(value * 100, 6)


def _range_pct(high: float | None, low: float | None, last: float | None) -> float | None:
    if high is None or low is None or not last:
        return None
    return round((high - low) / last * 100, 6)


def _average(values: list[float | None]) -> float | None:
    clean = [value for value in values if value is not None]
    if not clean:
        return None
    return round(sum(clean) / len(clean), 6)
