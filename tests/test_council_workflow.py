from __future__ import annotations

import threading
import unittest
from typing import Any

from finbot.config.ai_sites import DEFAULT_PRODUCT_COUNCIL_TEMPLATE
from finbot.council.models import CouncilTemplate
from finbot.council.orchestrator import CouncilOrchestrator, CouncilTurnRequest


class CouncilWorkflowTests(unittest.TestCase):
    def test_legacy_template_materializes_risk_aggregation_workflow(self) -> None:
        template = CouncilTemplate.from_payload(DEFAULT_PRODUCT_COUNCIL_TEMPLATE)

        layers = template.workflow.execution_layers(template.roles)

        self.assertEqual(template.workflow.version, 1)
        self.assertEqual(len(template.workflow.nodes), 6)
        self.assertEqual(len(template.workflow.edges), 7)
        self.assertEqual(
            [[role.role_id for role in layer] for layer in layers],
            [
                ["bull_researcher", "bear_researcher", "market_structure"],
                ["risk_controller"],
            ],
        )
        self.assertEqual(template.workflow.chair_input_role_ids(template.roles), ("risk_controller",))
        self.assertIn("workflow", template.to_dict())

    def test_workflow_rejects_cycle(self) -> None:
        payload = CouncilTemplate.from_payload(DEFAULT_PRODUCT_COUNCIL_TEMPLATE).to_dict()
        payload["workflow"]["edges"].append(
            {
                "edge_id": "edge_cycle",
                "source_node_id": "node_risk_controller",
                "target_node_id": "node_bull_researcher",
            }
        )

        with self.assertRaisesRegex(ValueError, "directed acyclic graph"):
            CouncilTemplate.from_payload(payload)

    def test_orchestrator_broadcasts_history_and_routes_current_round_by_edges(self) -> None:
        template = CouncilTemplate.from_payload(DEFAULT_PRODUCT_COUNCIL_TEMPLATE)
        requests: dict[tuple[int, str], CouncilTurnRequest] = {}
        lock = threading.Lock()

        def execute(request: CouncilTurnRequest) -> dict[str, Any]:
            with lock:
                requests[(request.round_index, request.role.role_id)] = request
            return {
                "message_id": f"message-{request.round_index}-{request.role.role_id}",
                "round_index": request.round_index,
                "phase_id": request.phase.phase_id,
                "message_type": request.phase.message_type,
                "agent_role": request.role.role_id,
                "stance": request.role.stance,
                "status": "completed",
                "content": {"role": request.role.role_id},
            }

        result = CouncilOrchestrator(execute).run(
            council_id="workflow-test",
            template=template,
            shared_context={"candidate": "BTCUSDT"},
            rounds=2,
        )

        first_bull = requests[(1, "bull_researcher")]
        first_risk = requests[(1, "risk_controller")]
        second_bull = requests[(2, "bull_researcher")]
        second_risk = requests[(2, "risk_controller")]
        self.assertEqual(first_bull.prior_messages, ())
        self.assertEqual(
            {message["agent_role"] for message in first_risk.prior_messages},
            {"bull_researcher", "bear_researcher", "market_structure"},
        )
        self.assertEqual(len(second_bull.prior_messages), 4)
        self.assertEqual({message["round_index"] for message in second_bull.prior_messages}, {1})
        self.assertEqual(len(second_risk.prior_messages), 7)
        self.assertEqual(
            set(second_risk.upstream_role_ids),
            {"bull_researcher", "bear_researcher", "market_structure"},
        )
        self.assertEqual(result["summary"]["workflow_layer_count"], 2)
        self.assertEqual(result["round_summaries"][0]["workflow_layer_count"], 2)


if __name__ == "__main__":
    unittest.main()
