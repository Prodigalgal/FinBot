from __future__ import annotations

import argparse

from finbot.cli.common import build_store, write_report
from finbot.macro.facts import MacroFactBuilder


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract structured macro release facts from raw evidence.")
    parser.add_argument("--data-dir", default="data")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    report = MacroFactBuilder(store).build()
    output = write_report(settings, "macro-release-facts-report.json", report)
    print("Facts:", report["facts_created"])
    print("Providers:", report["providers"])
    print("Report:", output)


if __name__ == "__main__":
    main()
