from __future__ import annotations

import argparse
import asyncio
import json
from collections import Counter, deque
from pathlib import Path

from finbot.config.settings import Settings
from finbot.config.source_catalog import SourceCatalog
from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.dispatcher import Dispatcher
from finbot.ingestion.models import AdapterResult, FetchJob, SourceConfig
from finbot.ingestion.scheduler import SourceScheduler
from finbot.storage.evidence_store import EvidenceStore
from finbot.storage.factory import create_runtime_store


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run one ingestion scheduler pass.")
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    parser.add_argument("--topics", default="config/topic_watchlists.example.yml")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--timeout-seconds", type=float, default=25)
    parser.add_argument("--force-disabled", action="store_true")
    parser.add_argument("--source", action="append")
    parser.add_argument("--max-initial-jobs", type=int, default=50)
    parser.add_argument("--max-followup-jobs", type=int, default=20)
    parser.add_argument("--max-followups-per-result", type=int, default=3)
    return parser.parse_args()


async def run() -> int:
    args = parse_args()
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(args.data_dir))
    settings.ensure_dirs()
    catalog = SourceCatalog.load(args.catalog)
    topics = TopicWatchlists.load(args.topics)
    scheduler = SourceScheduler(topics)
    store = create_runtime_store(settings)
    store.prune_catalog_sources({source.id for source in catalog.sources})
    evidence_store = EvidenceStore(settings.evidence_dir)
    dispatcher = Dispatcher(settings, evidence_store, topics, timeout_seconds=args.timeout_seconds)

    selected = set(args.source or [])
    sources = [source for source in catalog.sources if not selected or source.id in selected]
    source_map = {source.id: source for source in catalog.sources}
    for source in catalog.sources:
        store.upsert_source(source)

    queue: deque[tuple[SourceConfig, FetchJob, bool]] = deque()
    for source in sources:
        if not source.enabled and not args.force_disabled:
            continue
        for job in scheduler.jobs_for_source(source):
            queue.append((source, job, False))
            if len(queue) >= args.max_initial_jobs:
                break
        if len(queue) >= args.max_initial_jobs:
            break

    results: list[AdapterResult] = []
    followups_executed = 0
    try:
        while queue:
            source, job, is_followup = queue.popleft()
            store.upsert_fetch_job(job, status="running", detail="scheduled")
            result = await dispatcher.dispatch_job(source, job, force_disabled=args.force_disabled)
            results.append(result)
            if result.evidence:
                store.insert_evidence(result.evidence)
            store.insert_fetch_run(job, result)
            store.upsert_health(result)
            print(f"{source.id:38} {job.job_type:28} {result.status:24} {result.detail}")

            if is_followup:
                continue
            for next_job in result.discovered_jobs[: args.max_followups_per_result]:
                if followups_executed >= args.max_followup_jobs:
                    break
                next_source = source_map.get(next_job.source_id, source)
                queue.append((next_source, next_job, True))
                followups_executed += 1
    finally:
        dispatcher.close()

    counts = Counter(result.status for result in results)
    report = {
        "total_runs": len(results),
        "statuses": dict(counts),
        "followups_executed": followups_executed,
        "results": [
            {
                "source_id": result.source_id,
                "status": result.status,
                "detail": result.detail,
                "evidence_id": result.evidence.evidence_id if result.evidence else None,
                "discovered_jobs": len(result.discovered_jobs),
            }
            for result in results
        ],
    }
    output = settings.reports_dir / "run-once-report.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("")
    print("Summary:", dict(counts))
    print("Followups executed:", followups_executed)
    print("Report:", output)
    return 0


def main() -> None:
    raise SystemExit(asyncio.run(run()))


if __name__ == "__main__":
    main()
