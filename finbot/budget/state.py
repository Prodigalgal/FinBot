from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from finbot.config.source_catalog import SourceCatalog
from finbot.storage.sqlite_store import SQLiteStore


class BudgetStateBuilder:
    def __init__(self, store: SQLiteStore, catalog: SourceCatalog):
        self.store = store
        self.catalog = catalog

    def build(self) -> dict[str, Any]:
        self.store.init_schema()
        self.store.clear_source_budget_state()
        run_counts = self._run_counts()
        states = []
        for source in self.catalog.sources:
            counts = run_counts.get(source.id, {"requests": 0, "success": 0, "blocked": 0})
            budget = source.budget or {}
            max_requests = self._max_requests(source, budget)
            credits_used = self._estimated_credits_used(source.id)
            max_credits = _to_float(budget.get("max_credits_per_day"))
            state = {
                "source_id": source.id,
                "provider": source.provider or source.mode,
                "budget_window": "all-observed",
                "requests_used": counts["requests"],
                "credits_used": credits_used,
                "max_requests": max_requests,
                "max_credits": max_credits,
                "throttled_until": None,
                "last_error": self._last_error(source.id),
                "status": self._status(counts["requests"], max_requests, credits_used, max_credits),
                "metadata": {
                    "success_runs": counts["success"],
                    "blocked_runs": counts["blocked"],
                    "mode": source.mode,
                    "priority": source.priority,
                },
            }
            self.store.upsert_source_budget_state(state)
            states.append(state)
        return {
            "states_updated": len(states),
            "throttled_sources": [state for state in states if state["status"] == "budget-exhausted"],
            "states": states,
        }

    def _run_counts(self) -> dict[str, dict[str, int]]:
        with self.store.connect() as conn:
            rows = conn.execute("select source_id, status, success from fetch_runs").fetchall()
        counts: dict[str, dict[str, int]] = {}
        for row in rows:
            source_counts = counts.setdefault(row["source_id"], {"requests": 0, "success": 0, "blocked": 0})
            source_counts["requests"] += 1
            if row["success"]:
                source_counts["success"] += 1
            if str(row["status"]).startswith("blocked"):
                source_counts["blocked"] += 1
        return counts

    def _estimated_credits_used(self, source_id: str) -> float:
        with self.store.connect() as conn:
            rows = conn.execute("select metadata_json from fetch_runs where source_id = ?", (source_id,)).fetchall()
        credits = 0.0
        for row in rows:
            metadata = _loads(row["metadata_json"], {})
            if "markdown_length" in metadata:
                credits += 1.0
            elif "result_count" in metadata:
                credits += 1.0
            else:
                credits += 0.1
        return round(credits, 3)

    def _last_error(self, source_id: str) -> str | None:
        with self.store.connect() as conn:
            row = conn.execute(
                "select detail from fetch_runs where source_id = ? and success = 0 order by ran_at desc limit 1",
                (source_id,),
            ).fetchone()
        return row["detail"] if row else None

    def _max_requests(self, source, budget: dict[str, Any]) -> int | None:
        values = [
            budget.get("max_requests_per_day"),
            budget.get("max_searches_per_hour"),
            budget.get("max_scrapes_per_hour"),
        ]
        for value in values:
            parsed = _to_int(value)
            if parsed is not None:
                return parsed
        if "firecrawl" in source.mode:
            return 500
        return None

    def _status(self, requests: int, max_requests: int | None, credits: float, max_credits: float | None) -> str:
        if max_requests is not None and requests >= max_requests:
            return "budget-exhausted"
        if max_credits is not None and credits >= max_credits:
            return "budget-exhausted"
        if max_requests is not None and requests >= max_requests * 0.8:
            return "near-budget"
        if max_credits is not None and credits >= max_credits * 0.8:
            return "near-budget"
        return "ok"


def _loads(value: str | None, default):
    if not value:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


def _to_int(value: Any) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _to_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
