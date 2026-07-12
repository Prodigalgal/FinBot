from __future__ import annotations

import argparse
import json
from pathlib import Path

from finbot.config.settings import Settings
from finbot.normalization.evidence_processor import EvidenceProcessor
from finbot.storage.sqlite_store import SQLiteStore


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Normalize raw evidence and build event candidates.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--limit", type=int)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(args.data_dir))
    settings.ensure_dirs()
    store = SQLiteStore(settings.sqlite_path)
    processor = EvidenceProcessor(store)
    counts = processor.process_all(limit=args.limit)
    output = settings.reports_dir / "evidence-processing-report.json"
    output.write_text(json.dumps(counts, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(counts, ensure_ascii=False, indent=2))
    print("Report:", output)


if __name__ == "__main__":
    main()

