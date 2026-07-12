from __future__ import annotations

import argparse

from finbot.cli.common import build_store, write_report
from finbot.research.followup_dispatch import ResearchFollowupDispatcher


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Dispatch Phase 3 research follow-up jobs into fetch_jobs.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--limit-decisions", type=int, default=None)
    parser.add_argument("--max-jobs", type=int, default=50)
    parser.add_argument("--clear-existing", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    report = ResearchFollowupDispatcher(store).dispatch_all(
        limit_decisions=args.limit_decisions,
        max_jobs=args.max_jobs,
        clear_existing=args.clear_existing,
    )
    output = write_report(settings, "research-followup-dispatch-latest.json", report)
    print("Decisions considered:", report["decision_count"])
    print("Jobs dispatched:", report["jobs_dispatched"])
    print("Job types:", report["job_types"])
    print("Source ids:", report["source_ids"])
    print("Skipped:", len(report["skipped"]))
    print("Output:", output)


if __name__ == "__main__":
    main()
