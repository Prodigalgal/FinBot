from __future__ import annotations

import argparse

from finbot.cli.common import build_store, write_report
from finbot.scheduling.corroboration import CorroborationPlanner


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Plan Phase 2 corroboration follow-up jobs.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--max-jobs", type=int, default=20)
    parser.add_argument("--no-enqueue", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    report = CorroborationPlanner(store).plan(max_jobs=args.max_jobs, enqueue=not args.no_enqueue)
    output = write_report(settings, "corroboration-plan-report.json", report)
    print("Jobs planned:", report["jobs_planned"])
    print("Enqueued:", report["enqueued"])
    print("Report:", output)


if __name__ == "__main__":
    main()
