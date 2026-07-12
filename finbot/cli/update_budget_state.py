from __future__ import annotations

import argparse

from finbot.budget.state import BudgetStateBuilder
from finbot.cli.common import build_store, write_report
from finbot.config.source_catalog import SourceCatalog


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Update source budget and throttling state from fetch history.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    catalog = SourceCatalog.load(args.catalog)
    report = BudgetStateBuilder(store, catalog).build()
    output = write_report(settings, "budget-state-report.json", report)
    print("States:", report["states_updated"])
    print("Throttled:", len(report["throttled_sources"]))
    print("Report:", output)


if __name__ == "__main__":
    main()
