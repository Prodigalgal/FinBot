from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.exchange.account_snapshot import account_snapshot_payload, resolve_pnl_window
from finbot.exchange.bybit_demo import BybitDemoAdapter, BybitDemoClient
from finbot.exchange.gate_testnet import GateTestnetAdapter, GateTestnetClient
from finbot.exchange.runtime import _safe_account_error
from finbot.web.service import FinBotWebApp, create_fastapi_app


FIXED_NOW = datetime(2026, 7, 12, 12, 0, tzinfo=timezone.utc)


class AccountPnlWindowTests(unittest.TestCase):
    def test_presets_and_custom_ranges_are_explicit(self) -> None:
        preset = resolve_pnl_window("7d", now=FIXED_NOW)
        custom = resolve_pnl_window(
            "custom",
            start_at="2026-07-01T00:00:00Z",
            end_at="2026-07-05T00:00:00Z",
            now=FIXED_NOW,
        )

        self.assertEqual(preset.mode, "7d")
        self.assertEqual((preset.end_at - preset.start_at).days, 7)
        self.assertEqual(custom.start_at.isoformat(), "2026-07-01T00:00:00+00:00")
        with self.assertRaisesRegex(ValueError, "必须同时提供"):
            resolve_pnl_window("custom", start_at="2026-07-01T00:00:00Z", now=FIXED_NOW)
        with self.assertRaisesRegex(ValueError, "不能超过"):
            resolve_pnl_window(
                "custom",
                start_at="2025-01-01T00:00:00Z",
                end_at="2026-07-01T00:00:00Z",
                now=FIXED_NOW,
            )

    def test_provider_errors_are_human_readable_and_redacted(self) -> None:
        descriptor = {
            "adapter_id": "bybit_demo",
            "api_key": "sensitive-key",
            "api_secret": "sensitive-secret",
        }
        error = RuntimeError(
            "sensitive-key: The Amazon CloudFront distribution is configured to block access from your country"
        )

        message = _safe_account_error(error, descriptor)

        self.assertEqual(message, "当前代理出口被 Bybit 地区策略拦截")
        self.assertNotIn("sensitive-key", message)


class GateAccountSnapshotTests(unittest.TestCase):
    def test_gate_normalizes_balance_positions_and_selected_range_pnl(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.url.path.endswith("/accounts"):
                return httpx.Response(
                    200,
                    json={
                        "total": "1000",
                        "unrealised_pnl": "12.5",
                        "available": "870",
                        "position_margin": "100",
                        "order_margin": "5",
                        "maintenance_margin": "20",
                        "currency": "USDT",
                        "margin_mode": 0,
                    },
                )
            if request.url.path.endswith("/positions"):
                return httpx.Response(
                    200,
                    json=[
                        {
                            "contract": "BTC_USDT",
                            "size": -2,
                            "leverage": "5",
                            "value": "120",
                            "margin": "24",
                            "entry_price": "61000",
                            "mark_price": "60000",
                            "liq_price": "70000",
                            "unrealised_pnl": "12.5",
                            "realised_pnl": "3",
                            "update_time": 1_720_000_000,
                        }
                    ],
                )
            if request.url.path.endswith("/account_book"):
                return httpx.Response(
                    200,
                    json=[
                        {"type": "pnl", "change": "5"},
                        {"type": "fee", "change": "-1"},
                        {"type": "fund", "change": "0.5"},
                        {"type": "dnw", "change": "1000"},
                    ],
                )
            raise AssertionError(str(request.url))

        client = GateTestnetClient(
            "gate-key",
            "gate-secret",
            transport=httpx.MockTransport(handler),
            clock=lambda: FIXED_NOW.timestamp(),
        )
        try:
            snapshot = GateTestnetAdapter(client).fetch_account_snapshot(
                pnl_window=resolve_pnl_window("24h", now=FIXED_NOW),
            )
        finally:
            client.close()

        payload = snapshot.to_dict()
        self.assertEqual(payload["status"], "ready")
        self.assertEqual(payload["total_equity_usdt"], 1012.5)
        self.assertEqual(payload["margin_used_usdt"], 105.0)
        self.assertEqual(payload["realized_pnl_usdt"], 4.5)
        self.assertEqual(payload["realized_pnl_record_count"], 3)
        self.assertEqual(payload["positions"][0]["side"], "short")
        self.assertAlmostEqual(payload["positions"][0]["roe_pct"], 52.083333, places=6)
        account_book_request = next(request for request in requests if request.url.path.endswith("/account_book"))
        self.assertEqual(
            account_book_request.url.params["from"],
            str(int((FIXED_NOW - timedelta(hours=24)).timestamp())),
        )
        self.assertNotIn("gate-key", str(payload))
        self.assertNotIn("gate-secret", str(payload))


class BybitAccountSnapshotTests(unittest.TestCase):
    def test_bybit_uses_cumulative_realized_pnl_for_all_history(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.url.path.endswith("/wallet-balance"):
                return httpx.Response(200, json=_bybit_wallet_payload())
            if request.url.path.endswith("/position/list"):
                return httpx.Response(200, json=_bybit_position_payload())
            raise AssertionError(str(request.url))

        client = BybitDemoClient(
            "bybit-key",
            "bybit-secret",
            transport=httpx.MockTransport(handler),
            clock=lambda: FIXED_NOW.timestamp(),
        )
        try:
            snapshot = BybitDemoAdapter(client).fetch_account_snapshot(
                pnl_window=resolve_pnl_window("all", now=FIXED_NOW),
            )
        finally:
            client.close()

        payload = snapshot.to_dict()
        self.assertEqual(payload["status"], "ready")
        self.assertEqual(payload["realized_pnl_usdt"], 18.75)
        self.assertEqual(payload["unrealized_pnl_usdt"], 6.25)
        self.assertEqual(payload["positions"][0]["side"], "long")
        self.assertFalse(any(request.url.path.endswith("/closed-pnl") for request in requests))

    def test_bybit_splits_selected_ranges_into_seven_day_windows(self) -> None:
        closed_pnl_requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/wallet-balance"):
                return httpx.Response(200, json=_bybit_wallet_payload())
            if request.url.path.endswith("/position/list"):
                return httpx.Response(200, json=_bybit_position_payload())
            if request.url.path.endswith("/closed-pnl"):
                closed_pnl_requests.append(request)
                return httpx.Response(
                    200,
                    json={
                        "retCode": 0,
                        "retMsg": "OK",
                        "result": {"list": [{"closedPnl": "2.5"}], "nextPageCursor": ""},
                    },
                )
            raise AssertionError(str(request.url))

        client = BybitDemoClient(
            "bybit-key",
            "bybit-secret",
            transport=httpx.MockTransport(handler),
            clock=lambda: FIXED_NOW.timestamp(),
        )
        try:
            snapshot = BybitDemoAdapter(client).fetch_account_snapshot(
                pnl_window=resolve_pnl_window(
                    "custom",
                    start_at="2026-07-01T00:00:00Z",
                    end_at="2026-07-09T00:00:00Z",
                    now=FIXED_NOW,
                ),
            )
        finally:
            client.close()

        self.assertEqual(snapshot.realized_pnl_usdt, 5.0)
        self.assertEqual(snapshot.realized_pnl_record_count, 2)
        self.assertEqual(len(closed_pnl_requests), 2)


class ExchangeAccountApiTests(unittest.TestCase):
    def test_api_passes_custom_window_and_never_exposes_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            window = resolve_pnl_window(
                "custom",
                start_at="2026-07-01T00:00:00Z",
                end_at="2026-07-02T00:00:00Z",
                now=FIXED_NOW,
            )
            response_payload = account_snapshot_payload([], pnl_window=window, generated_at=FIXED_NOW.isoformat())
            with patch("finbot.web.service.fetch_exchange_accounts", return_value=response_payload) as fetch:
                with TestClient(create_fastapi_app(state, frontend_dist=None)) as client:
                    response = client.get(
                        "/api/v1/exchange-accounts",
                        params={
                            "pnl_range": "custom",
                            "start_at": "2026-07-01T00:00:00Z",
                            "end_at": "2026-07-02T00:00:00Z",
                        },
                    )
                    invalid = client.get("/api/v1/exchange-accounts", params={"pnl_range": "custom"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["pnl_window"]["mode"], "custom")
        self.assertEqual(invalid.status_code, 400)
        passed_window = fetch.call_args.kwargs["pnl_window"]
        self.assertEqual(passed_window.start_at.isoformat(), "2026-07-01T00:00:00+00:00")
        self.assertNotIn("api_key", response.text.lower())
        self.assertNotIn("secret", response.text.lower())


def _bybit_wallet_payload() -> dict[str, object]:
    return {
        "retCode": 0,
        "retMsg": "OK",
        "result": {
            "list": [
                {
                    "accountType": "UNIFIED",
                    "totalEquity": "1006.25",
                    "totalWalletBalance": "1000",
                    "totalAvailableBalance": "850",
                    "totalInitialMargin": "120",
                    "totalMaintenanceMargin": "18",
                    "totalPerpUPL": "6.25",
                    "coin": [
                        {
                            "coin": "USDT",
                            "equity": "1006.25",
                            "walletBalance": "1000",
                            "cumRealisedPnl": "18.75",
                            "unrealisedPnl": "6.25",
                        }
                    ],
                }
            ]
        },
    }


def _bybit_position_payload() -> dict[str, object]:
    return {
        "retCode": 0,
        "retMsg": "OK",
        "result": {
            "list": [
                {
                    "symbol": "BTCUSDT",
                    "side": "Buy",
                    "size": "0.01",
                    "leverage": "5",
                    "avgPrice": "60000",
                    "markPrice": "60625",
                    "liqPrice": "50000",
                    "positionValue": "606.25",
                    "positionIM": "121.25",
                    "unrealisedPnl": "6.25",
                    "cumRealisedPnl": "1.5",
                }
            ],
            "nextPageCursor": "",
        },
    }


if __name__ == "__main__":
    unittest.main()
