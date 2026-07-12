from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import RuntimeConfigStore
from finbot.instruments.models import CatalogInstrument, InstrumentMarket
from finbot.instruments.product_center import DEFAULT_WATCHLIST_ID, ProductCatalogService
from finbot.instruments.universe import HybridUniverseBuilder, UniverseConfig
from finbot.storage.sqlite_store import SQLiteStore
from finbot.web.service import FinBotWebApp, create_fastapi_app


CAPTURED_AT = "2026-07-11T00:00:00+00:00"


def _seed_catalog(store: SQLiteStore) -> list[CatalogInstrument]:
    store.init_schema()
    instruments = [
        CatalogInstrument(
            provider="gate",
            market_type="spot",
            symbol=f"{base}_USDT",
            base_asset=base,
            quote_asset="USDT",
            captured_at=CAPTURED_AT,
            tick_size=0.01,
            amount_step=0.0001,
            market_snapshot={
                "last_price": price,
                "bid": price - 0.01,
                "ask": price + 0.01,
                "turnover_24h": turnover,
            },
        )
        for base, price, turnover in (
            ("BTC", 60_000.0, 9_000_000.0),
            ("ETH", 3_000.0, 6_000_000.0),
            ("SOL", 150.0, 3_000_000.0),
        )
    ]
    store.sync_instrument_catalog(
        "gate",
        "spot",
        [instrument.to_record() for instrument in instruments],
        CAPTURED_AT,
    )
    return instruments


class ProductCatalogServiceTests(unittest.TestCase):
    def test_schema_enables_wal_and_latest_snapshot_lookup_index(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            store.init_schema()
            store.init_schema()
            with store.connect() as conn:
                journal_mode = conn.execute("pragma journal_mode").fetchone()[0]
                indexes = {
                    row["name"]
                    for row in conn.execute("pragma index_list('instrument_market_snapshots')")
                }

        self.assertEqual(journal_mode.lower(), "wal")
        self.assertIn("idx_instrument_snapshots_latest", indexes)

    def test_default_watchlist_and_server_side_catalog_filters(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            _seed_catalog(store)
            service = ProductCatalogService(store)

            watchlists = service.list_watchlists()
            first_page = service.list_products(page=1, page_size=2)
            search = service.list_products(search="eth", page=1, page_size=25)

        self.assertEqual(watchlists["count"], 1)
        self.assertEqual(watchlists["watchlists"][0]["watchlist_id"], DEFAULT_WATCHLIST_ID)
        self.assertTrue(watchlists["watchlists"][0]["is_default"])
        self.assertEqual(first_page["total"], 3)
        self.assertEqual(first_page["total_pages"], 2)
        self.assertEqual(len(first_page["items"]), 2)
        self.assertEqual(search["total"], 1)
        self.assertEqual(search["items"][0]["base_asset"], "ETH")

    def test_watchlist_upsert_is_idempotent_and_validates_preferred_instrument(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            btc, eth, _ = _seed_catalog(store)
            service = ProductCatalogService(store)

            first = service.upsert_watchlist_item(
                DEFAULT_WATCHLIST_ID,
                btc.product_id,
                preferred_instrument_id=btc.instrument_id,
                research_mode="research",
                tags=["核心", "核心"],
                notes="关注流动性",
            )
            second = service.upsert_watchlist_item(
                DEFAULT_WATCHLIST_ID,
                btc.product_id,
                preferred_instrument_id=btc.instrument_id,
                research_mode="pinned",
                tags=["优先"],
            )
            detail = service.get_watchlist(DEFAULT_WATCHLIST_ID)

            with self.assertRaisesRegex(ValueError, "不属于当前产品"):
                service.upsert_watchlist_item(
                    DEFAULT_WATCHLIST_ID,
                    btc.product_id,
                    preferred_instrument_id=eth.instrument_id,
                    research_mode="research",
                )
            with self.assertRaisesRegex(ValueError, "research_mode"):
                service.upsert_watchlist_item(
                    DEFAULT_WATCHLIST_ID,
                    btc.product_id,
                    preferred_instrument_id=btc.instrument_id,
                    research_mode="automatic",
                )

        self.assertEqual(first["item"]["watchlist_item_id"], second["item"]["watchlist_item_id"])
        self.assertEqual(len(detail["items"]), 1)
        self.assertEqual(detail["items"][0]["research_mode"], "pinned")
        self.assertEqual(detail["items"][0]["tags"], ["优先"])

    def test_watchlist_modes_feed_hybrid_universe_without_bypassing_eligibility(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            btc, eth, sol = _seed_catalog(store)
            service = ProductCatalogService(store)
            service.upsert_watchlist_item(
                DEFAULT_WATCHLIST_ID,
                btc.product_id,
                preferred_instrument_id=btc.instrument_id,
                research_mode="monitor",
            )
            service.upsert_watchlist_item(
                DEFAULT_WATCHLIST_ID,
                eth.product_id,
                preferred_instrument_id=eth.instrument_id,
                research_mode="research",
            )
            service.upsert_watchlist_item(
                DEFAULT_WATCHLIST_ID,
                sol.product_id,
                preferred_instrument_id=sol.instrument_id,
                research_mode="pinned",
            )
            sources = service.universe_instrument_ids()
            universe = HybridUniverseBuilder(store).build(
                UniverseConfig(
                    mode="fixed",
                    watchlist=(),
                    watchlist_instrument_ids=sources["research"],
                    pinned_instrument_ids=sources["pinned"],
                    markets=(InstrumentMarket("gate", "spot"),),
                    quote_assets=("USDT",),
                    max_instruments=3,
                )
            )

        selected = {item["instrument_id"]: item for item in universe["instruments"]}
        self.assertNotIn(btc.instrument_id, selected)
        self.assertIn("user_watchlist", selected[eth.instrument_id]["sources"])
        self.assertIn("user_pinned", selected[sol.instrument_id]["sources"])
        self.assertGreater(selected[sol.instrument_id]["score"], selected[eth.instrument_id]["score"])


class ProductCatalogApiTests(unittest.TestCase):
    def test_product_and_watchlist_crud_api(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            state = FinBotWebApp(
                data_dir=str(root / "data"),
                config_store=RuntimeConfigStore(root),
                ai_config_store=AISitesConfigStore(root),
            )
            btc, _, _ = _seed_catalog(state.autonomous_store())
            app = create_fastapi_app(state, frontend_dist=None)

            with TestClient(app) as client:
                watchlists = client.get("/api/v1/watchlists")
                products = client.get("/api/v1/products", params={"search": "btc", "page_size": 10})
                saved = client.put(
                    f"/api/v1/watchlists/{DEFAULT_WATCHLIST_ID}/items/{btc.product_id}",
                    json={
                        "preferred_instrument_id": btc.instrument_id,
                        "research_mode": "research",
                        "notes": "API smoke",
                        "tags": ["BTC"],
                    },
                )
                watched = client.get(
                    "/api/v1/products",
                    params={"watchlist_id": DEFAULT_WATCHLIST_ID, "watched_only": True},
                )
                detail = client.get(
                    f"/api/v1/products/{btc.product_id}",
                    params={"watchlist_id": DEFAULT_WATCHLIST_ID},
                )
                invalid_default_delete = client.delete(f"/api/v1/watchlists/{DEFAULT_WATCHLIST_ID}")
                deleted = client.delete(
                    f"/api/v1/watchlists/{DEFAULT_WATCHLIST_ID}/items/{btc.product_id}"
                )

        self.assertEqual(watchlists.status_code, 200)
        self.assertEqual(products.status_code, 200)
        self.assertEqual(products.json()["total"], 1)
        self.assertEqual(saved.status_code, 200)
        self.assertEqual(watched.json()["total"], 1)
        self.assertEqual(watched.json()["items"][0]["watchlist_item"]["research_mode"], "research")
        self.assertEqual(detail.status_code, 200)
        self.assertEqual(detail.json()["watchlist_item"]["preferred_instrument_id"], btc.instrument_id)
        self.assertEqual(invalid_default_delete.status_code, 400)
        self.assertEqual(deleted.json()["status"], "deleted")


if __name__ == "__main__":
    unittest.main()
