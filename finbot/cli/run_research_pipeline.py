from __future__ import annotations

import argparse
import asyncio
import json
import os
from pathlib import Path

from finbot.ai.openai_compatible import DEFAULT_PROVIDER_KEYS_FILE
from finbot.cli.common import write_report
from finbot.orchestration.pipeline import (
    ResearchPipelineConfig,
    build_pipeline_runner,
    provider_order_from_env,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the Phase 5 research workflow pipeline.")
    parser.add_argument("--data-dir", default="data")
    parser.add_argument("--catalog", default="config/source_catalog.example.yml")
    parser.add_argument("--topics", default="config/topic_watchlists.example.yml")
    parser.add_argument("--profile", default="phase5-default")
    parser.add_argument("--triggered-by", default="cli")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--resume-run-id")
    parser.add_argument("--from-step")
    parser.add_argument("--continue-on-error", action="store_true")
    parser.add_argument("--clear-existing", action="store_true")
    parser.add_argument("--no-idempotent-outputs", action="store_true")

    parser.add_argument("--run-ingestion", action="store_true")
    parser.add_argument("--timeout-seconds", type=float, default=25)
    parser.add_argument("--force-disabled", action="store_true")
    parser.add_argument("--source", action="append")
    parser.add_argument("--max-initial-jobs", type=int, default=30)
    parser.add_argument("--max-ingestion-followup-jobs", type=int, default=10)
    parser.add_argument("--max-ingestion-followups-per-result", type=int, default=2)

    parser.add_argument("--evidence-limit", type=int)
    parser.add_argument("--max-events", type=int, default=10)
    parser.add_argument("--limit-cards", type=int)
    parser.add_argument("--limit-decisions", type=int)
    parser.add_argument("--max-dispatch-jobs", type=int, default=50)
    parser.add_argument("--include-watch-only", action="store_true")

    parser.add_argument("--run-ai-compression", action="store_true")
    parser.add_argument("--ai-compression-dry-run", action="store_true")
    parser.add_argument("--ai-keys-file", default=os.getenv("AI_PROVIDER_KEYS_FILE", str(DEFAULT_PROVIDER_KEYS_FILE)))
    parser.add_argument("--ai-provider", action="append", choices=["deepseek", "mimo"])
    parser.add_argument("--ai-protocol", choices=["chat", "responses"], default="chat")
    parser.add_argument("--ai-limit-documents", type=int, default=5)
    parser.add_argument("--ai-limit-events", type=int, default=3)

    parser.add_argument("--followups-dry-run", action="store_true")
    parser.add_argument("--run-followups", action="store_true")
    parser.add_argument("--followup-max-jobs", type=int, default=5)
    parser.add_argument("--followup-max-discovered-jobs", type=int, default=5)
    parser.add_argument("--followup-max-discovered-per-result", type=int, default=1)
    parser.add_argument("--rebuild-after-followups", action="store_true")

    parser.add_argument("--phase3-time-window", default="phase3-pipeline")
    parser.add_argument("--phase4-time-window", default="phase4-pipeline")
    parser.add_argument("--phase41-time-window", default="phase4.1-pipeline")
    parser.add_argument("--phase4-limit-items", type=int, default=20)
    parser.add_argument("--phase41-limit-items", type=int, default=20)
    parser.add_argument("--include-background-council", action="store_true")
    parser.add_argument("--artifact-retention-keep-runs", type=int)
    parser.add_argument("--artifact-retention-days", type=int)
    return parser.parse_args()


async def run() -> int:
    args = parse_args()
    settings, runner = build_pipeline_runner(
        data_dir=args.data_dir,
        catalog_path=args.catalog,
        topics_path=args.topics,
    )
    provider_order = tuple(args.ai_provider or provider_order_from_env())
    config = ResearchPipelineConfig(
        profile=args.profile,
        triggered_by=args.triggered_by,
        dry_run=args.dry_run,
        resume_run_id=args.resume_run_id,
        from_step=args.from_step,
        continue_on_error=args.continue_on_error,
        clear_existing=args.clear_existing,
        idempotent_outputs=not args.no_idempotent_outputs,
        catalog_path=args.catalog,
        topics_path=args.topics,
        timeout_seconds=args.timeout_seconds,
        run_ingestion=args.run_ingestion,
        force_disabled=args.force_disabled,
        source_ids=tuple(args.source or ()),
        max_initial_jobs=args.max_initial_jobs,
        max_ingestion_followup_jobs=args.max_ingestion_followup_jobs,
        max_ingestion_followups_per_result=args.max_ingestion_followups_per_result,
        evidence_limit=args.evidence_limit,
        max_events=args.max_events,
        limit_cards=args.limit_cards,
        limit_decisions=args.limit_decisions,
        max_dispatch_jobs=args.max_dispatch_jobs,
        run_ai_compression=args.run_ai_compression,
        ai_compression_dry_run=args.ai_compression_dry_run,
        ai_keys_file=args.ai_keys_file,
        ai_provider_order=provider_order,
        ai_protocol=args.ai_protocol,
        ai_limit_documents=args.ai_limit_documents,
        ai_limit_events=args.ai_limit_events,
        run_followups=args.run_followups,
        followups_dry_run=args.followups_dry_run,
        followup_max_jobs=args.followup_max_jobs,
        followup_max_discovered_jobs=args.followup_max_discovered_jobs,
        followup_max_discovered_per_result=args.followup_max_discovered_per_result,
        rebuild_after_followups=args.rebuild_after_followups,
        include_watch_only=args.include_watch_only,
        phase3_time_window=args.phase3_time_window,
        phase4_time_window=args.phase4_time_window,
        phase41_time_window=args.phase41_time_window,
        phase4_limit_items=args.phase4_limit_items,
        phase41_limit_items=args.phase41_limit_items,
        include_background_council=args.include_background_council,
        artifact_retention_keep_runs=args.artifact_retention_keep_runs,
        artifact_retention_days=args.artifact_retention_days,
    )
    report = await runner.run(config)
    output = write_report(settings, "research-pipeline-latest.json", report)

    print("Dry run:", report["dry_run"])
    if report["dry_run"]:
        print("Enabled steps:", ", ".join(report["enabled_steps"]))
        print("Disabled steps:", ", ".join(item["name"] for item in report["disabled_steps"]) or "-")
    else:
        print("Run:", report["run_id"])
        print("Status:", report["status"])
        print("Summary:", json.dumps(report["summary"], ensure_ascii=False))
        print("Steps:", ", ".join(f"{step['step_name']}={step['status']}#{step.get('attempt')}" for step in report["steps"]))
        if report.get("error"):
            print("Error:", report["error"])
    print("Report:", output)
    return 0 if report.get("status") != "failed" else 1


def main() -> None:
    raise SystemExit(asyncio.run(run()))


if __name__ == "__main__":
    main()
