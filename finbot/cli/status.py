from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

from finbot.config.settings import Settings
from finbot.storage.sqlite_store import SQLiteStore


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Show FinBot ingestion status.")
    parser.add_argument("--data-dir", default="data")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(args.data_dir))
    store = SQLiteStore(settings.sqlite_path)
    store.init_schema()
    with store.connect() as conn:
        health_rows = conn.execute("select * from source_health order by source_id").fetchall()
        counts = {
            table: conn.execute(f"select count(*) from {table}").fetchone()[0]
            for table in [
                "sources",
                "source_health",
                "fetch_jobs",
                "fetch_runs",
                "raw_evidence",
                "url_candidates",
                "normalized_documents",
                "dedupe_keys",
                "event_candidates",
                "research_packages",
                "official_release_calendar",
                "market_context_snapshots",
                "market_quotes",
                "market_candles",
                "advisory_reports",
                "paper_order_proposals",
                "macro_release_facts",
                "source_budget_state",
                "ai_compressions",
                "research_cards",
                "research_card_validations",
                "research_card_decisions",
                "research_followup_dispatches",
                "research_watch_items",
                "research_briefs",
                "research_review_verdicts",
                "research_councils",
                "research_pipeline_runs",
                "research_pipeline_steps",
                "research_pipeline_artifacts",
            ]
        }
        latest_pipeline_run = conn.execute(
            "select * from research_pipeline_runs order by started_at desc limit 1"
        ).fetchone()
        latest_pipeline_steps = []
        if latest_pipeline_run:
            latest_pipeline_steps = conn.execute(
                """
                select steps.step_name, steps.status, steps.attempt, steps.duration_ms, steps.error
                from research_pipeline_steps steps
                join (
                  select step_name, max(attempt) as max_attempt
                  from research_pipeline_steps
                  where run_id = ?
                  group by step_name
                ) latest
                  on steps.step_name = latest.step_name
                 and steps.attempt = latest.max_attempt
                where steps.run_id = ?
                order by steps.created_at, steps.step_name
                """,
                (latest_pipeline_run["run_id"], latest_pipeline_run["run_id"]),
            ).fetchall()
        latest_pipeline_artifacts = []
        if latest_pipeline_run:
            latest_pipeline_artifacts = conn.execute(
                """
                select artifact_type, count(*) as count
                from research_pipeline_artifacts
                where run_id = ?
                group by artifact_type
                order by artifact_type
                """,
                (latest_pipeline_run["run_id"],),
            ).fetchall()
    status_counts = Counter(row["status"] for row in health_rows)
    latest_pipeline = None
    if latest_pipeline_run:
        latest_pipeline = {
            "run_id": latest_pipeline_run["run_id"],
            "profile": latest_pipeline_run["profile"],
            "status": latest_pipeline_run["status"],
            "started_at": latest_pipeline_run["started_at"],
            "finished_at": latest_pipeline_run["finished_at"],
            "error": latest_pipeline_run["error"],
            "summary": _loads(latest_pipeline_run["summary_json"], {}),
            "readable_summary": _readable_pipeline_summary(latest_pipeline_run, latest_pipeline_steps),
            "artifact_counts": {row["artifact_type"]: row["count"] for row in latest_pipeline_artifacts},
            "steps": [
                {
                    "step_name": row["step_name"],
                    "status": row["status"],
                    "attempt": row["attempt"],
                    "duration_ms": row["duration_ms"],
                    "error": row["error"],
                }
                for row in latest_pipeline_steps
            ],
        }
    required_keys: dict[str, list[str]] = defaultdict(list)
    blocked_sources: list[dict] = []
    for row in health_rows:
        keys = _loads(row["required_keys_json"], [])
        for key in keys:
            required_keys[key].append(row["source_id"])
        if row["status"] in {"blocked-by-credential", "blocked-by-provider", "disabled-by-scope", "failed"}:
            blocked_sources.append(
                {
                    "source_id": row["source_id"],
                    "status": row["status"],
                    "detail": row["detail"],
                    "required_keys": keys,
                }
            )

    report = {
        "counts": counts,
        "source_statuses": dict(status_counts),
        "required_keys": dict(required_keys),
        "blocked_sources": blocked_sources,
        "latest_pipeline_run": latest_pipeline,
    }
    output = settings.reports_dir / "ingestion-status.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("Report:", output)


def _loads(value: str | None, default):
    if not value:
        return default
    try:
        loaded = json.loads(value)
        return default if loaded is None else loaded
    except Exception:
        return default


def _readable_pipeline_summary(run, steps) -> str:
    statuses = Counter(row["status"] for row in steps)
    parts = [f"run={run['run_id']}", f"status={run['status']}"]
    if statuses:
        parts.append("steps=" + ",".join(f"{status}:{count}" for status, count in sorted(statuses.items())))
    failed = [row["step_name"] for row in steps if row["status"] == "failed"]
    if failed:
        parts.append("failed=" + ",".join(failed))
    return " | ".join(parts)


if __name__ == "__main__":
    main()
