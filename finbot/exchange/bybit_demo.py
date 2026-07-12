from __future__ import annotations

import hashlib
import hmac
import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_DOWN
from typing import Any, Callable
from urllib.parse import urlencode, urlparse

import httpx

from finbot.exchange.account_snapshot import (
    ExchangeAccountSnapshot,
    ExchangePositionSnapshot,
    PnlWindow,
    position_roe_pct,
)
from finbot.exchange.paper_execution import (
    PaperExecutionBlocked,
    PaperSubmissionResult,
    PreparedPaperOrder,
)
from finbot.instruments.models import stable_id


BYBIT_DEMO_API_BASE = "https://api-demo.bybit.com"
BYBIT_REAL_API_HOST = "api.bybit.com"


class BybitDemoApiError(RuntimeError):
    def __init__(self, status_code: int, code: int, message: str):
        self.status_code = status_code
        self.code = code
        self.message = message
        super().__init__(f"Bybit Demo {code or status_code}: {message}")


class BybitDemoClient:
    def __init__(
        self,
        api_key: str,
        api_secret: str,
        *,
        timeout_seconds: float = 20.0,
        recv_window_ms: int = 5_000,
        proxy: str | None = None,
        transport: httpx.BaseTransport | None = None,
        clock: Callable[[], float] = time.time,
        base_url: str = BYBIT_DEMO_API_BASE,
    ):
        normalized_base = base_url.rstrip("/")
        if normalized_base != BYBIT_DEMO_API_BASE:
            raise ValueError("Bybit 私有执行仅允许固定 Demo host")
        if urlparse(normalized_base).hostname == BYBIT_REAL_API_HOST:
            raise ValueError("Bybit Mainnet 私有 host 被禁止")
        self.api_key = api_key.strip()
        self._api_secret = api_secret.strip()
        self.base_url = normalized_base
        self.recv_window_ms = max(1_000, min(int(recv_window_ms), 10_000))
        self.clock = clock
        self._client = httpx.Client(
            timeout=timeout_seconds,
            proxy=proxy,
            transport=transport,
            follow_redirects=False,
            headers={"User-Agent": "FinBot paper execution"},
        )

    def get_wallet_balance(self, *, account_type: str = "UNIFIED", coin: str = "USDT") -> dict[str, Any]:
        payload = self._request(
            "GET",
            "/v5/account/wallet-balance",
            query={"accountType": account_type, "coin": coin},
        )
        rows = ((payload.get("result") or {}).get("list") or []) if isinstance(payload, dict) else []
        account = next((row for row in rows if isinstance(row, dict)), None)
        if account is None:
            raise BybitDemoApiError(502, -1, "账户响应缺少账户数据")
        return account

    def list_positions(
        self,
        symbol: str | None = None,
        *,
        settle_coin: str | None = None,
        page_size: int = 200,
        max_records: int = 1_000,
    ) -> list[dict[str, Any]]:
        rows, _complete = self._list_result_pages(
            "/v5/position/list",
            query={
                "category": "linear",
                "symbol": symbol,
                "settleCoin": settle_coin,
            },
            page_size=page_size,
            max_records=max_records,
        )
        return rows

    def list_closed_pnl(
        self,
        *,
        start_time_ms: int,
        end_time_ms: int,
        page_size: int = 100,
        max_records: int = 1_000,
    ) -> tuple[list[dict[str, Any]], bool]:
        return self._list_result_pages(
            "/v5/position/closed-pnl",
            query={
                "category": "linear",
                "startTime": start_time_ms,
                "endTime": end_time_ms,
            },
            page_size=page_size,
            max_records=max_records,
        )

    def create_order(self, order: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", "/v5/order/create", body=order)

    def signing_headers(self, payload_text: str, timestamp_ms: str) -> dict[str, str]:
        signature_payload = f"{timestamp_ms}{self.api_key}{self.recv_window_ms}{payload_text}"
        signature = hmac.new(
            self._api_secret.encode("utf-8"),
            signature_payload.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return {
            "X-BAPI-API-KEY": self.api_key,
            "X-BAPI-TIMESTAMP": timestamp_ms,
            "X-BAPI-SIGN": signature,
            "X-BAPI-SIGN-TYPE": "2",
            "X-BAPI-RECV-WINDOW": str(self.recv_window_ms),
        }

    def close(self) -> None:
        self._client.close()

    def _request(
        self,
        method: str,
        path: str,
        *,
        query: dict[str, Any] | None = None,
        body: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        query_string = urlencode([(key, value) for key, value in (query or {}).items() if value is not None])
        body_text = json.dumps(body, ensure_ascii=False, separators=(",", ":")) if body is not None else ""
        payload_text = query_string if method.upper() == "GET" else body_text
        timestamp_ms = str(int(self.clock() * 1000))
        headers = {"Accept": "application/json", **self.signing_headers(payload_text, timestamp_ms)}
        if body is not None:
            headers["Content-Type"] = "application/json"
        url = f"{self.base_url}{path}"
        if query_string:
            url = f"{url}?{query_string}"
        response = self._client.request(method, url, content=body_text or None, headers=headers)
        if response.is_redirect:
            raise BybitDemoApiError(response.status_code, -1, "Demo 请求禁止重定向")
        payload = _response_json(response)
        code = int(payload.get("retCode") or 0) if isinstance(payload, dict) else -1
        if response.status_code >= 400 or code != 0:
            raise BybitDemoApiError(
                response.status_code,
                code,
                str(payload.get("retMsg") or response.text[:300]) if isinstance(payload, dict) else response.text[:300],
            )
        return payload

    def _list_result_pages(
        self,
        path: str,
        *,
        query: dict[str, Any],
        page_size: int,
        max_records: int,
    ) -> tuple[list[dict[str, Any]], bool]:
        rows: list[dict[str, Any]] = []
        cursor: str | None = None
        limit = max(1, min(page_size, 200))
        complete = True
        while len(rows) < max_records:
            payload = self._request(
                "GET",
                path,
                query={**query, "limit": limit, "cursor": cursor},
            )
            result = payload.get("result") if isinstance(payload.get("result"), dict) else {}
            page = result.get("list") if isinstance(result, dict) else []
            if not isinstance(page, list):
                raise BybitDemoApiError(502, -1, "分页响应缺少 list")
            rows.extend(row for row in page if isinstance(row, dict))
            next_cursor = str(result.get("nextPageCursor") or "")
            if not next_cursor or next_cursor == cursor:
                break
            cursor = next_cursor
        if len(rows) >= max_records and cursor:
            complete = False
        return rows[:max_records], complete


@dataclass
class BybitDemoAdapter:
    client: BybitDemoClient | None
    blocker: str | None = None

    adapter_id = "bybit_demo"
    provider = "bybit"
    environment = "demo"
    market_types = frozenset({"linear"})
    display_name = "Bybit Demo"

    def readiness(self) -> dict[str, Any]:
        blockers = [value for value in (self.blocker, "缺少 Bybit Demo API key/secret" if self.client is None else None) if value]
        return {
            "adapter_id": self.adapter_id,
            "provider": self.provider,
            "environment": self.environment,
            "status": "ready" if not blockers else "blocked",
            "credentials_configured": self.client is not None,
            "blockers": blockers,
        }

    def prepare_order(
        self,
        *,
        instrument: dict[str, Any],
        decision: dict[str, Any],
        max_notional_usdt: float,
    ) -> PreparedPaperOrder:
        price = _positive_decimal(instrument.get("last_price")) or _positive_decimal(decision.get("entry_reference"))
        step = _positive_decimal(instrument.get("amount_step"))
        minimum = _positive_decimal(instrument.get("min_amount"))
        min_notional = _positive_decimal(instrument.get("min_notional")) or Decimal("0")
        if price is None or step is None or minimum is None:
            raise PaperExecutionBlocked("Bybit 合约缺少 Mainnet 价格或数量规格")
        raw = _instrument_raw(instrument)
        lot_filter = raw.get("lotSizeFilter") if isinstance(raw.get("lotSizeFilter"), dict) else {}
        maximum = _positive_decimal(lot_filter.get("maxMktOrderQty"))
        quantity = (Decimal(str(max_notional_usdt)) / price / step).to_integral_value(rounding=ROUND_DOWN) * step
        if maximum is not None:
            quantity = min(quantity, maximum)
        if quantity < minimum or quantity * price < min_notional:
            raise PaperExecutionBlocked("名义限额不足以满足 Bybit 最小数量或最小名义价值")
        action = str(decision["action"]).upper()
        client_order_id = f"finbot-{stable_id(decision['decision_id'], self.adapter_id)[:24]}"
        request = {
            "category": "linear",
            "symbol": str(instrument["symbol"]).replace("_", "").upper(),
            "side": "Buy" if action == "BUY" else "Sell",
            "orderType": "Market",
            "qty": _decimal_text(quantity),
            "timeInForce": "IOC",
            "positionIdx": 0,
            "orderLinkId": client_order_id,
            "slippageToleranceType": "Percent",
            "slippageTolerance": "1",
        }
        signed_quantity = quantity if action == "BUY" else -quantity
        return PreparedPaperOrder(
            adapter_id=self.adapter_id,
            provider=self.provider,
            environment=self.environment,
            product_id=instrument.get("product_id"),
            instrument_id=str(instrument["instrument_id"]),
            symbol=request["symbol"],
            market_type=str(instrument["market_type"]),
            action=action,
            client_order_id=client_order_id,
            requested_notional=float(quantity * price),
            requested_quantity=float(signed_quantity),
            request=request,
            metadata={"reference_price": float(price), "quantity_step": str(step), "settle": "USDT"},
        )

    def fetch_account_snapshot(self, *, pnl_window: PnlWindow) -> ExchangeAccountSnapshot:
        if self.client is None:
            raise PaperExecutionBlocked("Bybit Demo client 未配置")
        fetched_at = datetime.now(timezone.utc)
        account = self._wallet_account()
        warnings: list[str] = []
        coins = account.get("coin") if isinstance(account.get("coin"), list) else []
        usdt = next(
            (row for row in coins if isinstance(row, dict) and str(row.get("coin") or "").upper() == "USDT"),
            {},
        )

        try:
            raw_positions = self.client.list_positions(settle_coin="USDT")
        except (BybitDemoApiError, httpx.HTTPError) as exc:
            raw_positions = []
            warnings.append(f"持仓读取失败：{_safe_warning(exc)}")

        closed_pnl_rows: list[dict[str, Any]] = []
        if pnl_window.is_all:
            realized_pnl = _optional_float(usdt.get("cumRealisedPnl"))
            realized_complete = realized_pnl is not None
            if realized_pnl is None:
                warnings.append("账户响应未提供累计已实现盈亏")
        else:
            try:
                closed_pnl_rows, realized_complete = self._closed_pnl_for_window(pnl_window)
                realized_pnl = round(sum(_float(row.get("closedPnl"), 0.0) for row in closed_pnl_rows), 8)
                if not realized_complete:
                    warnings.append("所选区间平仓记录超过查询上限，已实现盈亏为截断结果")
            except (BybitDemoApiError, httpx.HTTPError) as exc:
                realized_complete = False
                realized_pnl = None
                warnings.append(f"所选区间已实现盈亏读取失败：{_safe_warning(exc)}")

        positions = tuple(
            position
            for position in (_bybit_position_snapshot(row) for row in raw_positions)
            if position is not None
        )
        unrealized_pnl = _first_float(account.get("totalPerpUPL"), usdt.get("unrealisedPnl"))
        if unrealized_pnl is None:
            unrealized_pnl = round(sum(position.unrealized_pnl_usdt or 0.0 for position in positions), 8)
        return ExchangeAccountSnapshot(
            adapter_id=self.adapter_id,
            display_name=self.display_name,
            provider=self.provider,
            environment=self.environment,
            status="partial" if warnings else "ready",
            currency="USDT",
            total_equity_usdt=_first_float(usdt.get("equity"), account.get("totalEquity")),
            wallet_balance_usdt=_first_float(usdt.get("walletBalance"), account.get("totalWalletBalance")),
            available_balance_usdt=_first_float(
                usdt.get("availableToWithdraw"),
                account.get("totalAvailableBalance"),
            ),
            margin_used_usdt=_optional_float(account.get("totalInitialMargin")),
            maintenance_margin_usdt=_optional_float(account.get("totalMaintenanceMargin")),
            unrealized_pnl_usdt=unrealized_pnl,
            realized_pnl_usdt=realized_pnl,
            realized_pnl_complete=realized_complete,
            realized_pnl_record_count=len(closed_pnl_rows),
            positions=positions,
            warnings=tuple(warnings),
            fetched_at=fetched_at.isoformat(),
            metadata={"account_type": account.get("accountType") or "UNIFIED"},
        )

    def _closed_pnl_for_window(self, pnl_window: PnlWindow) -> tuple[list[dict[str, Any]], bool]:
        if self.client is None or pnl_window.start_at is None:
            return [], False
        start_ms = int(pnl_window.start_at.timestamp() * 1000)
        final_end_ms = int(pnl_window.end_at.timestamp() * 1000)
        max_slice_ms = 7 * 24 * 60 * 60 * 1000
        rows: list[dict[str, Any]] = []
        complete = True
        while start_ms <= final_end_ms:
            end_ms = min(final_end_ms, start_ms + max_slice_ms - 1)
            page_rows, page_complete = self.client.list_closed_pnl(
                start_time_ms=start_ms,
                end_time_ms=end_ms,
            )
            rows.extend(page_rows)
            complete = complete and page_complete
            start_ms = end_ms + 1
        return rows, complete

    def _wallet_account(self) -> dict[str, Any]:
        if self.client is None:
            raise PaperExecutionBlocked("Bybit Demo client 未配置")
        try:
            return self.client.get_wallet_balance(account_type="UNIFIED", coin="USDT")
        except BybitDemoApiError as exc:
            if exc.code not in {10008, 10028, 100028}:
                raise
            return self.client.get_wallet_balance(account_type="CONTRACT", coin="USDT")

    def submit_order(self, order: PreparedPaperOrder) -> PaperSubmissionResult:
        if self.client is None:
            raise PaperExecutionBlocked("Bybit Demo client 未配置")
        positions = self.client.list_positions(order.symbol)
        open_positions = [position for position in positions if _positive_decimal(position.get("size")) is not None]
        if open_positions:
            return PaperSubmissionResult(
                status="skipped_existing_position",
                exchange_order_id=None,
                response={"positions": [_safe_position(position) for position in open_positions]},
                error="Bybit Demo 已有该合约未平仓位",
            )
        response = self.client.create_order(order.request)
        result = response.get("result") if isinstance(response.get("result"), dict) else {}
        return PaperSubmissionResult(
            status="submitted",
            exchange_order_id=str(result.get("orderId") or "") or None,
            response={
                "retCode": response.get("retCode"),
                "retMsg": response.get("retMsg"),
                "orderId": result.get("orderId"),
                "orderLinkId": result.get("orderLinkId"),
                "time": response.get("time"),
            },
        )

    def close(self) -> None:
        if self.client is not None:
            self.client.close()


def _response_json(response: httpx.Response) -> dict[str, Any]:
    try:
        payload = response.json()
        return payload if isinstance(payload, dict) else {"retCode": -1, "retMsg": "响应不是对象"}
    except ValueError:
        return {"retCode": -1, "retMsg": response.text[:300]}


def _instrument_raw(instrument: dict[str, Any]) -> dict[str, Any]:
    try:
        payload = json.loads(str(instrument.get("payload_json") or "{}"))
    except (TypeError, ValueError):
        return {}
    raw = payload.get("raw") if isinstance(payload, dict) else None
    return raw if isinstance(raw, dict) else {}


def _safe_position(position: dict[str, Any]) -> dict[str, Any]:
    return {key: position.get(key) for key in ("symbol", "side", "size", "avgPrice", "markPrice", "positionStatus")}


def _bybit_position_snapshot(position: dict[str, Any]) -> ExchangePositionSnapshot | None:
    size = _optional_float(position.get("size"))
    if size is None or size <= 0:
        return None
    side = str(position.get("side") or "").lower()
    margin = _first_float(position.get("positionIM"), position.get("positionBalance"))
    unrealized_pnl = _optional_float(position.get("unrealisedPnl"))
    return ExchangePositionSnapshot(
        symbol=str(position.get("symbol") or "UNKNOWN"),
        side="long" if side == "buy" else "short" if side == "sell" else "unknown",
        size=size,
        leverage=_optional_float(position.get("leverage")),
        entry_price=_optional_float(position.get("avgPrice")),
        mark_price=_optional_float(position.get("markPrice")),
        liquidation_price=_optional_float(position.get("liqPrice")),
        position_value_usdt=_absolute_optional(position.get("positionValue")),
        margin_usdt=margin,
        unrealized_pnl_usdt=unrealized_pnl,
        realized_pnl_usdt=_optional_float(position.get("cumRealisedPnl")),
        roe_pct=position_roe_pct(unrealized_pnl, margin),
        updated_at=_timestamp_text(position.get("updatedTime")),
    )


def _safe_warning(error: Exception) -> str:
    return str(error).replace("\r", " ").replace("\n", " ")[:240]


def _first_float(*values: Any) -> float | None:
    for value in values:
        numeric = _optional_float(value)
        if numeric is not None:
            return numeric
    return None


def _absolute_optional(value: Any) -> float | None:
    numeric = _optional_float(value)
    return abs(numeric) if numeric is not None else None


def _timestamp_text(value: Any) -> str | None:
    timestamp = _optional_float(value)
    if timestamp is None:
        return None
    if timestamp > 10_000_000_000:
        timestamp /= 1000.0
    try:
        return datetime.fromtimestamp(timestamp, tz=timezone.utc).isoformat()
    except (OSError, OverflowError, ValueError):
        return None


def _positive_decimal(value: Any) -> Decimal | None:
    try:
        number = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return number if number > 0 else None


def _optional_float(value: Any) -> float | None:
    if value in (None, ""):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _float(value: Any, default: float) -> float:
    numeric = _optional_float(value)
    return numeric if numeric is not None else default


def _decimal_text(value: Decimal | None) -> str:
    if value is None:
        raise PaperExecutionBlocked("价格或数量必须为正数")
    return format(value.normalize(), "f")
