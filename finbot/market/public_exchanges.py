from __future__ import annotations

import asyncio
from collections import defaultdict, deque
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urlencode

import httpx

from finbot.network.proxy_router import ProxyRouteBlocked, ProxyRouteDecision, ProxyRouter


BINANCE_MARKET_DATA_BASE = "https://data-api.binance.vision"
BINANCE_FUTURES_API_BASE = "https://fapi.binance.com"
BYBIT_API_BASE = "https://api.bybit.com"
BYBIT_ALTERNATE_API_BASE = "https://api.bytick.com"
GATE_API_BASE = "https://api.gateio.ws/api/v4"


@dataclass(frozen=True)
class RateLimitPolicy:
    window_seconds: float
    max_requests: int
    max_weight: int | None = None
    cool_down_seconds: float = 0.0


PUBLIC_MARKET_RATE_POLICIES = {
    "binance": RateLimitPolicy(window_seconds=60.0, max_requests=60, max_weight=120, cool_down_seconds=5.0),
    "bybit": RateLimitPolicy(window_seconds=5.0, max_requests=12, cool_down_seconds=600.0),
    "gate": RateLimitPolicy(window_seconds=10.0, max_requests=30, cool_down_seconds=10.0),
}
RETRYABLE_REQUEST_ERRORS = (
    httpx.ConnectError,
    httpx.ConnectTimeout,
    httpx.ReadTimeout,
    httpx.RemoteProtocolError,
    httpx.PoolTimeout,
    httpx.ReadError,
    httpx.WriteError,
)
RETRYABLE_STATUS_CODES = {408, 425, 500, 502, 503, 504}


class MarketDataProviderBlocked(RuntimeError):
    def __init__(self, provider: str, url: str, status_code: int, category: str, detail: str):
        self.provider = provider
        self.url = url
        self.status_code = status_code
        self.category = category
        self.detail = detail
        super().__init__(f"{category}: {provider} returned HTTP {status_code}: {detail}")


@dataclass(frozen=True)
class MarketQuote:
    provider: str
    market_type: str
    symbol: str
    normalized_symbol: str
    captured_at: str
    last_price: float | None
    bid: float | None
    ask: float | None
    price_change_pct_24h: float | None
    high_24h: float | None
    low_24h: float | None
    volume_24h: float | None
    turnover_24h: float | None
    source_url: str
    raw: Any

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class MarketCandle:
    provider: str
    market_type: str
    symbol: str
    normalized_symbol: str
    interval: str
    open_time: str
    open: float | None
    high: float | None
    low: float | None
    close: float | None
    volume: float | None
    turnover: float | None
    raw: Any

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class PublicExchangeMarketDataClient:
    def __init__(
        self,
        timeout_seconds: float = 20.0,
        user_agent: str = "FinBot market data",
        max_retries: int = 2,
        retry_base_delay_seconds: float = 0.3,
        proxy_router: ProxyRouter | None = None,
    ):
        self.timeout_seconds = timeout_seconds
        self.user_agent = user_agent
        self.max_retries = max(0, int(max_retries))
        self.retry_base_delay_seconds = max(0.0, float(retry_base_delay_seconds))
        self.proxy_router = proxy_router or ProxyRouter.from_pools()
        self._limiter = PublicMarketRateLimiter(PUBLIC_MARKET_RATE_POLICIES)
        self._request_observations: list[dict[str, Any]] = []

    async def fetch_quote(self, provider: str, symbol: str, market_type: str = "spot") -> MarketQuote:
        provider = _provider(provider)
        url = self.quote_url(provider, symbol, market_type)
        payload = await self._get_json(provider, url, _quote_weight(provider, symbol))
        captured_at = _now()
        if provider == "binance":
            return _binance_quote(symbol, market_type, captured_at, url, payload)
        if provider == "bybit":
            return _bybit_quote(symbol, market_type, captured_at, url, payload)
        if provider == "gate":
            return _gate_quote(symbol, market_type, captured_at, url, payload)
        raise ValueError(f"Unsupported provider: {provider}")

    async def fetch_candles(
        self,
        provider: str,
        symbol: str,
        market_type: str = "spot",
        interval: str = "1h",
        limit: int = 60,
    ) -> list[MarketCandle]:
        provider = _provider(provider)
        safe_limit = max(2, min(int(limit), 500))
        url = self.candles_url(provider, symbol, market_type, interval, safe_limit)
        payload = await self._get_json(provider, url, _candle_weight(provider))
        if provider == "binance":
            return _binance_candles(symbol, market_type, interval, payload)
        if provider == "bybit":
            return _bybit_candles(symbol, market_type, interval, payload)
        if provider == "gate":
            return _gate_candles(symbol, market_type, interval, payload)
        raise ValueError(f"Unsupported provider: {provider}")

    def quote_url(self, provider: str, symbol: str, market_type: str = "spot") -> str:
        provider = _provider(provider)
        if provider == "binance":
            query = urlencode({"symbol": _compact_symbol(symbol)})
            if _is_linear_market(market_type):
                return f"{BINANCE_FUTURES_API_BASE}/fapi/v1/ticker/24hr?{query}"
            return f"{BINANCE_MARKET_DATA_BASE}/api/v3/ticker/24hr?{query}"
        if provider == "bybit":
            query = urlencode({"category": _bybit_category(market_type), "symbol": _compact_symbol(symbol)})
            return f"{BYBIT_API_BASE}/v5/market/tickers?{query}"
        if provider == "gate":
            if _is_linear_market(market_type):
                query = urlencode({"contract": _gate_symbol(symbol)})
                return f"{GATE_API_BASE}/futures/usdt/tickers?{query}"
            query = urlencode({"currency_pair": _gate_symbol(symbol)})
            return f"{GATE_API_BASE}/spot/tickers?{query}"
        raise ValueError(f"Unsupported provider: {provider}")

    def candles_url(self, provider: str, symbol: str, market_type: str, interval: str, limit: int) -> str:
        provider = _provider(provider)
        if provider == "binance":
            query = urlencode({"symbol": _compact_symbol(symbol), "interval": _binance_interval(interval), "limit": limit})
            if _is_linear_market(market_type):
                return f"{BINANCE_FUTURES_API_BASE}/fapi/v1/klines?{query}"
            return f"{BINANCE_MARKET_DATA_BASE}/api/v3/klines?{query}"
        if provider == "bybit":
            query = urlencode(
                {
                    "category": _bybit_category(market_type),
                    "symbol": _compact_symbol(symbol),
                    "interval": _bybit_interval(interval),
                    "limit": limit,
                }
            )
            return f"{BYBIT_API_BASE}/v5/market/kline?{query}"
        if provider == "gate":
            if _is_linear_market(market_type):
                query = urlencode({"contract": _gate_symbol(symbol), "interval": _gate_interval(interval), "limit": limit})
                return f"{GATE_API_BASE}/futures/usdt/candlesticks?{query}"
            query = urlencode({"currency_pair": _gate_symbol(symbol), "interval": _gate_interval(interval), "limit": limit})
            return f"{GATE_API_BASE}/spot/candlesticks?{query}"
        raise ValueError(f"Unsupported provider: {provider}")

    def request_observations(self) -> list[dict[str, Any]]:
        return list(self._request_observations)

    async def request_json(self, provider: str, url: str, weight: int = 1) -> Any:
        """Fetch a public exchange payload through the configured routing and rate limits."""
        return await self._get_json(_provider(provider), url, max(1, int(weight)))

    async def _get_json(self, provider: str, url: str, weight: int = 1) -> Any:
        last_error: Exception | None = None
        candidate_urls = _public_host_candidates(provider, url)
        for index, candidate_url in enumerate(candidate_urls):
            try:
                return await self._get_json_routed(provider, candidate_url, weight)
            except MarketDataProviderBlocked as exc:
                last_error = exc
                can_try_alternate = (
                    index + 1 < len(candidate_urls)
                    and exc.category in {"provider-geo-blocked", "provider-access-blocked"}
                )
                if can_try_alternate:
                    continue
                raise
        if last_error:
            raise last_error
        raise RuntimeError(f"{provider} market data request has no available host: {url}")

    async def _get_json_routed(self, provider: str, url: str, weight: int = 1) -> Any:
        route_decisions = self.proxy_router.candidate_decisions(f"exchange:{provider}", url, attempts=1)
        last_error: Exception | None = None
        for index, proxy_decision in enumerate(route_decisions):
            if not proxy_decision.ok:
                self._request_observations.append(_proxy_block_observation(provider, url, weight, proxy_decision))
                last_error = ProxyRouteBlocked(proxy_decision)
                continue
            try:
                return await self._get_json_with_decision(provider, url, weight, proxy_decision)
            except MarketDataProviderBlocked as exc:
                last_error = exc
                if _should_try_next_route(exc, proxy_decision, route_decisions[index + 1 :]):
                    continue
                raise
        if last_error:
            raise last_error
        raise RuntimeError(f"{provider} market data request has no available proxy route: {url}")

    async def _get_json_with_decision(self, provider: str, url: str, weight: int, proxy_decision: ProxyRouteDecision) -> Any:
        attempts = self.max_retries + 1
        last_error: Exception | None = None
        for attempt in range(1, attempts + 1):
            wait_seconds = await self._limiter.acquire(provider, weight)
            try:
                kwargs: dict[str, Any] = {
                    "timeout": self.timeout_seconds,
                    "headers": {"User-Agent": self.user_agent},
                    "follow_redirects": True,
                }
                if proxy_decision.proxy:
                    kwargs["proxy"] = proxy_decision.proxy
                async with httpx.AsyncClient(
                    **kwargs,
                ) as client:
                    response = await client.get(url)
                self._request_observations.append(_request_observation(provider, url, weight, wait_seconds, response, attempt, proxy_decision))
                provider_block = _provider_block(provider, url, response)
                if provider_block:
                    if provider_block.category == "provider-rate-limited":
                        self._limiter.cool_down(provider)
                    raise provider_block
                if response.status_code in RETRYABLE_STATUS_CODES and attempt < attempts:
                    await self._sleep_before_retry(attempt)
                    continue
                response.raise_for_status()
                return response.json() if response.text else {}
            except httpx.HTTPError as exc:
                last_error = exc
                if isinstance(exc, RETRYABLE_REQUEST_ERRORS):
                    self._request_observations.append(_request_error_observation(provider, url, weight, wait_seconds, exc, attempt, proxy_decision))
                if attempt >= attempts or not isinstance(exc, RETRYABLE_REQUEST_ERRORS):
                    raise
                await self._sleep_before_retry(attempt)
        if last_error:
            raise last_error
        raise RuntimeError(f"{provider} market data request failed without response: {url}")

    async def _sleep_before_retry(self, attempt: int) -> None:
        delay = min(self.retry_base_delay_seconds * (2 ** max(0, attempt - 1)), 2.0)
        if delay > 0:
            await asyncio.sleep(delay)


class PublicMarketRateLimiter:
    def __init__(self, policies: dict[str, RateLimitPolicy]):
        self._policies = policies
        self._lock = asyncio.Lock()
        self._requests: dict[str, deque[tuple[float, int]]] = defaultdict(deque)
        self._blocked_until: dict[str, float] = {}

    async def acquire(self, provider: str, weight: int = 1) -> float:
        wait_seconds = 0.0
        while True:
            async with self._lock:
                now = asyncio.get_running_loop().time()
                blocked_until = self._blocked_until.get(provider, 0.0)
                if blocked_until > now:
                    wait_seconds = max(wait_seconds, blocked_until - now)
                else:
                    policy = self._policies[provider]
                    rows = self._requests[provider]
                    while rows and now - rows[0][0] >= policy.window_seconds:
                        rows.popleft()
                    request_count = len(rows)
                    weight_count = sum(row_weight for _, row_weight in rows)
                    weight_ok = policy.max_weight is None or weight_count + weight <= policy.max_weight
                    if request_count < policy.max_requests and weight_ok:
                        rows.append((now, weight))
                        return wait_seconds
                    oldest = rows[0][0] if rows else now
                    wait_seconds = max(wait_seconds, policy.window_seconds - (now - oldest), 0.05)
            await asyncio.sleep(wait_seconds)

    def cool_down(self, provider: str) -> None:
        policy = self._policies[provider]
        if not policy.cool_down_seconds:
            return
        loop_time = asyncio.get_running_loop().time()
        self._blocked_until[provider] = max(self._blocked_until.get(provider, 0.0), loop_time + policy.cool_down_seconds)


def supported_providers() -> tuple[str, ...]:
    return ("binance", "bybit", "gate")


def rate_limit_policy_snapshot() -> dict[str, dict[str, Any]]:
    return {provider: asdict(policy) for provider, policy in PUBLIC_MARKET_RATE_POLICIES.items()}


def _binance_quote(symbol: str, market_type: str, captured_at: str, url: str, payload: dict[str, Any]) -> MarketQuote:
    return MarketQuote(
        provider="binance",
        market_type=market_type,
        symbol=_compact_symbol(symbol),
        normalized_symbol=_compact_symbol(symbol),
        captured_at=captured_at,
        last_price=_to_float(payload.get("lastPrice")),
        bid=_to_float(payload.get("bidPrice")),
        ask=_to_float(payload.get("askPrice")),
        price_change_pct_24h=_to_float(payload.get("priceChangePercent")),
        high_24h=_to_float(payload.get("highPrice")),
        low_24h=_to_float(payload.get("lowPrice")),
        volume_24h=_to_float(payload.get("volume")),
        turnover_24h=_to_float(payload.get("quoteVolume")),
        source_url=url,
        raw=payload,
    )


def _bybit_quote(symbol: str, market_type: str, captured_at: str, url: str, payload: dict[str, Any]) -> MarketQuote:
    items = (((payload or {}).get("result") or {}).get("list") or []) if isinstance(payload, dict) else []
    item = items[0] if items else {}
    return MarketQuote(
        provider="bybit",
        market_type=_bybit_category(market_type),
        symbol=_compact_symbol(symbol),
        normalized_symbol=_compact_symbol(symbol),
        captured_at=captured_at,
        last_price=_to_float(item.get("lastPrice")),
        bid=_to_float(item.get("bid1Price") or item.get("bidPrice")),
        ask=_to_float(item.get("ask1Price") or item.get("askPrice")),
        price_change_pct_24h=_scale_ratio_pct(_to_float(item.get("price24hPcnt"))),
        high_24h=_to_float(item.get("highPrice24h")),
        low_24h=_to_float(item.get("lowPrice24h")),
        volume_24h=_to_float(item.get("volume24h")),
        turnover_24h=_to_float(item.get("turnover24h")),
        source_url=url,
        raw=payload,
    )


def _gate_quote(symbol: str, market_type: str, captured_at: str, url: str, payload: Any) -> MarketQuote:
    items = payload if isinstance(payload, list) else []
    item = items[0] if items else {}
    return MarketQuote(
        provider="gate",
        market_type=market_type,
        symbol=_gate_symbol(symbol),
        normalized_symbol=_compact_symbol(symbol),
        captured_at=captured_at,
        last_price=_to_float(item.get("last")),
        bid=_to_float(item.get("highest_bid")),
        ask=_to_float(item.get("lowest_ask")),
        price_change_pct_24h=_to_float(item.get("change_percentage")),
        high_24h=_to_float(item.get("high_24h")),
        low_24h=_to_float(item.get("low_24h")),
        volume_24h=_to_float(item.get("volume_24h_base") or item.get("base_volume") or item.get("volume_24h")),
        turnover_24h=_to_float(item.get("volume_24h_quote") or item.get("volume_24h_settle") or item.get("quote_volume")),
        source_url=url,
        raw=payload,
    )


def _binance_candles(symbol: str, market_type: str, interval: str, payload: Any) -> list[MarketCandle]:
    rows = payload if isinstance(payload, list) else []
    candles = []
    for row in rows:
        if not isinstance(row, list) or len(row) < 6:
            continue
        candles.append(
            MarketCandle(
                provider="binance",
                market_type=market_type,
                symbol=_compact_symbol(symbol),
                normalized_symbol=_compact_symbol(symbol),
                interval=_binance_interval(interval),
                open_time=_timestamp_ms(row[0]),
                open=_to_float(row[1]),
                high=_to_float(row[2]),
                low=_to_float(row[3]),
                close=_to_float(row[4]),
                volume=_to_float(row[5]),
                turnover=_to_float(row[7]) if len(row) > 7 else None,
                raw=row,
            )
        )
    return candles


def _bybit_candles(symbol: str, market_type: str, interval: str, payload: Any) -> list[MarketCandle]:
    rows = (((payload or {}).get("result") or {}).get("list") or []) if isinstance(payload, dict) else []
    candles = []
    for row in rows:
        if not isinstance(row, list) or len(row) < 6:
            continue
        candles.append(
            MarketCandle(
                provider="bybit",
                market_type=_bybit_category(market_type),
                symbol=_compact_symbol(symbol),
                normalized_symbol=_compact_symbol(symbol),
                interval=_bybit_interval(interval),
                open_time=_timestamp_ms(row[0]),
                open=_to_float(row[1]),
                high=_to_float(row[2]),
                low=_to_float(row[3]),
                close=_to_float(row[4]),
                volume=_to_float(row[5]),
                turnover=_to_float(row[6]) if len(row) > 6 else None,
                raw=row,
            )
        )
    candles.sort(key=lambda candle: candle.open_time)
    return candles


def _gate_candles(symbol: str, market_type: str, interval: str, payload: Any) -> list[MarketCandle]:
    rows = payload if isinstance(payload, list) else []
    candles = []
    for row in rows:
        if not isinstance(row, list) or len(row) < 6:
            continue
        candles.append(
            MarketCandle(
                provider="gate",
                market_type=market_type,
                symbol=_gate_symbol(symbol),
                normalized_symbol=_compact_symbol(symbol),
                interval=_gate_interval(interval),
                open_time=_timestamp_seconds(row[0]),
                open=_to_float(row[5]),
                high=_to_float(row[3]),
                low=_to_float(row[4]),
                close=_to_float(row[2]),
                volume=_to_float(row[1]),
                turnover=_to_float(row[6]) if len(row) > 6 else None,
                raw=row,
            )
        )
    candles.sort(key=lambda candle: candle.open_time)
    return candles


def _provider(value: str) -> str:
    provider = value.strip().lower()
    if provider not in supported_providers():
        raise ValueError(f"Unsupported provider: {value}")
    return provider


def _compact_symbol(symbol: str) -> str:
    return symbol.replace("_", "").replace("-", "").upper()


def _gate_symbol(symbol: str) -> str:
    compact = _compact_symbol(symbol)
    if compact.endswith("USDT"):
        return f"{compact[:-4]}_USDT"
    return symbol.replace("-", "_").upper()


def _bybit_category(market_type: str) -> str:
    value = market_type.strip().lower()
    if value in {"linear", "inverse", "spot"}:
        return value
    return "spot"


def _is_linear_market(market_type: str) -> bool:
    return market_type.strip().lower() in {"linear", "future", "perpetual"}


def _binance_interval(interval: str) -> str:
    return interval.strip().lower()


def _gate_interval(interval: str) -> str:
    return interval.strip().lower()


def _bybit_interval(interval: str) -> str:
    mapping = {
        "1m": "1",
        "3m": "3",
        "5m": "5",
        "15m": "15",
        "30m": "30",
        "1h": "60",
        "2h": "120",
        "4h": "240",
        "1d": "D",
        "1w": "W",
        "1mo": "M",
    }
    return mapping.get(interval.strip().lower(), interval)


def _timestamp_ms(value: Any) -> str:
    numeric = _to_float(value)
    if numeric is None:
        return str(value)
    return datetime.fromtimestamp(numeric / 1000, tz=timezone.utc).isoformat()


def _timestamp_seconds(value: Any) -> str:
    numeric = _to_float(value)
    if numeric is None:
        return str(value)
    return datetime.fromtimestamp(numeric, tz=timezone.utc).isoformat()


def _to_float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _scale_ratio_pct(value: float | None) -> float | None:
    if value is None:
        return None
    return round(value * 100, 6)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _quote_weight(provider: str, symbol: str) -> int:
    if provider == "binance":
        return 2 if symbol else 80
    return 1


def _candle_weight(provider: str) -> int:
    return 2 if provider == "binance" else 1


def _public_host_candidates(provider: str, url: str) -> tuple[str, ...]:
    if provider == "bybit" and url.startswith(f"{BYBIT_API_BASE}/"):
        alternate = f"{BYBIT_ALTERNATE_API_BASE}{url[len(BYBIT_API_BASE):]}"
        return (url, alternate)
    return (url,)


def _request_observation(
    provider: str,
    url: str,
    weight: int,
    wait_seconds: float,
    response: httpx.Response,
    attempt: int,
    proxy_decision: ProxyRouteDecision,
) -> dict[str, Any]:
    return {
        "provider": provider,
        "url": url,
        "status_code": response.status_code,
        "attempt": attempt,
        "proxy_route": proxy_decision.to_dict(),
        "local_weight": weight,
        "local_wait_seconds": round(wait_seconds, 4),
        "rate_headers": {
            key: value
            for key, value in response.headers.items()
            if key.lower().startswith(("x-mbx-used-weight", "x-bapi-limit", "retry-after"))
        },
        "observed_at": _now(),
    }


def _request_error_observation(
    provider: str,
    url: str,
    weight: int,
    wait_seconds: float,
    exc: Exception,
    attempt: int,
    proxy_decision: ProxyRouteDecision,
) -> dict[str, Any]:
    return {
        "provider": provider,
        "url": url,
        "status_code": None,
        "attempt": attempt,
        "proxy_route": proxy_decision.to_dict(),
        "local_weight": weight,
        "local_wait_seconds": round(wait_seconds, 4),
        "rate_headers": {},
        "error_type": type(exc).__name__,
        "error": str(exc)[:240],
        "observed_at": _now(),
    }


def _proxy_block_observation(provider: str, url: str, weight: int, proxy_decision: ProxyRouteDecision) -> dict[str, Any]:
    return {
        "provider": provider,
        "url": url,
        "status_code": None,
        "attempt": 0,
        "proxy_route": proxy_decision.to_dict(),
        "local_weight": weight,
        "local_wait_seconds": 0.0,
        "rate_headers": {},
        "error_type": "ProxyRouteBlocked",
        "error": proxy_decision.reason,
        "observed_at": _now(),
    }


def _provider_block(provider: str, url: str, response: httpx.Response) -> MarketDataProviderBlocked | None:
    if response.status_code == 403:
        text = response.text[:600]
        lower_text = text.lower()
        if "configured to block access from your country" in lower_text or "country" in lower_text and "cloudfront" in lower_text:
            return MarketDataProviderBlocked(
                provider=provider,
                url=url,
                status_code=response.status_code,
                category="provider-geo-blocked",
                detail=_compact_error_text(text),
            )
        return MarketDataProviderBlocked(
            provider=provider,
            url=url,
            status_code=response.status_code,
            category="provider-access-blocked",
            detail=_compact_error_text(text),
        )
    if response.status_code in {418, 429}:
        return MarketDataProviderBlocked(
            provider=provider,
            url=url,
            status_code=response.status_code,
            category="provider-rate-limited",
            detail=_compact_error_text(response.text[:600]),
        )
    return None


def _should_try_next_route(
    exc: MarketDataProviderBlocked,
    current_decision: ProxyRouteDecision,
    remaining_decisions: list[ProxyRouteDecision],
) -> bool:
    if current_decision.proxy is None:
        return False
    if exc.category not in {"provider-geo-blocked", "provider-access-blocked"}:
        return False
    return any(decision.ok and decision.proxy is None for decision in remaining_decisions)


def _compact_error_text(value: str) -> str:
    lines = [line.strip() for line in value.splitlines() if line.strip()]
    return " ".join(lines)[:300] if lines else ""
