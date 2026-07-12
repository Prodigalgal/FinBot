from __future__ import annotations

import hashlib
import hmac
import json
import tempfile
import unittest
from pathlib import Path

import httpx

from finbot.exchange.bybit_demo import BYBIT_DEMO_API_BASE, BybitDemoAdapter, BybitDemoClient
from finbot.exchange.gate_testnet import GATE_TESTNET_API_BASE, GateTestnetAdapter, GateTestnetClient
from finbot.exchange.paper_execution import MultiExchangePaperExecutionEngine, PaperExecutionPolicy
from finbot.instruments.models import CatalogInstrument
from finbot.storage.sqlite_store import SQLiteStore


CAPTURED_AT = "2026-07-11T00:00:00+00:00"


def _seed_perpetual_catalog(store: SQLiteStore) -> None:
    store.init_schema()
    gate = CatalogInstrument(
        provider="gate",
        market_type="perpetual",
        symbol="BTC_USDT",
        base_asset="BTC",
        quote_asset="USDT",
        settle_asset="USDT",
        captured_at=CAPTURED_AT,
        contract=True,
        linear=True,
        contract_size=0.0001,
        tick_size=0.1,
        amount_step=1,
        min_amount=1,
        min_notional=6,
        raw={"status": "trading", "order_size_max": 1_000_000},
        market_snapshot={
            "last_price": 60_000,
            "bid": 59_999.9,
            "ask": 60_000.1,
            "turnover_24h": 100_000_000,
        },
    )
    bybit = CatalogInstrument(
        provider="bybit",
        market_type="linear",
        symbol="BTCUSDT",
        base_asset="BTC",
        quote_asset="USDT",
        settle_asset="USDT",
        captured_at=CAPTURED_AT,
        contract=True,
        linear=True,
        contract_size=1,
        tick_size=0.1,
        amount_step=0.001,
        min_amount=0.001,
        min_notional=5,
        raw={
            "status": "Trading",
            "lotSizeFilter": {
                "qtyStep": "0.001",
                "minOrderQty": "0.001",
                "minNotionalValue": "5",
                "maxMktOrderQty": "100",
            },
        },
        market_snapshot={
            "last_price": 60_000,
            "bid": 59_999.9,
            "ask": 60_000.1,
            "turnover_24h": 120_000_000,
        },
    )
    store.sync_instrument_catalog("gate", "perpetual", [gate.to_record()], CAPTURED_AT)
    store.sync_instrument_catalog("bybit", "linear", [bybit.to_record()], CAPTURED_AT)


def _decision() -> dict[str, object]:
    return {
        "decision_id": "decision-btc-long",
        "provider": "gate",
        "market_type": "perpetual",
        "symbol": "BTC_USDT",
        "normalized_symbol": "BTCUSDT",
        "action": "BUY",
        "status": "candidate",
        "human_review_status": "approved",
        "confidence": 0.82,
        "score": 90,
        "entry_reference": 60_000,
        "target_price": 63_000,
        "invalidation_price": 58_000,
    }


def _portfolio_risk(status: str = "passed") -> dict[str, object]:
    return {"risk_gate": {"status": status}}


def _governance() -> dict[str, object]:
    return {"summary": {"governance_status": "passed"}}


class SigningContractTests(unittest.TestCase):
    def test_gate_signature_matches_api_v4_contract_and_rejects_real_host(self) -> None:
        client = GateTestnetClient("gate-key", "gate-secret", transport=httpx.MockTransport(lambda request: httpx.Response(200, json={})))
        body = '{"contract":"BTC_USDT","size":1}'
        headers = client.signing_headers("POST", "/futures/usdt/orders", "", body, "1700000000")
        body_hash = hashlib.sha512(body.encode("utf-8")).hexdigest()
        canonical = f"POST\n/api/v4/futures/usdt/orders\n\n{body_hash}\n1700000000"
        expected = hmac.new(b"gate-secret", canonical.encode("utf-8"), hashlib.sha512).hexdigest()
        client.close()

        self.assertEqual(headers["SIGN"], expected)
        self.assertEqual(headers["KEY"], "gate-key")
        with self.assertRaisesRegex(ValueError, "TestNet host"):
            GateTestnetClient("key", "secret", base_url="https://api.gateio.ws/api/v4")

    def test_bybit_signature_matches_v5_contract_and_rejects_mainnet_private_host(self) -> None:
        client = BybitDemoClient("bybit-key", "bybit-secret", transport=httpx.MockTransport(lambda request: httpx.Response(200, json={})))
        body = '{"category":"linear"}'
        headers = client.signing_headers(body, "1700000000000")
        canonical = f"1700000000000bybit-key5000{body}"
        expected = hmac.new(b"bybit-secret", canonical.encode("utf-8"), hashlib.sha256).hexdigest()
        client.close()

        self.assertEqual(headers["X-BAPI-SIGN"], expected)
        self.assertEqual(headers["X-BAPI-API-KEY"], "bybit-key")
        with self.assertRaisesRegex(ValueError, "Demo host"):
            BybitDemoClient("key", "secret", base_url="https://api.bybit.com")


class MultiExchangePaperExecutionTests(unittest.TestCase):
    def test_robot_approved_decision_does_not_require_human_review(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_perpetual_catalog(store)
            engine = MultiExchangePaperExecutionEngine(store, (GateTestnetAdapter(None),))
            decision = _decision()
            decision.pop("human_review_status")

            report = engine.execute(
                loop_run_id="loop-robot-approved",
                decisions=[decision],
                portfolio_risk=_portfolio_risk(),
                ai_governance=_governance(),
                policy=PaperExecutionPolicy(submit_orders=False),
            )

        self.assertEqual(report["status"], "passed")
        self.assertEqual(report["summary"]["execution_count"], 1)
        self.assertEqual(report["executions"][0]["status"], "dry_run")

    def test_risk_warning_does_not_block_simulation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_perpetual_catalog(store)
            engine = MultiExchangePaperExecutionEngine(
                store,
                (GateTestnetAdapter(None), BybitDemoAdapter(None)),
            )
            report = engine.execute(
                loop_run_id="loop-risk-warning",
                decisions=[_decision()],
                portfolio_risk=_portfolio_risk("warning"),
                ai_governance=_governance(),
                policy=PaperExecutionPolicy(submit_orders=False, max_notional_usdt=100),
            )

        self.assertEqual(report["status"], "passed")
        self.assertEqual({item["status"] for item in report["executions"]}, {"dry_run"})

    def test_non_directional_decisions_explain_why_no_orders_were_planned(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_perpetual_catalog(store)
            engine = MultiExchangePaperExecutionEngine(
                store,
                (GateTestnetAdapter(None), BybitDemoAdapter(None)),
            )
            decision = {**_decision(), "action": "WATCH", "status": "watch"}

            report = engine.execute(
                loop_run_id="loop-watch-only",
                decisions=[decision],
                portfolio_risk=_portfolio_risk(),
                ai_governance=_governance(),
                policy=PaperExecutionPolicy(submit_orders=False),
            )

        self.assertEqual(report["status"], "passed")
        self.assertEqual(report["summary"]["execution_count"], 0)
        self.assertIn("WATCH/HOLD", report["summary"]["reasons"][0])

    def test_dry_run_prepares_both_exchanges_without_credentials_or_network(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_perpetual_catalog(store)
            adapters = (GateTestnetAdapter(None), BybitDemoAdapter(None))
            engine = MultiExchangePaperExecutionEngine(store, adapters)

            report = engine.execute(
                loop_run_id="loop-dry-run",
                decisions=[_decision()],
                portfolio_risk=_portfolio_risk(),
                ai_governance=_governance(),
                policy=PaperExecutionPolicy(submit_orders=False, max_notional_usdt=100),
            )
            rows = [dict(row) for row in store.list_paper_executions(loop_run_id="loop-dry-run")]

        self.assertEqual(report["status"], "passed")
        self.assertEqual(len(rows), 2)
        self.assertEqual({row["status"] for row in rows}, {"dry_run"})
        gate = next(row for row in rows if row["adapter_id"] == "gate_testnet")
        bybit = next(row for row in rows if row["adapter_id"] == "bybit_demo")
        self.assertGreater(gate["requested_quantity"], 0)
        self.assertEqual(bybit["requested_quantity"], 0.001)

    def test_submit_runs_both_adapters_and_is_idempotent(self) -> None:
        gate_requests: list[httpx.Request] = []
        bybit_requests: list[httpx.Request] = []

        def gate_handler(request: httpx.Request) -> httpx.Response:
            gate_requests.append(request)
            if request.url.path.endswith("/contracts/BTC_USDT"):
                return httpx.Response(200, json={"name": "BTC_USDT", "status": "trading", "quanto_multiplier": "0.0001"})
            if "/positions/" in request.url.path:
                return httpx.Response(404, json={"label": "POSITION_NOT_FOUND", "message": "position not found"})
            if request.url.path.endswith("/orders"):
                return httpx.Response(201, json={"id": 123, "contract": "BTC_USDT", "size": 8, "left": 0, "fill_price": "60001", "status": "finished", "finish_as": "filled", "text": "t-finbot-test"})
            raise AssertionError(str(request.url))

        def bybit_handler(request: httpx.Request) -> httpx.Response:
            bybit_requests.append(request)
            if request.url.path.endswith("/position/list"):
                return httpx.Response(200, json={"retCode": 0, "retMsg": "OK", "result": {"list": [{"symbol": "BTCUSDT", "size": "0", "side": ""}]}})
            if request.url.path.endswith("/order/create"):
                body = json.loads(request.content)
                return httpx.Response(200, json={"retCode": 0, "retMsg": "OK", "result": {"orderId": "bybit-123", "orderLinkId": body["orderLinkId"]}, "time": 1_700_000_000_000})
            raise AssertionError(str(request.url))

        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_perpetual_catalog(store)
            gate_client = GateTestnetClient("gate-key", "gate-secret", transport=httpx.MockTransport(gate_handler), clock=lambda: 1_700_000_000)
            bybit_client = BybitDemoClient("bybit-key", "bybit-secret", transport=httpx.MockTransport(bybit_handler), clock=lambda: 1_700_000_000)
            engine = MultiExchangePaperExecutionEngine(
                store,
                (GateTestnetAdapter(gate_client), BybitDemoAdapter(bybit_client)),
            )
            policy = PaperExecutionPolicy(submit_orders=True, max_notional_usdt=100)

            first = engine.execute(
                loop_run_id="loop-submit",
                decisions=[_decision()],
                portfolio_risk=_portfolio_risk(),
                ai_governance=_governance(),
                policy=policy,
            )
            second = engine.execute(
                loop_run_id="loop-submit-retry",
                decisions=[_decision()],
                portfolio_risk=_portfolio_risk(),
                ai_governance=_governance(),
                policy=policy,
            )
            rows = [dict(row) for row in store.list_paper_executions()]
            engine.close()

        self.assertEqual(first["status"], "passed")
        self.assertEqual({item["status"] for item in first["executions"]}, {"filled", "submitted"})
        self.assertEqual(len(rows), 2)
        self.assertEqual(len(gate_requests), 3)
        self.assertEqual(len(bybit_requests), 2)
        self.assertEqual(second["summary"]["execution_count"], 2)
        self.assertTrue(all(request.url.host in {"api-testnet.gateapi.io", "api-demo.bybit.com"} for request in [*gate_requests, *bybit_requests]))

    def test_one_adapter_failure_does_not_cancel_other_adapter(self) -> None:
        def gate_handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/contracts/BTC_USDT"):
                return httpx.Response(502, json={"label": "TESTNET_UNAVAILABLE", "message": "temporary"})
            raise AssertionError(str(request.url))

        def bybit_handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/position/list"):
                return httpx.Response(200, json={"retCode": 0, "retMsg": "OK", "result": {"list": []}})
            return httpx.Response(200, json={"retCode": 0, "retMsg": "OK", "result": {"orderId": "ok", "orderLinkId": "link"}})

        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_perpetual_catalog(store)
            engine = MultiExchangePaperExecutionEngine(
                store,
                (
                    GateTestnetAdapter(GateTestnetClient("key", "secret", transport=httpx.MockTransport(gate_handler))),
                    BybitDemoAdapter(BybitDemoClient("key", "secret", transport=httpx.MockTransport(bybit_handler))),
                ),
            )
            report = engine.execute(
                loop_run_id="loop-partial",
                decisions=[_decision()],
                portfolio_risk=_portfolio_risk(),
                ai_governance=_governance(),
                policy=PaperExecutionPolicy(submit_orders=True, max_notional_usdt=100),
            )
            engine.close()

        self.assertEqual(report["status"], "partial")
        statuses = {item["adapter_id"]: item["status"] for item in report["executions"]}
        self.assertEqual(statuses["gate_testnet"], "failed")
        self.assertEqual(statuses["bybit_demo"], "submitted")


if __name__ == "__main__":
    unittest.main()
