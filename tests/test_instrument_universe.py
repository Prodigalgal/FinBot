from __future__ import annotations

import asyncio
import tempfile
import unittest
from pathlib import Path
from typing import Any

from finbot.autonomous.product_candidates import ProductCandidateBuilder, ProductCandidateConfig
from finbot.instruments.catalog import InstrumentCatalogSynchronizer
from finbot.instruments.models import CatalogInstrument, InstrumentMarket
from finbot.instruments.universe import HybridUniverseBuilder, UniverseConfig
from finbot.storage.sqlite_store import SQLiteStore


class FakeCatalogClient:
    async def request_json(self, provider: str, url: str, weight: int = 1) -> Any:
        if url.endswith("/spot/currency_pairs"):
            return [
                {
                    "id": "BTC_USDT",
                    "base": "BTC",
                    "quote": "USDT",
                    "trade_status": "tradable",
                    "precision": 2,
                    "amount_precision": 6,
                    "min_base_amount": "0.0001",
                    "min_quote_amount": "1",
                },
                {
                    "id": "BZ_USDT",
                    "base": "BZ",
                    "quote": "USDT",
                    "trade_status": "tradable",
                    "precision": 4,
                    "amount_precision": 2,
                    "min_base_amount": "1",
                    "min_quote_amount": "2",
                },
            ]
        if url.endswith("/spot/tickers"):
            return [
                {
                    "currency_pair": "BTC_USDT",
                    "last": "60000",
                    "highest_bid": "59999",
                    "lowest_ask": "60001",
                    "base_volume": "100",
                    "quote_volume": "6000000",
                    "change_percentage": "1.2",
                },
                {
                    "currency_pair": "BZ_USDT",
                    "last": "0.10",
                    "highest_bid": "0.099",
                    "lowest_ask": "0.101",
                    "base_volume": "10000",
                    "quote_volume": "1000",
                    "change_percentage": "-2",
                },
            ]
        raise AssertionError(f"Unexpected catalog URL: {provider} {url} {weight}")

    def request_observations(self) -> list[dict[str, Any]]:
        return []


class EmptyCatalogClient(FakeCatalogClient):
    async def request_json(self, provider: str, url: str, weight: int = 1) -> Any:
        return []


class FakeGateFuturesCatalogClient(FakeCatalogClient):
    async def request_json(self, provider: str, url: str, weight: int = 1) -> Any:
        if url.endswith("/futures/usdt/contracts"):
            return [
                {
                    "name": "BTC_USDT",
                    "type": "direct",
                    "quanto_multiplier": "0.0001",
                    "leverage_min": "1",
                    "leverage_max": "100",
                    "order_price_round": "0.1",
                    "order_size_min": 0,
                    "order_size_max": 1_000_000,
                    "status": "trading",
                }
            ]
        if url.endswith("/futures/usdt/tickers"):
            return [
                {
                    "contract": "BTC_USDT",
                    "last": "60000",
                    "highest_bid": "59999.9",
                    "lowest_ask": "60000.1",
                    "volume_24h_base": "1234",
                    "volume_24h_quote": "74040000",
                    "change_percentage": "1.5",
                }
            ]
        raise AssertionError(f"Unexpected futures catalog URL: {provider} {url} {weight}")


class FakeBybitLinearCatalogClient(FakeCatalogClient):
    async def request_json(self, provider: str, url: str, weight: int = 1) -> Any:
        if "/v5/market/tickers" in url:
            return {
                "result": {
                    "list": [
                        {
                            "symbol": "BTCUSDT",
                            "lastPrice": "60000",
                            "bid1Price": "59999.9",
                            "ask1Price": "60000.1",
                            "turnover24h": "100000000",
                        },
                        {
                            "symbol": "BTCUSDT-25SEP26",
                            "lastPrice": "",
                            "bid1Price": "",
                            "ask1Price": "",
                            "turnover24h": "0",
                        },
                    ]
                }
            }
        if "/v5/market/instruments-info" in url:
            return {
                "result": {
                    "list": [
                        {
                            "symbol": "BTCUSDT",
                            "baseCoin": "BTC",
                            "quoteCoin": "USDT",
                            "settleCoin": "USDT",
                            "status": "Trading",
                            "lotSizeFilter": {"qtyStep": "0.001", "minOrderQty": "0.001"},
                            "priceFilter": {"tickSize": "0.1"},
                        },
                        {
                            "symbol": "BTCUSDT-25SEP26",
                            "baseCoin": "BTC",
                            "quoteCoin": "USDT",
                            "settleCoin": "USDT",
                            "status": "Trading",
                            "deliveryTime": "1789776000000",
                            "lotSizeFilter": {"qtyStep": "0.001", "minOrderQty": "0.001"},
                            "priceFilter": {"tickSize": "0.1"},
                        },
                    ],
                    "nextPageCursor": "",
                }
            }
        raise AssertionError(f"Unexpected Bybit catalog URL: {provider} {url} {weight}")


class InstrumentUniverseTests(unittest.TestCase):
    def test_catalog_sync_and_hybrid_universe_only_use_real_instruments(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            synchronizer = InstrumentCatalogSynchronizer(store, FakeCatalogClient())  # type: ignore[arg-type]

            catalog = asyncio.run(synchronizer.sync((InstrumentMarket("gate", "spot"),)))
            universe = HybridUniverseBuilder(store).build(
                UniverseConfig(
                    mode="hybrid",
                    watchlist=("BZUSDT", "UNKNOWN"),
                    markets=(InstrumentMarket("gate", "spot"),),
                    quote_assets=("USDT",),
                    max_instruments=2,
                    max_spread_pct=3.0,
                ),
                loop_run_id="loop-a",
                research_assets=("BZ", "NOT_LISTED"),
            )

        self.assertEqual(catalog["status"], "passed")
        self.assertEqual(catalog["instrument_count"], 2)
        self.assertEqual(universe["status"], "passed")
        selected = {item["symbol"]: item for item in universe["instruments"]}
        self.assertIn("BZ_USDT", selected)
        self.assertIn("watchlist", selected["BZ_USDT"]["sources"])
        self.assertIn("research", selected["BZ_USDT"]["sources"])
        self.assertIn("UNKNOWN", universe["summary"]["unresolved_watchlist"])
        self.assertIn("NOT_LISTED", universe["summary"]["unresolved_research_assets"])
        self.assertNotIn("BZUSDTUSDT", {item["normalized_symbol"] for item in universe["instruments"]})

    def test_research_candidate_requires_verified_instrument_mapping(self) -> None:
        report = {
            "report_id": "operator-a",
            "config": {
                "instruments": [
                    {
                        "instrument_id": "instrument-bz",
                        "provider": "gate",
                        "market_type": "spot",
                        "symbol": "BZ_USDT",
                        "normalized_symbol": "BZUSDT",
                        "base_asset": "BZ",
                    }
                ]
            },
            "items": [],
            "research_context": {
                "source": "phase4.1-research-council",
                "status": "passed",
                "items": [
                    {"event_key": "bz-event", "impact_assets": ["BZUSDT", "UNKNOWN"], "confidence": 0.8}
                ],
            },
        }

        result = ProductCandidateBuilder().build(report, ProductCandidateConfig(symbols=("BZUSDT",)))

        self.assertEqual(result["candidate_count"], 1)
        self.assertEqual(result["candidates"][0]["instrument_id"], "instrument-bz")
        self.assertEqual(result["candidates"][0]["symbol"], "BZ_USDT")
        self.assertNotEqual(result["candidates"][0]["symbol"], "BZUSDTUSDT")

    def test_candidate_uses_strict_cross_venue_market_confirmation(self) -> None:
        report = {
            "report_id": "operator-cross-venue",
            "items": [
                _operator_market_item("gate", "spot", "SOL_USDT", "SOLUSDT", "SELL", 0.67, 78.57),
                _operator_market_item("bybit", "linear", "SOLUSDT", "SOLUSDT", "SELL", 0.69, 78.55),
            ],
            "research_context": {"status": "needs-followup", "items": []},
        }

        result = ProductCandidateBuilder().build(report, ProductCandidateConfig(symbols=("SOLUSDT",)))

        self.assertEqual(result["selected_count"], 2)
        self.assertTrue(all(item["research_context"]["status"] == "market-confirmed" for item in result["candidates"]))
        confirmation = result["candidates"][0]["research_context"]["market_confirmation"]
        self.assertEqual(confirmation["provider_count"], 2)
        self.assertLess(confirmation["maximum_price_divergence_pct"], 1.0)
        self.assertIn("market:gate:SOL_USDT", result["candidates"][0]["evidence_refs"])
        self.assertIn("market:bybit:SOLUSDT", result["candidates"][0]["evidence_refs"])

    def test_cross_venue_confirmation_preserves_unresolved_fundamental_context(self) -> None:
        pending_research = {
            "status": "needs-followup",
            "matched_items": [{"event_key": "crypto:sol", "status": "needs-followup"}],
        }
        report = {
            "report_id": "operator-cross-venue-with-pending-research",
            "items": [
                _operator_market_item("gate", "perpetual", "SOL_USDT", "SOLUSDT", "SELL", 0.67, 78.57),
                _operator_market_item("bybit", "linear", "SOLUSDT", "SOLUSDT", "SELL", 0.69, 78.55),
            ],
        }
        for item in report["items"]:
            item["advice"]["research_context"] = pending_research

        result = ProductCandidateBuilder().build(report, ProductCandidateConfig(symbols=("SOLUSDT",)))

        self.assertEqual(result["selected_count"], 2)
        for candidate in result["candidates"]:
            context = candidate["research_context"]
            self.assertEqual(context["status"], "market-confirmed")
            self.assertEqual(context["confirmation_scope"], "market-only")
            self.assertEqual(context["fundamental_research_status"], "needs-followup")
            self.assertFalse(context["fundamental_research_confirmed"])
            self.assertEqual(context["matched_items_count"], 1)

    def test_candidate_does_not_confirm_conflicting_cross_venue_direction(self) -> None:
        report = {
            "report_id": "operator-conflict",
            "items": [
                _operator_market_item("gate", "spot", "SOL_USDT", "SOLUSDT", "SELL", 0.67, 78.57),
                _operator_market_item("bybit", "linear", "SOLUSDT", "SOLUSDT", "BUY", 0.69, 78.55),
            ],
        }

        result = ProductCandidateBuilder().build(report, ProductCandidateConfig(symbols=("SOLUSDT",)))

        self.assertTrue(all(item["research_context"]["status"] != "market-confirmed" for item in result["candidates"]))

    def test_empty_catalog_response_preserves_existing_active_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            asyncio.run(
                InstrumentCatalogSynchronizer(store, FakeCatalogClient()).sync(  # type: ignore[arg-type]
                    (InstrumentMarket("gate", "spot"),)
                )
            )

            failed = asyncio.run(
                InstrumentCatalogSynchronizer(store, EmptyCatalogClient()).sync(  # type: ignore[arg-type]
                    (InstrumentMarket("gate", "spot"),)
                )
            )
            active_rows = store.list_venue_instruments(active_only=True)

        self.assertEqual(failed["status"], "failed")
        self.assertEqual(len(active_rows), 2)

    def test_spot_canonical_product_id_is_venue_independent(self) -> None:
        common = {
            "market_type": "spot",
            "symbol": "BTCUSDT",
            "base_asset": "BTC",
            "quote_asset": "USDT",
            "captured_at": "2026-07-10T00:00:00+00:00",
        }
        gate = CatalogInstrument(provider="gate", settle_asset=None, **common)
        binance = CatalogInstrument(provider="binance", settle_asset="USDT", **common)

        self.assertEqual(gate.product_id, binance.product_id)

    def test_gate_usdt_perpetual_catalog_maps_contract_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            report = asyncio.run(
                InstrumentCatalogSynchronizer(store, FakeGateFuturesCatalogClient()).sync(  # type: ignore[arg-type]
                    (InstrumentMarket("gate", "perpetual"),)
                )
            )
            rows = [dict(row) for row in store.list_venue_instruments(active_only=True)]

        self.assertEqual(report["status"], "passed")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["market_type"], "perpetual")
        self.assertTrue(rows[0]["contract"])
        self.assertTrue(rows[0]["linear"])
        self.assertEqual(rows[0]["contract_size"], 0.0001)
        self.assertEqual(rows[0]["min_amount"], 1.0)
        self.assertEqual(rows[0]["min_notional"], 6.0)
        self.assertEqual(rows[0]["turnover_24h"], 74_040_000.0)

    def test_research_asset_selects_representative_non_expiring_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            asyncio.run(
                InstrumentCatalogSynchronizer(store, FakeBybitLinearCatalogClient()).sync(  # type: ignore[arg-type]
                    (InstrumentMarket("bybit", "linear"),)
                )
            )

            universe = HybridUniverseBuilder(store).build(
                UniverseConfig(
                    mode="hybrid",
                    watchlist=(),
                    markets=(InstrumentMarket("bybit", "linear"),),
                    max_instruments=12,
                    include_market_rank=False,
                ),
                loop_run_id="loop-representative",
                research_assets=("BTC",),
            )

        self.assertEqual([item["symbol"] for item in universe["instruments"]], ["BTCUSDT"])
        self.assertEqual(universe["summary"]["source_counts"], {"research": 1})


def _operator_market_item(
    provider: str,
    market_type: str,
    symbol: str,
    normalized_symbol: str,
    action: str,
    confidence: float,
    price: float,
) -> dict[str, Any]:
    target = price * (0.98 if action == "SELL" else 1.02)
    invalidation = price * (1.01 if action == "SELL" else 0.99)
    return {
        "status": "ok",
        "provider": provider,
        "market_type": market_type,
        "symbol": symbol,
        "quote": {
            "provider": provider,
            "market_type": market_type,
            "symbol": symbol,
            "normalized_symbol": normalized_symbol,
            "last_price": price,
            "captured_at": "2026-07-11T00:00:00+00:00",
        },
        "advice": {
            "provider": provider,
            "market_type": market_type,
            "symbol": symbol,
            "normalized_symbol": normalized_symbol,
            "action": action,
            "confidence": confidence,
            "levels": {
                "entry_reference": price,
                "target_price": target,
                "invalidation_price": invalidation,
            },
            "research_context": {"status": "unconfirmed", "matched_items": []},
        },
    }


if __name__ == "__main__":
    unittest.main()
