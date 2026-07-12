from __future__ import annotations

import argparse

from finbot.calendar.official_release_registry import OfficialReleaseRegistry
from finbot.calendar.release_matcher import OfficialReleaseMatcher, release_row_to_dict
from finbot.cli.common import build_store, write_report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Load official release calendar and match it against normalized evidence.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--calendar", default="config/official_release_calendar.example.yml")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    registry = OfficialReleaseRegistry.load(args.calendar)
    matcher = OfficialReleaseMatcher(store)
    report = matcher.upsert_and_match(registry.releases)
    report["calendar_rows"] = [release_row_to_dict(row) for row in store.list_official_releases()]
    output = write_report(settings, "official-release-calendar-report.json", report)
    print("Releases:", report["total_releases"])
    print("Strict matched:", report["matched_releases"])
    print("Candidate matches:", report["candidate_releases"])
    print("Report:", output)


if __name__ == "__main__":
    main()
