from __future__ import annotations

import argparse

from finbot.cli.common import build_store, write_report
from finbot.market.confirmation import MarketConfirmationBuilder


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build market context snapshots for Phase 2 events.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--limit-events", type=int)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    builder = MarketConfirmationBuilder(store)
    report = builder.build(limit_events=args.limit_events)
    output = write_report(settings, "market-confirmation-report.json", report)
    print("Snapshots:", report["snapshots_created"])
    print("Missing:", report["missing_market_context"])
    print("Report:", output)


if __name__ == "__main__":
    main()
