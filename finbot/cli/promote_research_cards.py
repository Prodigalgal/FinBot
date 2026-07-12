from __future__ import annotations

import argparse

from finbot.cli.common import build_store, write_report
from finbot.research.card_promotion import ResearchCardPromoter


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Promote validated research cards into watch/follow-up/archive decisions.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--limit-cards", type=int, default=None)
    parser.add_argument("--clear-existing", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    report = ResearchCardPromoter(store).promote_all(limit=args.limit_cards, clear_existing=args.clear_existing)
    output = write_report(settings, "research-card-decisions-latest.json", report)
    print("Total cards:", report["total_cards"])
    print("Promoted cards:", report["promoted_cards"])
    print("Decisions:", report["decisions"])
    print("Skipped cards:", len(report["skipped_cards"]))
    print("Output:", output)


if __name__ == "__main__":
    main()
