from __future__ import annotations

import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.exchange.account_activity import (
    exchange_source,
    local_account_activity_payload,
    merge_account_activity_payload,
    resolve_activity_query,
)
from finbot.exchange.account_activity_normalizers import bybit_fill_activity
from finbot.exchange.bybit_demo import BybitDemoAdapter, BybitDemoClient
from finbot.exchange.gate_testnet import GateTestnetAdapter, GateTestnetClient
from finbot.storage.sqlite_store import SQLiteStore
from finbot.web.service import FinBotWebApp, create_fastapi_app


FIXED_NOW = datetime(2026, 7, 13, 8, 0, tzinfo=timezone.utc)


class AccountActivityQueryTests(unittest.TestCase):
    def test_query_normalizes_filters_and_rejects_unbounded_pages(self) -> None:
        query = resolve_activity_query(
            range_mode="24h",
            adapter_id="BYBIT_DEMO",
            stage="FILL",
            status="PartiallyFilled",
            symbol="btc_usdt",
            limit=200,
            now=FIXED_NOW,
        )

        self.assertEqual(query.adapter_id, "bybit_demo")
        self.assertEqual(query.stage, "fill")
        self.assertEqual(query.status, "partially_filled")
        self.assertEqual(query.symbol, "BTCUSDT")
        with self.assertRaisesRegex(ValueError, "limit"):
            resolve_activity_query(limit=201, now=FIXED_NOW)


class GateAccountActivityTests(unittest.TestCase):
    def test_gate_reads_time_range_orders_and_fills_as_exchange_facts(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.url.path.endswith("/orders_timerange"):
                return httpx.Response(
                    200,
                    json=[
                        {
                            "id": 101,
                            "contract": "BTC_USDT",
                            "size": 2,
                            "left": 0,
                            "price": "0",
                            "fill_price": "60000",
                            "status": "finished",
                            "finish_as": "filled",
                            "text": "t-finbot-order",
                            "create_time": FIXED_NOW.timestamp() - 120,
                            "finish_time": FIXED_NOW.timestamp() - 119,
                        }
                    ],
                )
            if request.url.path.endswith("/my_trades_timerange"):
                return httpx.Response(
                    200,
                    json=[
                        {
                            "trade_id": "trade-101",
                            "order_id": "101",
                            "contract": "BTC_USDT",
                            "size": 2,
                            "price": "60000",
                            "fee": "-0.5",
                            "role": "taker",
                            "text": "t-finbot-order",
                            "create_time": FIXED_NOW.timestamp() - 119,
                        }
                    ],
                )
            if request.url.path.endswith("/account_book"):
                return httpx.Response(
                    200,
                    json=[
                        {
                            "id": "ledger-101",
                            "trade_id": "trade-101",
                            "contract": "BTC_USDT",
                            "type": "pnl",
                            "change": "12.5",
                            "balance": "1012.5",
                            "time": FIXED_NOW.timestamp() - 118,
                        }
                    ],
                )
            raise AssertionError(str(request.url))

        client = GateTestnetClient(
            "gate-key",
            "gate-secret",
            transport=httpx.MockTransport(handler),
            clock=lambda: FIXED_NOW.timestamp(),
        )
        query = resolve_activity_query(range_mode="24h", now=FIXED_NOW)
        try:
            payload = GateTestnetAdapter(client).fetch_account_activity(query=query)
        finally:
            client.close()

        self.assertEqual(payload["sources"][0]["status"], "ready")
        self.assertTrue(payload["sources"][0]["complete"])
        self.assertEqual([item["stage"] for item in payload["activities"]], ["order", "fill", "account"])
        self.assertTrue(all(item["source_type"] == "exchange" for item in payload["activities"]))
        self.assertEqual(payload["activities"][1]["fee"], -0.5)
        self.assertEqual(payload["activities"][2]["realized_pnl"], 12.5)
        self.assertTrue(all(request.url.params.get("from") for request in requests))


class BybitAccountActivityTests(unittest.TestCase):
    def test_bybit_reads_order_and_execution_history_with_official_window(self) -> None:
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            if request.url.path.endswith("/order/history"):
                rows = [
                    {
                        "orderId": "order-201",
                        "orderLinkId": "finbot-order-201",
                        "symbol": "ETHUSDT",
                        "side": "Sell",
                        "orderType": "Market",
                        "orderStatus": "Filled",
                        "qty": "0.1",
                        "cumExecQty": "0.1",
                        "leavesQty": "0",
                        "avgPrice": "3000",
                        "createdTime": str(int((FIXED_NOW.timestamp() - 120) * 1000)),
                        "updatedTime": str(int((FIXED_NOW.timestamp() - 119) * 1000)),
                    }
                ]
            elif request.url.path.endswith("/execution/list"):
                rows = [
                    {
                        "execId": "exec-201",
                        "orderId": "order-201",
                        "orderLinkId": "finbot-order-201",
                        "symbol": "ETHUSDT",
                        "side": "Sell",
                        "orderType": "Market",
                        "orderQty": "0.1",
                        "execQty": "0.1",
                        "execPrice": "3000",
                        "execFee": "0.18",
                        "execTime": str(int((FIXED_NOW.timestamp() - 119) * 1000)),
                        "isMaker": False,
                    }
                ]
            elif request.url.path.endswith("/position/closed-pnl"):
                rows = [
                    {
                        "orderId": "order-201",
                        "symbol": "ETHUSDT",
                        "side": "Sell",
                        "orderType": "Market",
                        "qty": "0.1",
                        "avgEntryPrice": "3100",
                        "avgExitPrice": "3000",
                        "closedPnl": "10",
                        "openFee": "0.1",
                        "closeFee": "0.1",
                        "updatedTime": str(int((FIXED_NOW.timestamp() - 118) * 1000)),
                    }
                ]
            else:
                raise AssertionError(str(request.url))
            return httpx.Response(
                200,
                json={"retCode": 0, "retMsg": "OK", "result": {"list": rows, "nextPageCursor": ""}},
            )

        client = BybitDemoClient(
            "bybit-key",
            "bybit-secret",
            transport=httpx.MockTransport(handler),
            clock=lambda: FIXED_NOW.timestamp(),
        )
        query = resolve_activity_query(range_mode="7d", symbol="ETHUSDT", now=FIXED_NOW)
        try:
            payload = BybitDemoAdapter(client).fetch_account_activity(query=query)
        finally:
            client.close()

        self.assertEqual(payload["sources"][0]["status"], "ready")
        self.assertTrue(payload["sources"][0]["complete"])
        self.assertEqual({item["stage"] for item in payload["activities"]}, {"order", "fill", "account"})
        self.assertEqual(next(item for item in payload["activities"] if item["stage"] == "fill")["fee"], 0.18)
        self.assertEqual(next(item for item in payload["activities"] if item["stage"] == "account")["realized_pnl"], 10.0)
        self.assertTrue(all(request.url.params["category"] == "linear" for request in requests))
        self.assertTrue(all(request.url.params["startTime"] for request in requests))
        self.assertTrue(all(request.url.params["endTime"] for request in requests))


class LocalAccountActivityTests(unittest.TestCase):
    def test_local_proposal_is_not_counted_as_exchange_order_or_fill(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.db")
            store.init_schema()
            store.insert_paper_order_proposal(
                {
                    "proposal_id": "proposal-1",
                    "report_id": "report-1",
                    "advice_id": "advice-1",
                    "provider": "gate",
                    "market_type": "perpetual",
                    "symbol": "BTC_USDT",
                    "action": "BUY",
                    "status": "planned",
                    "execution_mode": "paper-only",
                    "created_at": "2026-07-13T07:00:00+00:00",
                }
            )
            query = resolve_activity_query(range_mode="24h", now=FIXED_NOW)
            local = local_account_activity_payload(store, query)

        payload = merge_account_activity_payload(
            query=query,
            local_payload=local,
            exchange_payload={"sources": [], "activities": []},
        )

        self.assertEqual(payload["summary"]["proposal_count"], 1)
        self.assertEqual(payload["summary"]["exchange_order_count"], 0)
        self.assertEqual(payload["summary"]["exchange_fill_count"], 0)
        self.assertTrue(payload["policy"]["local_status_is_not_exchange_confirmation"])

    def test_exchange_fill_correlates_to_local_execution_without_changing_source(self) -> None:
        query = resolve_activity_query(range_mode="24h", now=FIXED_NOW)
        local_execution = {
            "activity_id": "local-1",
            "source_type": "local",
            "source_id": "local_audit",
            "adapter_id": "bybit_demo",
            "stage": "execution",
            "status": "submitted",
            "occurred_at": "2026-07-13T07:00:00+00:00",
            "symbol": "ETHUSDT",
            "client_order_id": "finbot-order-201",
            "exchange_order_id": "order-201",
            "paper_execution_id": "execution-1",
            "decision_id": "decision-1",
            "loop_run_id": "loop-1",
        }
        exchange_fill = bybit_fill_activity(
            {
                "execId": "exec-201",
                "orderId": "order-201",
                "orderLinkId": "finbot-order-201",
                "symbol": "ETHUSDT",
                "side": "Sell",
                "execQty": "0.1",
                "execPrice": "3000",
                "execTime": str(int((FIXED_NOW.timestamp() - 60) * 1000)),
            }
        )
        remote_source = exchange_source(
            adapter_id="bybit_demo",
            display_name="Bybit Demo",
            status="ready",
            complete=True,
            fetched_record_count=1,
            matched_record_count=1,
            coverage_start_at=query.start_at,
            coverage_end_at=query.end_at,
            message="test",
        )

        payload = merge_account_activity_payload(
            query=query,
            local_payload={"sources": [], "activities": [local_execution]},
            exchange_payload={"sources": [remote_source], "activities": [exchange_fill]},
        )
        correlated = next(item for item in payload["activities"] if item["source_type"] == "exchange")

        self.assertEqual(correlated["paper_execution_id"], "execution-1")
        self.assertEqual(correlated["decision_id"], "decision-1")
        self.assertEqual(correlated["source_type"], "exchange")


class AccountActivityApiTests(unittest.TestCase):
    def test_api_returns_local_truth_when_exchange_history_is_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            with patch(
                "finbot.web.service.fetch_exchange_account_activity",
                return_value={"sources": [], "activities": []},
            ):
                with TestClient(create_fastapi_app(state, frontend_dist=None)) as client:
                    response = client.get(
                        "/api/v1/exchange-account-activity",
                        params={"range_mode": "7d", "adapter_id": "local", "limit": 20},
                    )
                    invalid = client.get(
                        "/api/v1/exchange-account-activity",
                        params={"stage": "unknown"},
                    )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["sources"][0]["source_id"], "local_audit")
        self.assertEqual(invalid.status_code, 400)
        self.assertNotIn("api_key", response.text.lower())
        self.assertNotIn("secret", response.text.lower())


if __name__ == "__main__":
    unittest.main()
