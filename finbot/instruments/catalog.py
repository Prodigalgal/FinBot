from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlencode

from finbot.instruments.models import CatalogInstrument, InstrumentMarket
from finbot.market.public_exchanges import (
    BINANCE_MARKET_DATA_BASE,
    BINANCE_FUTURES_API_BASE,
    BYBIT_API_BASE,
    GATE_API_BASE,
    PublicExchangeMarketDataClient,
)
from finbot.storage.sqlite_store import SQLiteStore


class InstrumentCatalogSynchronizer:
    def __init__(self, store: SQLiteStore, client: PublicExchangeMarketDataClient):
        self.store = store
        self.client = client

    async def sync(self, markets: tuple[InstrumentMarket, ...]) -> dict[str, Any]:
        self.store.init_schema()
        captured_at = _now()
        results: list[dict[str, Any]] = []
        total_count = 0
        active_count = 0
        for market in markets:
            try:
                instruments = await self._fetch_market(market, captured_at)
                if not instruments:
                    raise RuntimeError(
                        f"{market.provider}:{market.market_type} returned an empty instrument catalog; existing rows were preserved"
                    )
                counts = self.store.sync_instrument_catalog(
                    market.provider,
                    market.market_type,
                    [instrument.to_record() for instrument in instruments],
                    captured_at,
                )
                total_count += counts["instrument_count"]
                active_count += counts["active_count"]
                results.append({"status": "passed", **market.to_dict(), **counts})
            except Exception as exc:
                results.append(
                    {
                        "status": "failed",
                        **market.to_dict(),
                        "instrument_count": 0,
                        "active_count": 0,
                        "error": f"{type(exc).__name__}: {exc}",
                    }
                )
        passed_count = sum(1 for result in results if result["status"] == "passed")
        status = "passed" if passed_count == len(results) else "partial" if passed_count else "failed"
        return {
            "status": status,
            "captured_at": captured_at,
            "market_count": len(markets),
            "passed_market_count": passed_count,
            "instrument_count": total_count,
            "active_count": active_count,
            "markets": results,
            "request_observations": self.client.request_observations(),
        }

    async def _fetch_market(self, market: InstrumentMarket, captured_at: str) -> list[CatalogInstrument]:
        if market.provider == "gate":
            return await self._fetch_gate(market, captured_at)
        if market.provider == "binance":
            return await self._fetch_binance(market, captured_at)
        if market.provider == "bybit":
            return await self._fetch_bybit(market, captured_at)
        raise ValueError(f"Unsupported instrument provider: {market.provider}")

    async def _fetch_gate(self, market: InstrumentMarket, captured_at: str) -> list[CatalogInstrument]:
        if market.market_type == "spot":
            return await self._fetch_gate_spot(captured_at)
        if market.market_type in {"linear", "future", "perpetual"}:
            return await self._fetch_gate_usdt_perpetual(market.market_type, captured_at)
        raise ValueError(f"Gate catalog market type is not supported: {market.market_type}")

    async def _fetch_gate_spot(self, captured_at: str) -> list[CatalogInstrument]:
        instruments_url = f"{GATE_API_BASE}/spot/currency_pairs"
        tickers_url = f"{GATE_API_BASE}/spot/tickers"
        rows = await self.client.request_json("gate", instruments_url)
        ticker_rows = await self.client.request_json("gate", tickers_url)
        tickers = {
            str(row.get("currency_pair")): row
            for row in ticker_rows
            if isinstance(row, dict) and row.get("currency_pair")
        } if isinstance(ticker_rows, list) else {}
        instruments = []
        for row in rows if isinstance(rows, list) else []:
            if not isinstance(row, dict) or not row.get("id") or not row.get("base"):
                continue
            ticker = tickers.get(str(row["id"]), {})
            instruments.append(
                CatalogInstrument(
                    provider="gate",
                    market_type="spot",
                    symbol=str(row["id"]),
                    base_asset=str(row["base"]).upper(),
                    quote_asset=str(row.get("quote") or "").upper() or None,
                    captured_at=captured_at,
                    active=str(row.get("trade_status") or "tradable").lower() == "tradable",
                    tick_size=_step_from_precision(row.get("precision")),
                    amount_step=_step_from_precision(row.get("amount_precision")),
                    min_amount=_float(row.get("min_base_amount")),
                    min_notional=_float(row.get("min_quote_amount")),
                    source_url=instruments_url,
                    raw=row,
                    market_snapshot=_gate_snapshot(ticker),
                )
            )
        return instruments

    async def _fetch_gate_usdt_perpetual(
        self,
        market_type: str,
        captured_at: str,
    ) -> list[CatalogInstrument]:
        instruments_url = f"{GATE_API_BASE}/futures/usdt/contracts"
        tickers_url = f"{GATE_API_BASE}/futures/usdt/tickers"
        rows = await self.client.request_json("gate", instruments_url)
        ticker_rows = await self.client.request_json("gate", tickers_url)
        tickers = {
            str(row.get("contract")): row
            for row in ticker_rows
            if isinstance(row, dict) and row.get("contract")
        } if isinstance(ticker_rows, list) else {}
        instruments = []
        for row in rows if isinstance(rows, list) else []:
            if not isinstance(row, dict) or not row.get("name"):
                continue
            symbol = str(row["name"]).upper()
            base_asset, separator, quote_asset = symbol.partition("_")
            if not separator or quote_asset != "USDT":
                continue
            ticker = tickers.get(symbol, {})
            contract_size = _float(row.get("quanto_multiplier"))
            min_contracts = max(1.0, _float(row.get("order_size_min")) or 1.0)
            last_price = _float(ticker.get("last"))
            min_notional = (
                min_contracts * contract_size * last_price
                if min_contracts is not None and contract_size is not None and last_price is not None
                else None
            )
            contract_type = str(row.get("type") or "direct").lower()
            instruments.append(
                CatalogInstrument(
                    provider="gate",
                    market_type=market_type,
                    symbol=symbol,
                    base_asset=base_asset,
                    quote_asset=quote_asset,
                    settle_asset="USDT",
                    captured_at=captured_at,
                    active=str(row.get("status") or "trading").lower() == "trading",
                    contract=True,
                    linear=contract_type == "direct",
                    inverse=contract_type == "inverse",
                    contract_size=contract_size,
                    tick_size=_float(row.get("order_price_round")),
                    amount_step=1.0,
                    min_amount=min_contracts,
                    min_notional=min_notional,
                    leverage={
                        "type": "advertised-constraint",
                        "min": _float(row.get("leverage_min")),
                        "max": _float(row.get("leverage_max")),
                        "notional_tier_dependent": True,
                        "note": "Actual leverage depends on Gate risk tiers and TestNet account state.",
                    },
                    source_url=instruments_url,
                    raw=row,
                    market_snapshot=_gate_futures_snapshot(ticker),
                )
            )
        return instruments

    async def _fetch_binance(self, market: InstrumentMarket, captured_at: str) -> list[CatalogInstrument]:
        if market.market_type == "spot":
            instruments_url = f"{BINANCE_MARKET_DATA_BASE}/api/v3/exchangeInfo"
            tickers_url = f"{BINANCE_MARKET_DATA_BASE}/api/v3/ticker/24hr"
            contract = False
        elif market.market_type in {"linear", "future", "perpetual"}:
            instruments_url = f"{BINANCE_FUTURES_API_BASE}/fapi/v1/exchangeInfo"
            tickers_url = f"{BINANCE_FUTURES_API_BASE}/fapi/v1/ticker/24hr"
            contract = True
        else:
            raise ValueError(f"Binance catalog market type is not supported: {market.market_type}")
        payload = await self.client.request_json("binance", instruments_url, weight=10)
        ticker_rows = await self.client.request_json("binance", tickers_url, weight=40)
        tickers = {
            str(row.get("symbol")): row
            for row in ticker_rows
            if isinstance(row, dict) and row.get("symbol")
        } if isinstance(ticker_rows, list) else {}
        instruments = []
        for row in payload.get("symbols", []) if isinstance(payload, dict) else []:
            if not isinstance(row, dict) or not row.get("symbol") or not row.get("baseAsset"):
                continue
            filters = {item.get("filterType"): item for item in row.get("filters", []) if isinstance(item, dict)}
            lot_filter = filters.get("LOT_SIZE", {})
            price_filter = filters.get("PRICE_FILTER", {})
            notional_filter = filters.get("NOTIONAL", {}) or filters.get("MIN_NOTIONAL", {})
            ticker = tickers.get(str(row["symbol"]), {})
            expiry = _timestamp_ms(row.get("deliveryDate")) if contract and row.get("contractType") != "PERPETUAL" else None
            instruments.append(
                CatalogInstrument(
                    provider="binance",
                    market_type=market.market_type,
                    symbol=str(row["symbol"]),
                    base_asset=str(row["baseAsset"]).upper(),
                    quote_asset=str(row.get("quoteAsset") or "").upper() or None,
                    settle_asset=str(row.get("marginAsset") or row.get("quoteAsset") or "").upper() or None,
                    captured_at=captured_at,
                    active=str(row.get("status") or "").upper() == "TRADING",
                    contract=contract,
                    linear=True if contract else None,
                    inverse=False if contract else None,
                    contract_size=1.0 if contract else None,
                    expiry=expiry,
                    tick_size=_float(price_filter.get("tickSize")),
                    amount_step=_float(lot_filter.get("stepSize")),
                    min_amount=_float(lot_filter.get("minQty")),
                    min_notional=_float(notional_filter.get("minNotional") or notional_filter.get("notional")),
                    leverage={
                        "type": "notional-tier-dependent",
                        "max": None,
                        "note": "Actual leverage depends on venue tiers and account state.",
                    } if contract else {},
                    source_url=instruments_url,
                    raw=row,
                    market_snapshot=_binance_snapshot(ticker),
                )
            )
        return instruments

    async def _fetch_bybit(self, market: InstrumentMarket, captured_at: str) -> list[CatalogInstrument]:
        category = "linear" if market.market_type in {"linear", "future", "perpetual"} else "spot"
        ticker_url = f"{BYBIT_API_BASE}/v5/market/tickers?{urlencode({'category': category})}"
        ticker_payload = await self.client.request_json("bybit", ticker_url)
        ticker_rows = ((ticker_payload.get("result") or {}).get("list") or []) if isinstance(ticker_payload, dict) else []
        tickers = {
            str(row.get("symbol")): row
            for row in ticker_rows
            if isinstance(row, dict) and row.get("symbol")
        }
        rows: list[dict[str, Any]] = []
        cursor = ""
        source_url = ""
        for _ in range(20):
            query = {"category": category, "limit": 1000}
            if cursor:
                query["cursor"] = cursor
            source_url = f"{BYBIT_API_BASE}/v5/market/instruments-info?{urlencode(query)}"
            payload = await self.client.request_json("bybit", source_url)
            result = payload.get("result") or {} if isinstance(payload, dict) else {}
            rows.extend(row for row in result.get("list", []) if isinstance(row, dict))
            next_cursor = str(result.get("nextPageCursor") or "")
            if not next_cursor or next_cursor == cursor:
                break
            cursor = next_cursor
        instruments = []
        for row in rows:
            if not row.get("symbol") or not row.get("baseCoin"):
                continue
            lot_filter = row.get("lotSizeFilter") or {}
            price_filter = row.get("priceFilter") or {}
            leverage_filter = row.get("leverageFilter") or {}
            contract = category == "linear"
            ticker = tickers.get(str(row["symbol"]), {})
            max_leverage = _float(leverage_filter.get("maxLeverage"))
            instruments.append(
                CatalogInstrument(
                    provider="bybit",
                    market_type=category,
                    symbol=str(row["symbol"]),
                    base_asset=str(row["baseCoin"]).upper(),
                    quote_asset=str(row.get("quoteCoin") or "").upper() or None,
                    settle_asset=str(row.get("settleCoin") or row.get("quoteCoin") or "").upper() or None,
                    captured_at=captured_at,
                    active=str(row.get("status") or "").lower() == "trading",
                    contract=contract,
                    linear=True if contract else None,
                    inverse=False if contract else None,
                    contract_size=_float(row.get("contractSize")) or (1.0 if contract else None),
                    expiry=_timestamp_ms(row.get("deliveryTime")),
                    tick_size=_float(price_filter.get("tickSize")),
                    amount_step=_float(lot_filter.get("qtyStep") or lot_filter.get("basePrecision")),
                    min_amount=_float(lot_filter.get("minOrderQty")),
                    min_notional=_float(lot_filter.get("minNotionalValue") or lot_filter.get("minOrderAmt")),
                    leverage={
                        "type": "advertised-constraint",
                        "max": max_leverage,
                        "min": _float(leverage_filter.get("minLeverage")),
                        "step": _float(leverage_filter.get("leverageStep")),
                        "notional_tier_dependent": True,
                        "note": "Actual leverage depends on risk tiers and account state.",
                    } if contract else {},
                    source_url=source_url,
                    raw=row,
                    market_snapshot=_bybit_snapshot(ticker),
                )
            )
        return instruments


def _gate_snapshot(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "last_price": _float(row.get("last")),
        "bid": _float(row.get("highest_bid")),
        "ask": _float(row.get("lowest_ask")),
        "volume_24h": _float(row.get("base_volume")),
        "turnover_24h": _float(row.get("quote_volume")),
        "price_change_pct_24h": _float(row.get("change_percentage")),
        "raw": row,
    }


def _gate_futures_snapshot(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "last_price": _float(row.get("last")),
        "bid": _float(row.get("highest_bid")),
        "ask": _float(row.get("lowest_ask")),
        "volume_24h": _float(row.get("volume_24h_base") or row.get("volume_24h")),
        "turnover_24h": _float(row.get("volume_24h_quote") or row.get("volume_24h_settle")),
        "price_change_pct_24h": _float(row.get("change_percentage")),
        "mark_price": _float(row.get("mark_price")),
        "index_price": _float(row.get("index_price")),
        "raw": row,
    }


def _binance_snapshot(row: dict[str, Any]) -> dict[str, Any]:
    return {
        "last_price": _float(row.get("lastPrice")),
        "bid": _float(row.get("bidPrice")),
        "ask": _float(row.get("askPrice")),
        "volume_24h": _float(row.get("volume")),
        "turnover_24h": _float(row.get("quoteVolume")),
        "price_change_pct_24h": _float(row.get("priceChangePercent")),
        "raw": row,
    }


def _bybit_snapshot(row: dict[str, Any]) -> dict[str, Any]:
    price_change = _float(row.get("price24hPcnt"))
    return {
        "last_price": _float(row.get("lastPrice")),
        "bid": _float(row.get("bid1Price")),
        "ask": _float(row.get("ask1Price")),
        "volume_24h": _float(row.get("volume24h")),
        "turnover_24h": _float(row.get("turnover24h")),
        "price_change_pct_24h": price_change * 100 if price_change is not None else None,
        "raw": row,
    }


def _float(value: Any) -> float | None:
    try:
        if value in (None, ""):
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def _step_from_precision(value: Any) -> float | None:
    try:
        precision = int(value)
    except (TypeError, ValueError):
        return None
    return 10.0 ** -precision


def _timestamp_ms(value: Any) -> str | None:
    number = _float(value)
    if number is None or number <= 0:
        return None
    return datetime.fromtimestamp(number / 1000, tz=timezone.utc).isoformat()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
