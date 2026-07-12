from __future__ import annotations

import unittest
from unittest.mock import AsyncMock

from finbot.market.public_exchanges import (
    BYBIT_ALTERNATE_API_BASE,
    BYBIT_API_BASE,
    PublicExchangeMarketDataClient,
    _public_host_candidates,
)


class GatePublicMarketTests(unittest.IsolatedAsyncioTestCase):
    def test_bybit_public_requests_have_official_alternate_host(self) -> None:
        path = "/v5/market/tickers?category=linear"
        candidates = _public_host_candidates("bybit", f"{BYBIT_API_BASE}{path}")

        self.assertEqual(
            candidates,
            (f"{BYBIT_API_BASE}{path}", f"{BYBIT_ALTERNATE_API_BASE}{path}"),
        )
        self.assertEqual(
            _public_host_candidates("gate", "https://api.gateio.ws/api/v4/spot/tickers"),
            ("https://api.gateio.ws/api/v4/spot/tickers",),
        )

    async def test_gate_perpetual_uses_mainnet_futures_public_endpoints(self) -> None:
        client = PublicExchangeMarketDataClient(max_retries=0)
        client._get_json = AsyncMock(  # type: ignore[method-assign]
            side_effect=[
                [
                    {
                        "contract": "BTC_USDT",
                        "last": "60000",
                        "highest_bid": "59999.9",
                        "lowest_ask": "60000.1",
                        "volume_24h_base": "100",
                        "volume_24h_quote": "6000000",
                        "change_percentage": "1.25",
                    }
                ],
                [[1_700_000_000, 10, "60000", "60100", "59900", "59950", "600000"]],
            ]
        )

        quote = await client.fetch_quote("gate", "BTC_USDT", "perpetual")
        candles = await client.fetch_candles("gate", "BTC_USDT", "perpetual", "1h", 20)

        self.assertIn("/futures/usdt/tickers?", client._get_json.await_args_list[0].args[1])
        self.assertIn("contract=BTC_USDT", client._get_json.await_args_list[0].args[1])
        self.assertIn("/futures/usdt/candlesticks?", client._get_json.await_args_list[1].args[1])
        self.assertEqual(quote.market_type, "perpetual")
        self.assertEqual(quote.turnover_24h, 6_000_000.0)
        self.assertEqual(candles[0].open, 59_950.0)
        self.assertEqual(candles[0].close, 60_000.0)


if __name__ == "__main__":
    unittest.main()
