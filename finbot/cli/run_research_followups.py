from __future__ import annotations

import argparse
import asyncio

from finbot.cli.common import write_report
from finbot.research.followup_runner import build_runner


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run queued Phase 3 research follow-up fetch jobs.")
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    parser.add_argument("--topics", default="config/topic_watchlists.example.yml")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--timeout-seconds", type=float, default=30)
    parser.add_argument("--max-jobs", type=int, default=10)
    parser.add_argument("--max-discovered-jobs", type=int, default=10)
    parser.add_argument("--max-discovered-per-result", type=int, default=2)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--force-disabled", action="store_true")
    parser.add_argument("--rebuild-after-run", action="store_true")
    parser.add_argument("--rebuild-time-window", default="phase3-followup-refresh")
    parser.add_argument("--rebuild-limit-events", type=int, default=10)
    parser.add_argument("--include-watch-only", action="store_true")
    return parser.parse_args()


async def run() -> int:
    args = parse_args()
    settings, runner = build_runner(
        data_dir=args.data_dir,
        catalog_path=args.catalog,
        topics_path=args.topics,
        timeout_seconds=args.timeout_seconds,
    )
    try:
        report = await runner.run(
            max_jobs=args.max_jobs,
            max_discovered_jobs=args.max_discovered_jobs,
            max_discovered_per_result=args.max_discovered_per_result,
            dry_run=args.dry_run,
            force_disabled=args.force_disabled,
            rebuild_after_run=args.rebuild_after_run,
            rebuild_time_window=args.rebuild_time_window,
            rebuild_limit_events=args.rebuild_limit_events,
            include_watch_only=args.include_watch_only,
        )
    finally:
        runner.close()
    output = write_report(settings, "run-research-followups-report.json", report)
    print("Dry run:", report["dry_run"])
    if report["dry_run"]:
        print("Queued jobs:", report["queued_jobs"])
    else:
        print("Root jobs selected:", report["root_jobs_selected"])
        print("Runs executed:", report["runs_executed"])
        print("Discovered executed:", report["discovered_executed"])
        print("Statuses:", report["statuses"])
        print("Skipped:", len(report["skipped"]))
        if report.get("rebuild"):
            print("Rebuild:", report["rebuild"])
    print("Report:", output)
    return 0


def main() -> None:
    raise SystemExit(asyncio.run(run()))


if __name__ == "__main__":
    main()
