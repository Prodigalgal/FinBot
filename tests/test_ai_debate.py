from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from typing import Any

from finbot.ai.openai_compatible import LLMCompletion, OpenAICompatibleProvider
from finbot.autonomous.ai_debate import AIDebateConfig, AIDebateCouncilRunner, _candidate_subset
from finbot.config.ai_sites import AISitesConfigStore
from finbot.storage.sqlite_store import SQLiteStore


class FakeDebateClient:
    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []

    def complete(
        self,
        provider: OpenAICompatibleProvider,
        protocol: str,
        system_prompt: str,
        user_prompt: str,
        require_json: bool = True,
    ) -> LLMCompletion:
        payload = json.loads(user_prompt)
        self.requests.append({"payload": payload, "system_prompt": system_prompt})
        role = payload.get("agent_role")
        candidates = payload.get("candidates") or []
        candidate = candidates[0]
        if role == "chair_arbiter":
            content = {
                "council_status": "passed",
                "debate_summary": ["研究确认与多周期行情均支持试探性方向建议。"],
                "decisions": [
                    {
                        "candidate_id": candidate["candidate_id"],
                        "symbol": candidate["symbol"],
                        "provider": candidate["provider"],
                        "market_type": candidate["market_type"],
                        "action": "BUY",
                        "confidence": 0.76,
                        "score": 84.0,
                        "entry_reference": 100.0,
                        "target_price": 104.0,
                        "invalidation_price": 98.0,
                        "position_sizing": {
                            "risk_per_trade_pct": 0.5,
                            "max_position_notional_pct": 5.0,
                            "sizing_policy": "advisory-only-user-must-confirm",
                        },
                        "rationale": ["研究确认、行情结构和风险回报满足人工复核条件。"],
                        "risk_warnings": ["需要人工确认，且不得自动交易。"],
                        "evidence_refs": candidate.get("evidence_refs", []),
                        "invalidation_conditions": ["跌破 invalidation_price 后方向建议失效。"],
                    }
                ],
                "policy_gate": {
                    "execution_allowed": False,
                    "order_api_allowed": False,
                    "human_confirmation_required": True,
                },
            }
        else:
            content = {
                "agent_role": role,
                "stance": payload.get("stance"),
                "overall_view": f"{role} view",
                "candidate_assessments": [
                    {
                        "candidate_id": candidate["candidate_id"],
                        "symbol": candidate["symbol"],
                        "provider": candidate["provider"],
                        "market_type": candidate["market_type"],
                        "action_bias": "BUY",
                        "confidence": 0.7,
                        "arguments": ["unit-test"],
                        "counter_arguments": [],
                        "risk_flags": [],
                        "evidence_refs": candidate.get("evidence_refs", []),
                    }
                ],
                "questions_for_other_agents": [],
            }
        return LLMCompletion(
            provider=provider.name,
            protocol=protocol,
            model=provider.model_for(protocol) or "fake-model",
            content=json.dumps(content, ensure_ascii=False),
            usage={"total_tokens": 1},
        )


class CandidateSubsetTests(unittest.TestCase):
    def test_prioritizes_confirmed_executable_products_before_higher_scored_unconfirmed_products(self) -> None:
        confirmed = {
            "status": "market-confirmed",
            "market_confirmation": {
                "valid": True,
                "provider_count": 2,
                "action": "SELL",
                "minimum_confidence": 0.65,
                "maximum_price_divergence_pct": 0.2,
            },
        }
        candidates = [
            {
                "candidate_id": "high-score-unconfirmed",
                "normalized_symbol": "LABUSDT",
                "market_type": "linear",
                "market_action": "SELL",
                "research_context": {"status": "unconfirmed"},
            },
            {
                "candidate_id": "lower-score-confirmed",
                "normalized_symbol": "SOLUSDT",
                "market_type": "linear",
                "market_action": "SELL",
                "research_context": confirmed,
            },
        ]

        selected = _candidate_subset(candidates, 2)

        self.assertEqual(
            [candidate["candidate_id"] for candidate in selected],
            ["lower-score-confirmed", "high-score-unconfirmed"],
        )

    def test_prefers_distinct_executable_products_for_limited_debate_slots(self) -> None:
        candidates = [
            {"candidate_id": "btc-spot", "normalized_symbol": "BTCUSDT", "market_type": "spot"},
            {"candidate_id": "btc-linear", "normalized_symbol": "BTCUSDT", "market_type": "linear"},
            {"candidate_id": "sol-spot", "normalized_symbol": "SOLUSDT", "market_type": "spot"},
            {"candidate_id": "sol-perpetual", "normalized_symbol": "SOLUSDT", "market_type": "perpetual"},
            {"candidate_id": "eth-linear", "normalized_symbol": "ETHUSDT", "market_type": "linear"},
        ]

        selected = _candidate_subset(candidates, 3)

        self.assertEqual(
            [candidate["candidate_id"] for candidate in selected],
            ["btc-linear", "sol-perpetual", "eth-linear"],
        )

    def test_falls_back_to_distinct_spot_then_duplicate_products(self) -> None:
        candidates = [
            {"candidate_id": "btc-spot-a", "normalized_symbol": "BTC_USDT", "market_type": "spot"},
            {"candidate_id": "btc-spot-b", "normalized_symbol": "BTCUSDT", "market_type": "spot"},
            {"candidate_id": "sol-spot", "normalized_symbol": "SOLUSDT", "market_type": "spot"},
        ]

        selected = _candidate_subset(candidates, 3)

        self.assertEqual(
            [candidate["candidate_id"] for candidate in selected],
            ["btc-spot-a", "sol-spot", "btc-spot-b"],
        )


class AIDebateTests(unittest.TestCase):
    def test_debate_persists_messages_and_trade_decision(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            ai_store = AISitesConfigStore(root)
            client = FakeDebateClient()
            runner = AIDebateCouncilRunner(
                store=store,
                providers=_providers(),
                ai_store=ai_store,
                client=client,
            )
            candidates_report = {"candidates": [_candidate(matched_research=True)]}
            config = AIDebateConfig(
                loop_run_id="loop-a",
                research_pipeline_run_id="research-a",
                operator_report_id="operator-a",
                user_query="分析 BTC ETF 资金流",
                min_confidence=0.58,
                require_research_confirmation=True,
            )

            debate = runner.run_debate(candidates_report, config)
            synthesis = runner.synthesize_decisions(debate, candidates_report, config)

            councils = store.list_ai_debate_councils(loop_run_id="loop-a")
            messages = store.list_ai_debate_messages(debate_id=debate["debate_id"])
            decisions = store.list_ai_trade_decisions(loop_run_id="loop-a")

        self.assertEqual(debate["status"], "ready_for_synthesis")
        self.assertEqual(synthesis["status"], "passed")
        self.assertEqual(len(messages), 13)
        self.assertEqual({row["round_index"] for row in messages}, {1, 2, 3, 4})
        analysis_messages = [row for row in messages if row["round_index"] <= 3]
        self.assertEqual(
            {row["phase_id"] for row in analysis_messages},
            {"independent_analysis", "cross_examination", "position_revision"},
        )
        self.assertTrue(
            all(json.loads(row["reply_to_json"]) for row in analysis_messages if row["round_index"] > 1)
        )
        self.assertEqual(
            {row["agent_role"] for row in analysis_messages if row["round_index"] == 1},
            {"bull_researcher", "bear_researcher", "market_structure", "risk_controller"},
        )
        self.assertEqual(councils[0]["status"], "passed")
        self.assertEqual(councils[0]["template_id"], "product_advisory")
        self.assertEqual(json.loads(councils[0]["payload_json"])["user_query"], "分析 BTC ETF 资金流")
        self.assertTrue(all(request["payload"].get("response_language") == "zh-CN" for request in client.requests))
        self.assertTrue(all("自然语言字段必须使用简体中文" in request["system_prompt"] for request in client.requests))
        self.assertEqual(decisions[0]["action"], "BUY")
        self.assertIn('"execution_allowed": false', decisions[0]["policy_json"])

    def test_policy_gate_downgrades_direction_without_research_confirmation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            runner = AIDebateCouncilRunner(
                store=store,
                providers=_providers(),
                ai_store=AISitesConfigStore(root),
                client=(client := FakeDebateClient()),
            )
            candidates_report = {"candidates": [_candidate(matched_research=False)]}
            config = AIDebateConfig(
                loop_run_id="loop-b",
                research_pipeline_run_id="research-b",
                operator_report_id="operator-b",
                min_confidence=0.58,
                require_research_confirmation=True,
            )

            debate = runner.run_debate(candidates_report, config)
            synthesis = runner.synthesize_decisions(debate, candidates_report, config)

        self.assertEqual(synthesis["ai_decisions"][0]["action"], "WATCH")
        self.assertIn("directional advice requires matched research confirmation", synthesis["ai_decisions"][0]["risk_warnings"])
        self.assertFalse(synthesis["ai_decisions"][0]["policy"]["execution_allowed"])
        self.assertTrue(all(request["payload"].get("response_language") == "zh-CN" for request in client.requests))
        chair_content = synthesis["chair_message"]["content"]
        self.assertIn("major_disagreements", chair_content)
        self.assertIn("missing_evidence", chair_content)
        self.assertTrue(chair_content["missing_evidence"])

    def test_policy_gate_rejects_matched_but_unresolved_research(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            runner = AIDebateCouncilRunner(
                store=store,
                providers=_providers(),
                ai_store=AISitesConfigStore(root),
                client=FakeDebateClient(),
            )
            candidate = _candidate(matched_research=True)
            candidate["research_context"]["status"] = "needs-followup"
            candidate["research_context"]["matched_items"] = [{"event_key": "btc", "status": "needs-followup"}]
            candidates_report = {"candidates": [candidate]}
            config = AIDebateConfig(
                loop_run_id="loop-unresolved",
                research_pipeline_run_id="research-unresolved",
                operator_report_id="operator-unresolved",
                require_research_confirmation=True,
            )

            debate = runner.run_debate(candidates_report, config)
            synthesis = runner.synthesize_decisions(debate, candidates_report, config)

        self.assertEqual(synthesis["ai_decisions"][0]["action"], "WATCH")
        self.assertIn("directional advice requires matched research confirmation", synthesis["ai_decisions"][0]["risk_warnings"])

    def test_policy_gate_accepts_valid_cross_venue_market_confirmation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = SQLiteStore(root / "finbot.sqlite3")
            store.init_schema()
            runner = AIDebateCouncilRunner(
                store=store,
                providers=_providers(),
                ai_store=AISitesConfigStore(root),
                client=FakeDebateClient(),
            )
            candidate = _candidate(matched_research=False)
            candidate["research_context"] = {
                "status": "market-confirmed",
                "matched_items_count": 0,
                "matched_items": [],
                "market_confirmation": {
                    "valid": True,
                    "provider_count": 2,
                    "action": "BUY",
                    "minimum_confidence": 0.70,
                    "maximum_price_divergence_pct": 0.1,
                },
            }
            candidates_report = {"candidates": [candidate]}
            config = AIDebateConfig(
                loop_run_id="loop-market-confirmed",
                research_pipeline_run_id="research-market-confirmed",
                operator_report_id="operator-market-confirmed",
                require_research_confirmation=True,
            )

            debate = runner.run_debate(candidates_report, config)
            synthesis = runner.synthesize_decisions(debate, candidates_report, config)

        self.assertEqual(synthesis["ai_decisions"][0]["action"], "BUY")
        self.assertEqual(synthesis["chair_message"]["content"]["missing_evidence"], [])


def _candidate(matched_research: bool) -> dict[str, Any]:
    return {
        "candidate_id": "candidate-a",
        "source": "operator_workbench",
        "symbol": "BTCUSDT",
        "normalized_symbol": "BTCUSDT",
        "provider": "binance",
        "market_type": "spot",
        "status": "candidate",
        "market_action": "BUY",
        "market_confidence": 0.72,
        "score": 72.0,
        "horizon": "1h/4h/1d-context",
        "levels": {
            "entry_reference": 100.0,
            "target_price": 104.0,
            "invalidation_price": 98.0,
        },
        "metrics": {"timeframe_alignment": {"direction": "bullish", "average_score": 1.3}},
        "research_context": {
            "source": "phase4.1-research-council",
            "status": "passed" if matched_research else "empty",
            "matched_items_count": 1 if matched_research else 0,
            "matched_items": [{"event_key": "btc"}] if matched_research else [],
        },
        "evidence_refs": ["council:council-a", "market:binance:BTCUSDT"],
    }


def _providers() -> dict[str, OpenAICompatibleProvider]:
    return {
        "deepseek": OpenAICompatibleProvider(
            name="deepseek",
            api_key="unit-test-key",
            base_url="https://deepseek.example.test/v1",
            chat_model="deepseek-v4-flash",
            responses_model=None,
        ),
        "mimo": OpenAICompatibleProvider(
            name="mimo",
            api_key="unit-test-key",
            base_url="https://mimo.example.test/v1",
            chat_model="mimo-v2.5-pro",
            responses_model=None,
        ),
        "sub2api": OpenAICompatibleProvider(
            name="sub2api",
            api_key="unit-test-key",
            base_url="https://sub2api.example.test/v1",
            chat_model=None,
            responses_model="gpt-5.6-terra",
        ),
    }


if __name__ == "__main__":
    unittest.main()
