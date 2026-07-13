from __future__ import annotations

import argparse
import asyncio
import json
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

from finbot.config.settings import Settings
from finbot.config.source_catalog import SourceCatalog
from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.dispatcher import Dispatcher
from finbot.ingestion.models import AdapterResult, SmokeReport
from finbot.research.package_builder import build_research_input
from finbot.storage.factory import create_runtime_store
from finbot.storage.evidence_store import EvidenceStore


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke test FinBot source ingestion adapters.")
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    parser.add_argument("--topics", default="config/topic_watchlists.example.yml")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--timeout-seconds", type=float, default=25)
    parser.add_argument("--force-disabled", action="store_true", help="Run adapters even for disabled catalog entries.")
    parser.add_argument("--source", action="append", help="Run only selected source id; may be repeated.")
    return parser.parse_args()


async def run() -> int:
    args = parse_args()
    project_root = Path.cwd()
    settings = Settings.from_env(project_root=project_root, data_dir=Path(args.data_dir))
    settings.ensure_dirs()

    catalog = SourceCatalog.load(args.catalog)
    topics = TopicWatchlists.load(args.topics)
    evidence_store = EvidenceStore(settings.evidence_dir)
    store = create_runtime_store(settings)
    store.prune_catalog_sources({source.id for source in catalog.sources})
    for source in catalog.sources:
        store.upsert_source(source)

    selected = set(args.source or [])
    sources = [source for source in catalog.sources if not selected or source.id in selected]
    dispatcher = Dispatcher(settings, evidence_store, topics, timeout_seconds=args.timeout_seconds)

    started_at = datetime.now(timezone.utc)
    results: list[AdapterResult] = []

    try:
        for source in sources:
            store.upsert_source(source)
            try:
                result = await dispatcher.dispatch(source, force_disabled=args.force_disabled)
            except Exception as exc:
                result = AdapterResult(source_id=source.id, status="failed", detail=f"Unhandled source failure: {exc}", success=False)
            result.metadata.setdefault("asset_scope", source.asset_scope)
            results.append(result)
            if result.evidence is not None:
                store.insert_evidence(result.evidence)
            store.upsert_health(result)
            print(f"{source.id:38} {result.status:24} {result.detail}")
    finally:
        dispatcher.close()

    status_counts = Counter(result.status for result in results)
    required_keys: dict[str, list[str]] = defaultdict(list)
    for result in results:
        for key in result.required_keys:
            required_keys[key].append(result.source_id)

    report = SmokeReport(
        started_at=started_at,
        ended_at=datetime.now(timezone.utc),
        total_sources=len(results),
        statuses=dict(status_counts),
        results=results,
        required_keys=dict(required_keys),
    )
    research_input = build_research_input(results)

    report_json_path = settings.reports_dir / "source-smoke-report.json"
    report_md_path = settings.reports_dir / "source-smoke-report.md"
    research_json_path = settings.reports_dir / "research-input-smoke.json"

    report_json_path.write_text(report.model_dump_json(indent=2), encoding="utf-8")
    research_json_path.write_text(json.dumps(research_input, ensure_ascii=False, indent=2), encoding="utf-8")
    report_md_path.write_text(_render_markdown(report), encoding="utf-8")

    print("")
    print("Summary:", dict(status_counts))
    print("Required keys:", dict(required_keys))
    print("Report:", report_json_path)
    print("Markdown:", report_md_path)
    print("Research input:", research_json_path)
    return 0


def _render_markdown(report: SmokeReport) -> str:
    lines = [
        "# FinBot Source Smoke Report",
        "",
        f"- Started: {report.started_at.isoformat()}",
        f"- Ended: {report.ended_at.isoformat() if report.ended_at else ''}",
        f"- Total sources: {report.total_sources}",
        f"- Statuses: `{json.dumps(report.statuses, ensure_ascii=False)}`",
        "",
        "## Required Keys",
        "",
    ]
    if report.required_keys:
        for key, sources in sorted(report.required_keys.items()):
            lines.append(f"- `{key}`: {', '.join(sorted(sources))}")
    else:
        lines.append("- None")
    lines.extend(["", "## Sources", ""])
    lines.append("| Source | Status | Detail | Required Keys |")
    lines.append("| --- | --- | --- | --- |")
    for result in report.results:
        keys = ", ".join(result.required_keys)
        detail = result.detail.replace("|", "\\|")
        lines.append(f"| `{result.source_id}` | `{result.status}` | {detail} | {keys} |")
    lines.append("")
    return "\n".join(lines)


def main() -> None:
    raise SystemExit(asyncio.run(run()))


if __name__ == "__main__":
    main()
