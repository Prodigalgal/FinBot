from __future__ import annotations

import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from finbot.config.topic_watchlist import TopicWatchlists
from finbot.ingestion.models import SourceConfig
from finbot.ingestion.scheduler import SourceScheduler
from finbot.orchestration.pipeline import ResearchPipelineConfig, ResearchPipelineRunner
from finbot.storage.sqlite_store import SQLiteStore


class StubPipelineRunner(ResearchPipelineRunner):
    def __init__(self, store: SQLiteStore, fail_once: set[str] | None = None):
        super().__init__(
            settings=SimpleNamespace(),
            store=store,
            catalog=SimpleNamespace(sources=[]),
            topics=SimpleNamespace(),
        )
        self.fail_once = set(fail_once or set())
        self.calls: list[str] = []

    async def _call_step(self, step_name: str, config: ResearchPipelineConfig, run_id: str) -> dict[str, Any]:
        self.calls.append(step_name)
        if step_name in self.fail_once:
            self.fail_once.remove(step_name)
            raise RuntimeError(f"planned failure: {step_name}")
        return {
            "step_name": step_name,
            "run_id": run_id,
            "network_step": step_name in {"ingestion_run", "run_followups"},
            "ai_step": step_name == "ai_compression",
        }


class ResearchPipelineTests(unittest.TestCase):
    def test_dry_run_does_not_write_pipeline_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            runner = StubPipelineRunner(store)

            report = asyncio.run(runner.run(ResearchPipelineConfig(dry_run=True)))

            self.assertTrue(report["dry_run"])
            disabled = {item["name"] for item in report["disabled_steps"]}
            self.assertIn("ingestion_run", disabled)
            self.assertIn("ai_compression", disabled)
            self.assertEqual(store.list_research_pipeline_runs(), [])
            self.assertEqual(runner.calls, [])

    def test_resume_from_failed_step_reuses_prior_attempts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            first_runner = StubPipelineRunner(store, fail_once={"macro_facts"})

            failed = asyncio.run(first_runner.run(ResearchPipelineConfig()))

            self.assertEqual(failed["status"], "failed")
            run_id = failed["run_id"]
            self.assertEqual(store.latest_research_pipeline_step(run_id, "macro_facts")["status"], "failed")

            resume_runner = StubPipelineRunner(store)
            resumed = asyncio.run(
                resume_runner.run(
                    ResearchPipelineConfig(
                        resume_run_id=run_id,
                        from_step="macro_facts",
                        followups_dry_run=True,
                    )
                )
            )

            self.assertEqual(resumed["status"], "passed")
            self.assertEqual(resume_runner.calls[0], "macro_facts")
            self.assertNotIn("preflight", resume_runner.calls)
            self.assertEqual(store.latest_research_pipeline_step(run_id, "preflight")["status"], "reused")
            self.assertEqual(store.latest_research_pipeline_step(run_id, "preflight")["attempt"], 2)
            self.assertEqual(store.latest_research_pipeline_step(run_id, "macro_facts")["status"], "passed")
            self.assertEqual(store.latest_research_pipeline_step(run_id, "macro_facts")["attempt"], 2)

    def test_default_plan_keeps_network_and_ai_steps_disabled(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            runner = StubPipelineRunner(store)

            default_steps = {step.name: step for step in runner.plan(ResearchPipelineConfig())}
            followup_preview_steps = {
                step.name: step for step in runner.plan(ResearchPipelineConfig(followups_dry_run=True))
            }

            self.assertFalse(default_steps["ingestion_run"].enabled)
            self.assertFalse(default_steps["ai_compression"].enabled)
            self.assertFalse(default_steps["run_followups"].enabled)
            self.assertTrue(followup_preview_steps["run_followups"].enabled)
            self.assertTrue(followup_preview_steps["run_followups"].input["dry_run"])

    def test_focus_queries_override_catalog_search_queries(self) -> None:
        source = SourceConfig(
            id="search-global",
            tier="T2",
            category="news",
            mode="firecrawl_search_then_scrape",
            search_queries=["catalog default query"],
            max_results=5,
        )
        scheduler = SourceScheduler(
            TopicWatchlists({}),
            focus_queries=("用户即时问题", "用户即时问题 官方来源"),
        )

        jobs = scheduler.jobs_for_source(source)

        self.assertEqual([job.query for job in jobs], ["用户即时问题", "用户即时问题 官方来源"])

    def test_pipeline_lineage_filter_and_artifact_retention(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            store.insert_research_card(
                {
                    "card_id": "card-a",
                    "pipeline_run_id": "run-a",
                    "event_id": "event-a",
                    "event_key": "event:a",
                    "readiness": "research-ready",
                    "priority": "P1",
                    "freshness_status": "fresh",
                    "time_window": "test",
                    "created_at": "2026-07-09T00:00:00+00:00",
                }
            )
            store.insert_research_card(
                {
                    "card_id": "card-b",
                    "pipeline_run_id": "run-b",
                    "event_id": "event-b",
                    "event_key": "event:b",
                    "readiness": "research-ready",
                    "priority": "P1",
                    "freshness_status": "fresh",
                    "time_window": "test",
                    "created_at": "2026-07-09T00:00:01+00:00",
                }
            )
            store.insert_research_pipeline_run(
                {
                    "run_id": "run-a",
                    "profile": "test",
                    "status": "passed",
                    "triggered_by": "unittest",
                    "config": {},
                    "summary": {},
                    "started_at": "2026-07-09T00:00:00+00:00",
                }
            )
            store.insert_research_pipeline_run(
                {
                    "run_id": "run-b",
                    "profile": "test",
                    "status": "passed",
                    "triggered_by": "unittest",
                    "config": {},
                    "summary": {},
                    "started_at": "2026-07-09T00:00:01+00:00",
                }
            )
            for run_id in ("run-a", "run-b"):
                store.insert_research_pipeline_artifact(
                    {
                        "artifact_id": f"artifact-{run_id}",
                        "run_id": run_id,
                        "step_name": "status_snapshot",
                        "artifact_type": "step-output",
                        "payload": {"run_id": run_id},
                        "created_at": "2026-07-09T00:00:00+00:00",
                    }
                )

            self.assertEqual(
                [row["card_id"] for row in store.list_research_cards(pipeline_run_id="run-a")],
                ["card-a"],
            )
            retention = store.prune_research_pipeline_artifacts(keep_runs=1)

            self.assertEqual(retention["deleted_artifacts"], 1)
            self.assertEqual(
                [row["run_id"] for row in store.list_research_pipeline_artifacts()],
                ["run-b"],
            )


if __name__ == "__main__":
    unittest.main()
