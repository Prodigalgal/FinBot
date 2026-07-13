from __future__ import annotations

import argparse
import json
from pathlib import Path

from finbot.cli.common import build_store
from finbot.config.settings import Settings
from finbot.research.package_builder import ResearchPackageBuilder


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build an AI research input package from normalized evidence.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--time-window", default="latest")
    parser.add_argument("--limit-events", type=int, default=50)
    parser.add_argument("--limit-documents", type=int, default=100)
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
    output = settings.reports_dir / "research-input-latest.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    print("Package:", package_id)
    print("Events:", len(payload["event_candidates"]))
    print("Documents:", len(payload["raw_document_refs"]))
    print("Output:", output)


if __name__ == "__main__":
    main()
