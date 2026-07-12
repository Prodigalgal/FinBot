from __future__ import annotations

import argparse
import json
from pathlib import Path

from finbot.config.settings import Settings
from finbot.research.package_builder import ResearchPackageBuilder
from finbot.research.readiness_gate import READY_GROUPS, ResearchReadinessGate
from finbot.storage.sqlite_store import SQLiteStore


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Inspect Phase 2 event quality and research readiness.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--top", type=int, default=20)
    parser.add_argument("--readiness", choices=READY_GROUPS)
    parser.add_argument("--json", action="store_true", help="Print JSON instead of a compact table.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings = Settings.from_env(project_root=Path.cwd(), data_dir=Path(args.data_dir))
    store = SQLiteStore(settings.sqlite_path)
    store.init_schema()
    builder = ResearchPackageBuilder(store)
    gate = ResearchReadinessGate()
    rows = store.list_event_candidates()
    events = gate.annotate([builder._event_row(row) for row in rows])
    if args.readiness:
        events = [event for event in events if event["research_readiness"] == args.readiness]
    events = sorted(events, key=lambda event: float((event.get("quality") or {}).get("score") or 0), reverse=True)[: args.top]

    report = {
        "summary": gate.summary(gate.annotate([builder._event_row(row) for row in rows])),
        "events": events,
    }
    output = settings.reports_dir / "event-quality-inspection.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding="utf-8")

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    else:
        _print_table(events)
    print("Report:", output)


def _print_table(events: list[dict]) -> None:
    headers = ("score", "ready", "priority", "state", "title", "reasons")
    print(f"{headers[0]:>5}  {headers[1]:<22}  {headers[2]:<8}  {headers[3]:<10}  {headers[4]:<58}  {headers[5]}")
    print("-" * 128)
    for event in events:
        score = (event.get("quality") or {}).get("score")
        title = str(event.get("title") or "").replace("\n", " ")[:58]
        reasons = ",".join(event.get("readiness_reasons") or [])[:60]
        print(
            f"{float(score or 0):>5.3f}  "
            f"{event.get('research_readiness', ''):<22}  "
            f"{event.get('priority') or '':<8}  "
            f"{event.get('confirmation_state') or '':<10}  "
            f"{title:<58}  "
            f"{reasons}"
        )


if __name__ == "__main__":
    main()
