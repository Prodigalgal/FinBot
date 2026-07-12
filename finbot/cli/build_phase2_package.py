from __future__ import annotations

import argparse
import json

from finbot.cli.common import build_store
from finbot.research.package_builder import ResearchPackageBuilder


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build the Phase 2 research readiness package.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--time-window", default="phase2-latest")
    parser.add_argument("--limit-events", type=int, default=100)
    parser.add_argument("--limit-documents", type=int, default=200)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    builder = ResearchPackageBuilder(store)
    package_id, payload = builder.build_from_store(
        time_window=args.time_window,
        limit_events=args.limit_events,
        limit_documents=args.limit_documents,
    )
    output = settings.reports_dir / "phase2-research-package-latest.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    readiness = payload["quality_summary"]["research_readiness"]["readiness"]
    print("Package:", package_id)
    print("Research ready:", readiness["research-ready"])
    print("Needs corroboration:", readiness["needs-corroboration"])
    print("Watch only:", readiness["watch-only"])
    print("Discard/background:", readiness["discard-or-background"])
    print("Output:", output)


if __name__ == "__main__":
    main()
