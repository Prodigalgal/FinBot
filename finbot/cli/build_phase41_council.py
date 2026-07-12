from __future__ import annotations

import argparse

from finbot.cli.common import build_store, write_report
from finbot.research.review_council import ResearchReviewCouncil


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build Phase 4.1 research review council.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--time-window", default="phase4.1-latest")
    parser.add_argument("--limit-items", type=int, default=20)
    parser.add_argument("--clear-existing", action="store_true")
    parser.add_argument("--include-background", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    report = ResearchReviewCouncil(store).run(
        time_window=args.time_window,
        limit_items=args.limit_items,
        clear_existing=args.clear_existing,
        include_background=args.include_background,
    )
    output = write_report(settings, "phase4-review-council-latest.json", report)
    print("Council:", report["council_id"])
    print("Brief:", report["brief_id"])
    print("Reviewed items:", report["reviewed_items"])
    print("Summary:", report["summary"])
    print("Policy gate:", report["policy_gate"])
    print("Output:", output)


if __name__ == "__main__":
    main()
