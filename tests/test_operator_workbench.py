from __future__ import annotations

import asyncio
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlparse

import httpx

from finbot.config.settings import Settings
from finbot.market.public_exchanges import MarketCandle, MarketDataProviderBlocked, MarketQuote, PublicExchangeMarketDataClient
from finbot.network.proxy_pool import ProxyPool
from finbot.network.proxy_router import ProxyRouter
from finbot.network.proxy_runtime import ProxyRuntime
from finbot.network.vless_subscription import parse_vless_subscription, prioritize_vless_nodes
from finbot.operator.workbench import OperatorWorkbenchBuilder, OperatorWorkbenchConfig, ProviderSpec, parse_provider_specs
from finbot.storage.sqlite_store import SQLiteStore


class FakeMarketClient:
    async def fetch_quote(self, provider: str, symbol: str, market_type: str = "spot") -> MarketQuote:
        return MarketQuote(
            provider=provider,
            market_type=market_type,
            symbol=symbol,
            normalized_symbol=symbol.replace("_", ""),
            captured_at="2026-07-09T00:00:00+00:00",
            last_price=110.0,
            bid=109.9,
            ask=110.1,
            price_change_pct_24h=2.2,
            high_24h=112.0,
            low_24h=100.0,
            volume_24h=1000.0,
            turnover_24h=110000.0,
            source_url="https://example.test/ticker",
            raw={},
        )

    async def fetch_candles(
        self,
        provider: str,
        symbol: str,
        market_type: str = "spot",
        interval: str = "1h",
        limit: int = 60,
    ) -> list[MarketCandle]:
        candles = []
        for index, close in enumerate([100.0, 102.0, 104.0, 107.0, 110.0]):
            candles.append(
                MarketCandle(
                    provider=provider,
                    market_type=market_type,
                    symbol=symbol,
                    normalized_symbol=symbol.replace("_", ""),
                    interval=interval,
                    open_time=f"2026-07-09T0{index}:00:00+00:00",
                    open=close - 1,
                    high=close + 2,
                    low=close - 2,
                    close=close,
                    volume=100.0,
                    turnover=10000.0,
                    raw=[],
                )
            )
        return candles


class GeoBlockedMarketClient(FakeMarketClient):
    async def fetch_quote(self, provider: str, symbol: str, market_type: str = "spot") -> MarketQuote:
        raise MarketDataProviderBlocked(
            provider=provider,
            url="https://api.bybit.com/v5/market/time",
            status_code=403,
            category="provider-geo-blocked",
            detail="CloudFront country block",
        )


class IncompleteMarketClient(FakeMarketClient):
    def __init__(self, last_price: float | None):
        self.last_price = last_price

    async def fetch_quote(self, provider: str, symbol: str, market_type: str = "spot") -> MarketQuote:
        return MarketQuote(
            provider=provider,
            market_type=market_type,
            symbol=symbol,
            normalized_symbol=symbol.replace("_", ""),
            captured_at="2026-07-09T00:00:00+00:00",
            last_price=self.last_price,
            bid=None,
            ask=None,
            price_change_pct_24h=None,
            high_24h=None,
            low_24h=None,
            volume_24h=None,
            turnover_24h=None,
            source_url="https://example.test/ticker",
            raw={},
        )

    async def fetch_candles(
        self,
        provider: str,
        symbol: str,
        market_type: str = "spot",
        interval: str = "1h",
        limit: int = 60,
    ) -> list[MarketCandle]:
        return []


class FlakyAsyncClient:
    calls = 0

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return None

    async def get(self, url: str) -> httpx.Response:
        type(self).calls += 1
        if type(self).calls == 1:
            raise httpx.ConnectError("temporary connect failure")
        payload = {
            "symbol": "BTCUSDT",
            "lastPrice": "100.0",
            "bidPrice": "99.9",
            "askPrice": "100.1",
            "priceChangePercent": "1.2",
            "highPrice": "101.0",
            "lowPrice": "98.0",
            "volume": "10.0",
            "quoteVolume": "1000.0",
        }
        request = httpx.Request("GET", url)
        return httpx.Response(200, json=payload, request=request)


class BybitGeoBlockedThenDirectAsyncClient:
    calls: list[dict[str, str | None]] = []

    def __init__(self, *args, **kwargs):
        self.proxy = kwargs.get("proxy")
        self.call = {"proxy": self.proxy, "url": None}
        type(self).calls.append(self.call)

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return None

    async def get(self, url: str) -> httpx.Response:
        self.call["url"] = url
        request = httpx.Request("GET", url)
        if self.proxy:
            return httpx.Response(
                403,
                text="The Amazon CloudFront distribution is configured to block access from your country.",
                request=request,
            )
        payload = {
            "result": {
                "list": [
                    {
                        "symbol": "BTCUSDT",
                        "lastPrice": "100.0",
                        "bid1Price": "99.9",
                        "ask1Price": "100.1",
                        "price24hPcnt": "0.01",
                        "highPrice24h": "101.0",
                        "lowPrice24h": "98.0",
                        "volume24h": "10.0",
                        "turnover24h": "1000.0",
                    }
                ]
            }
        }
        return httpx.Response(200, json=payload, request=request)


class OperatorWorkbenchTests(unittest.TestCase):
    def test_disabled_bridges_do_not_load_unused_vless_subscriptions(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            env = {
                "EXCHANGE_PROXY_POOL": "http://proxy.example.test:8080",
                "EXCHANGE_PROXY_IP_FAMILY": "ipv4",
                "EXCHANGE_VLESS_SUBSCRIPTION_URL": "https://unavailable.example.test/subscription",
                "EXCHANGE_HYSTERIA2_URLS": "hysteria2://password@unavailable.example.test:443",
            }
            with patch.dict(os.environ, env, clear=True), patch(
                "finbot.network.proxy_runtime.load_vless_subscription",
                side_effect=AssertionError("unused subscription must not be loaded"),
            ) as loader, patch(
                "finbot.network.proxy_runtime.parse_hysteria2_urls",
                side_effect=AssertionError("unused Hysteria2 nodes must not be parsed"),
            ) as hysteria2_parser:
                settings = Settings.from_env(project_root=root, data_dir=root / "data")
                runtime = ProxyRuntime.from_settings(settings, start_bridges=False)
                try:
                    decision = runtime.router.decide("exchange:bybit", "https://api.bybit.com/v5/market/time")
                finally:
                    runtime.close()

        loader.assert_not_called()
        hysteria2_parser.assert_not_called()
        self.assertTrue(decision.ok)
        self.assertEqual(decision.proxy_redacted, "http://proxy.example.test:8080")

    def test_workbench_builds_advice_without_execution_permission(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            builder = OperatorWorkbenchBuilder(store=store, market_client=FakeMarketClient())

            report = asyncio.run(
                builder.build(
                    OperatorWorkbenchConfig(
                        symbols=("BTCUSDT",),
                        providers=(ProviderSpec("binance", "spot"),),
                        candle_limit=5,
                    )
                )
            )

            self.assertEqual(report["status"], "ok")
            self.assertEqual(report["summary"]["advice_count"], 1)
            self.assertEqual(report["config"]["data_source"], "live_public")
            self.assertEqual(report["config"]["execution_mode"], "advisory_only")
            self.assertEqual(report["items"][0]["advice"]["action"], "BUY")
            self.assertIn("timeframe_alignment", report["items"][0]["advice"]["metrics"])
            self.assertEqual(report["summary"]["paper_proposal_count"], 1)
            self.assertEqual(report["paper_proposals"][0]["execution_mode"], "paper_only")
            self.assertFalse(report["policy"]["execution_allowed"])
            self.assertFalse(report["items"][0]["advice"]["policy"]["order_api_allowed"])
            self.assertEqual(len(store.list_advisory_reports(limit=1)), 1)
            self.assertEqual(len(store.list_paper_order_proposals(limit=1)), 1)

    def test_workbench_rejects_empty_price_and_primary_candles(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            builder = OperatorWorkbenchBuilder(store=store, market_client=IncompleteMarketClient(last_price=None))

            report = asyncio.run(
                builder.build(
                    OperatorWorkbenchConfig(
                        symbols=("BTCUSDT-25SEP26",),
                        providers=(ProviderSpec("bybit", "linear"),),
                        intervals=("1h", "4h"),
                    )
                )
            )

        self.assertEqual(report["status"], "failed")
        self.assertEqual(report["summary"]["advice_count"], 0)
        self.assertEqual(report["summary"]["failed_count"], 1)
        self.assertEqual(report["summary"]["paper_proposal_count"], 0)
        self.assertEqual(report["items"][0]["status"], "insufficient-data")
        self.assertFalse(report["items"][0]["data_quality"]["decision_ready"])
        self.assertIn("missing_last_price", report["items"][0]["data_quality"]["reasons"])
        self.assertIn("insufficient_primary_candles", report["items"][0]["data_quality"]["reasons"])

    def test_workbench_rejects_quote_without_primary_candles(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            builder = OperatorWorkbenchBuilder(store=store, market_client=IncompleteMarketClient(last_price=110.0))

            report = asyncio.run(
                builder.build(
                    OperatorWorkbenchConfig(
                        symbols=("BTCUSDT",),
                        providers=(ProviderSpec("gate", "perpetual"),),
                    )
                )
            )

        self.assertEqual(report["items"][0]["status"], "insufficient-data")
        self.assertNotIn("missing_last_price", report["items"][0]["data_quality"]["reasons"])
        self.assertEqual(report["items"][0]["data_quality"]["primary_candle_count"], 0)

    def test_default_provider_is_gate_public_market_data(self) -> None:
        self.assertEqual(parse_provider_specs(None), (ProviderSpec("gate", "spot"),))

    def test_binance_linear_uses_public_futures_endpoints(self) -> None:
        client = PublicExchangeMarketDataClient(proxy_router=ProxyRouter.direct_for_tests())

        self.assertIn("/fapi/v1/ticker/24hr", client.quote_url("binance", "BTCUSDT", "linear"))
        self.assertIn("/fapi/v1/klines", client.candles_url("binance", "BTCUSDT", "linear", "1h", 60))

    def test_workbench_marks_provider_geo_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = SQLiteStore(Path(temp_dir) / "finbot.sqlite3")
            builder = OperatorWorkbenchBuilder(store=store, market_client=GeoBlockedMarketClient())

            report = asyncio.run(
                builder.build(
                    OperatorWorkbenchConfig(
                        symbols=("BTCUSDT",),
                        providers=(ProviderSpec("bybit", "linear"),),
                        candle_limit=5,
                    )
                )
            )

            self.assertEqual(report["status"], "failed")
            self.assertEqual(report["items"][0]["status"], "provider-geo-blocked")
            self.assertEqual(report["items"][0]["error_category"], "provider-geo-blocked")

    def test_market_client_retries_transient_connect_error(self) -> None:
        FlakyAsyncClient.calls = 0
        client = PublicExchangeMarketDataClient(
            timeout_seconds=1,
            max_retries=1,
            retry_base_delay_seconds=0,
            proxy_router=ProxyRouter.direct_for_tests(),
        )

        with patch("finbot.market.public_exchanges.httpx.AsyncClient", FlakyAsyncClient):
            quote = asyncio.run(client.fetch_quote("binance", "BTCUSDT"))

        observations = client.request_observations()
        self.assertEqual(FlakyAsyncClient.calls, 2)
        self.assertEqual(quote.last_price, 100.0)
        self.assertEqual(observations[0]["error_type"], "ConnectError")
        self.assertEqual(observations[0]["attempt"], 1)
        self.assertEqual(observations[1]["status_code"], 200)
        self.assertEqual(observations[1]["attempt"], 2)

    def test_market_client_blocks_without_exchange_proxy(self) -> None:
        client = PublicExchangeMarketDataClient(timeout_seconds=1, max_retries=0)

        with self.assertRaises(Exception) as context:
            asyncio.run(client.fetch_quote("binance", "BTCUSDT"))

        observations = client.request_observations()
        self.assertIn("no proxy candidate configured", str(context.exception))
        self.assertEqual(observations[0]["error_type"], "ProxyRouteBlocked")
        self.assertEqual(observations[0]["proxy_route"]["status"], "blocked-by-proxy")

    def test_exchange_route_rejects_ipv6_only_proxy_pool(self) -> None:
        router = ProxyRouter.from_pools(
            exchange_pool=ProxyPool(["socks5://user:pass@example.test:1080"], include_direct=False),
            exchange_proxy_ip_family="ipv6",
        )

        decision = router.decide("exchange:bybit", "https://api.bybit.com/v5/market/time")

        self.assertFalse(decision.ok)
        self.assertEqual(decision.status, "blocked-by-proxy")
        self.assertIn("not allowed", decision.reason or "")
        self.assertIn("<redacted>", decision.proxy_redacted)

    def test_firecrawl_route_accepts_ipv4_and_rejects_ipv6_pool(self) -> None:
        router = ProxyRouter.from_pools(
            firecrawl_pool=ProxyPool(["http://proxy.example:8080"], include_direct=False),
            firecrawl_proxy_ip_family="ipv4",
        )

        decision = router.decide("firecrawl", "https://api.firecrawl.dev/v2/search")

        self.assertTrue(decision.ok)

        ipv6_router = ProxyRouter.from_pools(
            firecrawl_pool=ProxyPool(["http://proxy.example:8080"], include_direct=False),
            firecrawl_proxy_ip_family="ipv6",
        )
        blocked = ipv6_router.decide("firecrawl", "https://api.firecrawl.dev/v2/search")
        self.assertFalse(blocked.ok)
        self.assertEqual(blocked.status, "blocked-by-proxy")
        self.assertIn("not allowed", blocked.reason or "")

    def test_provider_geo_block_does_not_fallback_to_direct_by_default(self) -> None:
        BybitGeoBlockedThenDirectAsyncClient.calls = []
        router = ProxyRouter.from_pools(
            exchange_pool=ProxyPool(["http://proxy.example:8080"], include_direct=False),
            exchange_proxy_ip_family="ipv4",
        )
        client = PublicExchangeMarketDataClient(timeout_seconds=1, max_retries=0, proxy_router=router)

        with patch("finbot.market.public_exchanges.httpx.AsyncClient", BybitGeoBlockedThenDirectAsyncClient):
            with self.assertRaises(MarketDataProviderBlocked):
                asyncio.run(client.fetch_quote("bybit", "BTCUSDT"))

        observations = client.request_observations()
        self.assertEqual(
            [call["proxy"] for call in BybitGeoBlockedThenDirectAsyncClient.calls],
            ["http://proxy.example:8080", "http://proxy.example:8080"],
        )
        self.assertEqual(
            [urlparse(str(call["url"])).hostname for call in BybitGeoBlockedThenDirectAsyncClient.calls],
            ["api.bybit.com", "api.bytick.com"],
        )
        self.assertEqual(observations[0]["status_code"], 403)
        self.assertEqual(observations[0]["proxy_route"]["proxy"], "http://proxy.example:8080")

    def test_provider_geo_block_can_fallback_to_explicit_direct_route(self) -> None:
        BybitGeoBlockedThenDirectAsyncClient.calls = []
        router = ProxyRouter.from_pools(
            exchange_pool=ProxyPool(["http://proxy.example:8080"], include_direct=False),
            exchange_proxy_ip_family="ipv4",
            exchange_provider_overrides={"exchange:bybit": {"allow_direct": True}},
        )
        client = PublicExchangeMarketDataClient(timeout_seconds=1, max_retries=0, proxy_router=router)

        with patch("finbot.market.public_exchanges.httpx.AsyncClient", BybitGeoBlockedThenDirectAsyncClient):
            quote = asyncio.run(client.fetch_quote("bybit", "BTCUSDT"))

        observations = client.request_observations()
        self.assertEqual(quote.last_price, 100.0)
        self.assertEqual([call["proxy"] for call in BybitGeoBlockedThenDirectAsyncClient.calls], ["http://proxy.example:8080", None])
        self.assertEqual(observations[0]["status_code"], 403)
        self.assertEqual(observations[0]["proxy_route"]["proxy"], "http://proxy.example:8080")
        self.assertEqual(observations[1]["status_code"], 200)
        self.assertEqual(observations[1]["proxy_route"]["proxy"], "direct")
        self.assertTrue(observations[1]["proxy_route"]["policy"]["allow_direct"])

    def test_proxy_runtime_hot_loads_provider_direct_policy_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config_dir = root / "config"
            config_dir.mkdir()
            (config_dir / "proxy_policy.json").write_text(
                json.dumps({"routes": {"exchange:bybit": {"allow_direct": True}}}),
                encoding="utf-8",
            )
            settings = Settings.from_env(project_root=root, data_dir=root / "data")

            runtime = ProxyRuntime.from_settings(settings, start_bridges=False)
            try:
                bybit_decision = runtime.router.decide("exchange:bybit", "https://api.bybit.com/v5/market/time")
                binance_decision = runtime.router.decide("exchange:binance", "https://data-api.binance.vision/api/v3/time")
            finally:
                runtime.close()

        self.assertTrue(bybit_decision.ok)
        self.assertEqual(bybit_decision.proxy_redacted, "direct")
        self.assertEqual(binance_decision.status, "blocked-by-proxy")

    def test_proxy_policy_string_false_does_not_enable_direct(self) -> None:
        router = ProxyRouter.from_pools(
            exchange_provider_overrides={"exchange:bybit": {"allow_direct": "false"}},
        )

        decision = router.decide("exchange:bybit", "https://api.bybit.com/v5/market/time")

        self.assertFalse(decision.ok)
        self.assertEqual(decision.status, "blocked-by-proxy")

    def test_parse_vless_subscription_redacts_secret_fields(self) -> None:
        payload = (
            "vless://00000000-0000-4000-8000-000000000000@104.16.0.1:443"
            "?security=tls&type=ws&host=proxy.example&sni=proxy.example&path=%2F%3Fed%3D2560"
            "#node"
        )

        subscription = parse_vless_subscription(payload, source="inline")

        self.assertEqual(subscription.nodes[0].transport, "ws")
        self.assertEqual(subscription.nodes[0].security, "tls")
        self.assertEqual(subscription.nodes[0].path, "/?ed=2560")
        self.assertEqual(subscription.summary()["sample_nodes"][0]["uuid"], "<redacted>")

    def test_prioritize_vless_nodes_uses_exact_labels_and_preserves_fallback_order(self) -> None:
        payload = "\n".join(
            (
                "vless://00000000-0000-4000-8000-000000000001@104.16.0.1:443?security=tls&type=ws#first",
                "vless://00000000-0000-4000-8000-000000000002@104.16.0.2:443?security=tls&type=ws#preferred",
                "vless://00000000-0000-4000-8000-000000000003@104.16.0.3:443?security=tls&type=ws#third",
            )
        )
        subscription = parse_vless_subscription(payload, source="inline")

        prioritized = prioritize_vless_nodes(subscription, ["preferred", "missing"])

        self.assertEqual([node.name for node in prioritized.nodes], ["preferred", "first", "third"])
        self.assertEqual(prioritized.source, "inline")

    def test_vless_subscription_skips_invalid_and_duplicate_nodes(self) -> None:
        valid = "vless://00000000-0000-4000-8000-000000000001@104.16.0.1:443?security=tls&type=ws#first"
        subscription = parse_vless_subscription(
            "\n".join((valid, "vless://missing-host", valid)),
            source="inline",
        )

        self.assertEqual(len(subscription.nodes), 1)
        self.assertEqual(subscription.invalid_node_count, 1)
        self.assertEqual(subscription.summary()["invalid_node_count"], 1)


if __name__ == "__main__":
    unittest.main()
