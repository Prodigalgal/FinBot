from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.web.service import FinBotWebApp, create_fastapi_app


class QuantApiTests(unittest.TestCase):
    def test_leverage_preview_blocks_500x(self) -> None:
        with self._client() as client:
            response = client.post(
                "/api/v1/quant/risk/leverage-preview",
                json={
                    "contract": _contract(),
                    "risk": {
                        "side": "SELL",
                        "entry_price": 63_900,
                        "stop_price": 64_539,
                        "risk_budget_usdt": 10,
                        "requested_leverage": 500,
                        "environment": "testnet",
                    },
                },
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "blocked")
        self.assertTrue(any("规格上限" in reason for reason in response.json()["reasons"]))

    def test_execution_backtest_returns_auditable_costs(self) -> None:
        with self._client() as client:
            response = client.post(
                "/api/v1/quant/backtests/execution",
                json={
                    "contract": _contract(),
                    "position": {
                        "side": "BUY",
                        "quantity": 1000,
                        "leverage": 20,
                        "stop_price": 98,
                        "take_profit_price": 102,
                        "environment": "paper",
                    },
                    "bars": [
                        {
                            "timestamp": datetime(2026, 7, 12, tzinfo=timezone.utc).isoformat(),
                            "open": 100,
                            "high": 103,
                            "low": 97,
                            "close": 100,
                        }
                    ],
                },
            )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["exit_reason"], "stop_loss")
        self.assertGreater(payload["fee_usdt"], 0)
        self.assertFalse(payload["methodology"]["partial_fills_included"])

    def test_execution_backtest_rejects_mainnet(self) -> None:
        with self._client() as client:
            response = client.post(
                "/api/v1/quant/backtests/execution",
                json={
                    "contract": _contract(),
                    "position": {
                        "side": "BUY",
                        "quantity": 1000,
                        "leverage": 20,
                        "environment": "mainnet",
                    },
                    "bars": [
                        {
                            "timestamp": datetime(2026, 7, 12, tzinfo=timezone.utc).isoformat(),
                            "open": 100,
                            "high": 101,
                            "low": 99,
                            "close": 100,
                        }
                    ],
                },
            )

        self.assertEqual(response.status_code, 400)
        self.assertIn("paper/testnet/demo", response.json()["detail"])

    def test_oms_read_api_is_available_and_mainnet_is_forbidden(self) -> None:
        with self._client() as client:
            response = client.get("/api/v1/quant/oms/orders")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["orders"], [])
        self.assertFalse(response.json()["policy"]["mainnet_allowed"])

    def test_strategy_validation_api_marks_small_sample_unavailable(self) -> None:
        with self._client() as client:
            response = client.post(
                "/api/v1/quant/backtests/validate",
                json={
                    "control_variant": "control",
                    "variants": [
                        {
                            "variant_id": "control",
                            "observations": [
                                {"timestamp": "2026-07-12T00:00:00Z", "net_return": 0.01},
                                {"timestamp": "2026-07-13T00:00:00Z", "net_return": -0.01},
                            ],
                        }
                    ],
                },
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["walk_forward"]["status"], "unavailable")

    def _client(self) -> TestClient:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        root = Path(temp_dir.name)
        state = FinBotWebApp(
            data_dir=str(root / "data"),
            config_store=RuntimeConfigStore(root),
            ai_config_store=AISitesConfigStore(root),
        )
        return TestClient(create_fastapi_app(state, frontend_dist=None))


def _contract() -> dict[str, object]:
    return {
        "venue": "gate",
        "symbol": "BTC_USDT",
        "contract_multiplier": 0.0001,
        "min_quantity": 1,
        "quantity_step": 1,
        "min_notional_usdt": 1,
        "min_leverage": 1,
        "max_leverage": 200,
        "leverage_step": 1,
        "maintenance_margin_rate": 0.003,
        "taker_fee_rate": 0.00075,
    }


if __name__ == "__main__":
    unittest.main()
