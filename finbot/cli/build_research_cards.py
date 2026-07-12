from __future__ import annotations

import argparse
from collections import Counter

from finbot.cli.common import build_store, write_report
from finbot.research.context_retriever import DEFAULT_RESEARCH_READINESS, SQLiteResearchContextRetriever
from finbot.research.freshness import FreshnessGate
from finbot.research.research_cards import ResearchCardBuildConfig, ResearchCardBuilder


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build Phase 3 research cards from Phase 2 research context.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--time-window", default="phase3-latest")
    parser.add_argument("--limit-events", type=int, default=20)
    parser.add_argument("--include-watch-only", action="store_true")
    parser.add_argument("--clear-existing", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    if args.clear_existing:
        store.clear_research_cards()

    readiness = DEFAULT_RESEARCH_READINESS
    if args.include_watch_only:
        readiness = (*DEFAULT_RESEARCH_READINESS, "watch-only")

    retriever = SQLiteResearchContextRetriever(store=store, freshness_gate=FreshnessGate())
    builder = ResearchCardBuilder()
    events = retriever.candidate_events(limit=args.limit_events, readiness=readiness)
    cards = []
    for event in events:
        context = retriever.build_context_pack(event)
        card = builder.build(context, ResearchCardBuildConfig(time_window=args.time_window))
        store.insert_research_card(card)
        cards.append(card)

    summary = {
        "generated_at": cards[0]["created_at"] if cards else None,
        "time_window": args.time_window,
        "selected_readiness": list(readiness),
        "total_cards": len(cards),
        "readiness": dict(Counter(card["readiness"] for card in cards)),
        "freshness": dict(Counter(card["freshness_status"] for card in cards)),
        "policy_gate": dict(Counter(card["policy_gate"]["status"] for card in cards)),
    }
    report = {
        "summary": summary,
        "cards": cards,
    }
    output = write_report(settings, "phase3-research-cards-latest.json", report)
    print("Research cards:", len(cards))
    print("Readiness:", summary["readiness"])
    print("Freshness:", summary["freshness"])
    print("Policy gate:", summary["policy_gate"])
    print("Output:", output)


if __name__ == "__main__":
    main()
