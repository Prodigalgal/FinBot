from __future__ import annotations

import argparse
import os
from pathlib import Path

from finbot.ai.openai_compatible import DEFAULT_PROVIDER_KEYS_FILE, load_provider_configs
from finbot.cli.common import build_store, write_report
from finbot.research.ai_compression import AICompressionRunConfig, AICompressionRunner


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run auditable AI compression for Phase 2 research candidates.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--keys-file", default=os.getenv("AI_PROVIDER_KEYS_FILE", str(DEFAULT_PROVIDER_KEYS_FILE)))
    parser.add_argument("--provider", action="append", choices=["deepseek", "mimo"], help="Provider order. Can be passed multiple times.")
    parser.add_argument("--protocol", choices=["chat", "responses"], default="chat")
    parser.add_argument("--limit-documents", type=int, default=5)
    parser.add_argument("--limit-events", type=int, default=3)
    parser.add_argument("--source-document-limit", type=int, default=200)
    parser.add_argument("--source-event-limit", type=int, default=100)
    parser.add_argument("--max-document-chars", type=int, default=9000)
    parser.add_argument("--max-event-chars", type=int, default=16000)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--clear-existing", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    settings, store = build_store(args.data_dir)
    providers = load_provider_configs(keys_file=Path(args.keys_file))
    provider_order = tuple(args.provider or _provider_order_from_env())
    runner = AICompressionRunner(store=store, providers=providers)
    report = runner.run(
        AICompressionRunConfig(
            protocol=args.protocol,
            provider_order=provider_order,
            limit_documents=args.limit_documents,
            limit_events=args.limit_events,
            source_document_limit=args.source_document_limit,
            source_event_limit=args.source_event_limit,
            max_document_chars=args.max_document_chars,
            max_event_chars=args.max_event_chars,
            dry_run=args.dry_run,
            clear_existing=args.clear_existing,
        )
    )
    output = write_report(settings, "ai-compression-latest.json", report)
    print("Dry run:", report["dry_run"])
    print("Protocol:", report["protocol"])
    for status in report["provider_status"]:
        state = "configured" if status["configured"] else "disabled"
        missing = ",".join(status["missing"]) if status["missing"] else "-"
        model = status["model"] or "-"
        print(f"Provider {status['name']}: {state}; missing={missing}; model={model}")
    print("Document candidates:", report["candidate_counts"]["documents"])
    print("Event candidates:", report["candidate_counts"]["events"])
    if "summary" in report:
        print("Results:", report["summary"])
    print("Output:", output)


def _provider_order_from_env() -> list[str]:
    raw_value = os.getenv("AI_COMPRESSION_PROVIDER_ORDER", "deepseek,mimo")
    values = [value.strip() for value in raw_value.split(",") if value.strip()]
    return [value for value in values if value in {"deepseek", "mimo"}] or ["deepseek", "mimo"]


if __name__ == "__main__":
    main()
