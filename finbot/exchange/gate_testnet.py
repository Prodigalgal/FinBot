from __future__ import annotations

import hashlib
import hmac
import json
import math
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable
from urllib.parse import urlencode, urlparse

import httpx

from finbot.exchange.account_snapshot import (
    ExchangeAccountSnapshot,
    ExchangePositionSnapshot,
    PnlWindow,
    position_roe_pct,
)
from finbot.exchange.account_activity import (
    AccountActivityQuery,
    activity_matches,
    exchange_source,
)
from finbot.exchange.account_activity_normalizers import (
    gate_account_activity,
    gate_fill_activity,
    gate_order_activity,
)
from finbot.exchange.paper_execution import (
    PaperExecutionBlocked,
    PaperSubmissionResult,
    PreparedPaperOrder,
)
from finbot.instruments.models import stable_id


GATE_TESTNET_API_BASE = "https://api-testnet.gateapi.io/api/v4"
GATE_REAL_API_HOST = "api.gateio.ws"


class GateTestnetApiError(RuntimeError):
    def __init__(self, status_code: int, label: str, message: str):
        self.status_code = status_code
        self.label = label
        self.message = message
        super().__init__(f"Gate TestNet {label or status_code}: {message}")


class GateTestnetClient:
    def __init__(
        self,
        api_key: str,
        api_secret: str,
        *,
        timeout_seconds: float = 20.0,
        proxy: str | None = None,
        transport: httpx.BaseTransport | None = None,
        clock: Callable[[], float] = time.time,
        base_url: str = GATE_TESTNET_API_BASE,
    ):
        normalized_base = base_url.rstrip("/")
        if normalized_base != GATE_TESTNET_API_BASE:
            raise ValueError("Gate 私有执行仅允许固定 TestNet host")
        if urlparse(normalized_base).hostname == GATE_REAL_API_HOST:
            raise ValueError("Gate 真实盘 host 被禁止")
        self.api_key = api_key.strip()
        self._api_secret = api_secret.strip()
        self.base_url = normalized_base
        self.clock = clock
        self._client = httpx.Client(
            timeout=timeout_seconds,
            proxy=proxy,
            transport=transport,
            follow_redirects=False,
            headers={"User-Agent": "FinBot paper execution"},
        )

    def get_contract(self, settle: str, contract: str) -> dict[str, Any]:
        return self._request("GET", f"/futures/{settle}/contracts/{contract}", authenticated=False)

    def get_position(self, settle: str, contract: str) -> dict[str, Any] | None:
        try:
            return self._request("GET", f"/futures/{settle}/positions/{contract}")
        except GateTestnetApiError as exc:
            if exc.label == "POSITION_NOT_FOUND":
                return None
            raise

    def get_account(self, settle: str) -> dict[str, Any]:
        payload = self._request("GET", f"/futures/{settle}/accounts")
        if not isinstance(payload, dict):
            raise GateTestnetApiError(502, "INVALID_RESPONSE", "账户响应不是对象")
        return payload

    def list_positions(
        self,
        settle: str,
        *,
        holding: bool = True,
        page_size: int = 100,
        max_records: int = 500,
    ) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        offset = 0
        limit = max(1, min(page_size, 100))
        while len(rows) < max_records:
            payload = self._request(
                "GET",
                f"/futures/{settle}/positions",
                query={"holding": str(holding).lower(), "limit": limit, "offset": offset},
            )
            if not isinstance(payload, list):
                raise GateTestnetApiError(502, "INVALID_RESPONSE", "持仓响应不是数组")
            page = [row for row in payload if isinstance(row, dict)]
            rows.extend(page)
            if len(payload) < limit:
                break
            offset += limit
        return rows[:max_records]

    def list_account_book(
        self,
        settle: str,
        *,
        from_timestamp: int | None,
        to_timestamp: int,
        page_size: int = 100,
        max_records: int = 1_000,
    ) -> tuple[list[dict[str, Any]], bool]:
        rows: list[dict[str, Any]] = []
        offset = 0
        limit = max(1, min(page_size, 100))
        complete = True
        while len(rows) < max_records:
            payload = self._request(
                "GET",
                f"/futures/{settle}/account_book",
                query={
                    "limit": limit,
                    "offset": offset,
                    "from": from_timestamp,
                    "to": to_timestamp,
                },
            )
            if not isinstance(payload, list):
                raise GateTestnetApiError(502, "INVALID_RESPONSE", "账户流水响应不是数组")
            page = [row for row in payload if isinstance(row, dict)]
            rows.extend(page)
            if len(payload) < limit:
                break
            offset += limit
        if len(rows) >= max_records and len(payload) >= limit:
            complete = False
        return rows[:max_records], complete

    def list_order_history(
        self,
        settle: str,
        *,
        from_timestamp: int | None,
        to_timestamp: int,
        page_size: int = 100,
        max_records: int = 500,
    ) -> tuple[list[dict[str, Any]], bool]:
        return self._list_time_range_rows(
            f"/futures/{settle}/orders_timerange",
            from_timestamp=from_timestamp,
            to_timestamp=to_timestamp,
            page_size=page_size,
            max_records=max_records,
        )

    def list_trade_history(
        self,
        settle: str,
        *,
        from_timestamp: int | None,
        to_timestamp: int,
        page_size: int = 100,
        max_records: int = 500,
    ) -> tuple[list[dict[str, Any]], bool]:
        return self._list_time_range_rows(
            f"/futures/{settle}/my_trades_timerange",
            from_timestamp=from_timestamp,
            to_timestamp=to_timestamp,
            page_size=page_size,
            max_records=max_records,
        )

    def _list_time_range_rows(
        self,
        path: str,
        *,
        from_timestamp: int | None,
        to_timestamp: int,
        page_size: int,
        max_records: int,
    ) -> tuple[list[dict[str, Any]], bool]:
        rows: list[dict[str, Any]] = []
        offset = 0
        limit = max(1, min(page_size, 100))
        complete = True
        last_page_size = 0
        while len(rows) < max_records:
            payload = self._request(
                "GET",
                path,
                query={
                    "from": from_timestamp,
                    "to": to_timestamp,
                    "limit": limit,
                    "offset": offset,
                },
            )
            if not isinstance(payload, list):
                raise GateTestnetApiError(502, "INVALID_RESPONSE", "交易历史响应不是数组")
            page = [row for row in payload if isinstance(row, dict)]
            rows.extend(page)
            last_page_size = len(payload)
            if len(payload) < limit:
                break
            offset += limit
        if len(rows) >= max_records and last_page_size >= limit:
            complete = False
        return rows[:max_records], complete

    def create_order(self, settle: str, order: dict[str, Any]) -> dict[str, Any]:
        return self._request("POST", f"/futures/{settle}/orders", body=order)

    def signing_headers(
        self,
        method: str,
        path: str,
        query_string: str,
        body: str,
        timestamp: str,
    ) -> dict[str, str]:
        body_hash = hashlib.sha512(body.encode("utf-8")).hexdigest()
        canonical_path = urlparse(f"{self.base_url}{path}").path
        signature_payload = f"{method.upper()}\n{canonical_path}\n{query_string}\n{body_hash}\n{timestamp}"
        signature = hmac.new(
            self._api_secret.encode("utf-8"),
            signature_payload.encode("utf-8"),
            hashlib.sha512,
        ).hexdigest()
        return {"KEY": self.api_key, "Timestamp": timestamp, "SIGN": signature}

    def close(self) -> None:
        self._client.close()

    def _request(
        self,
        method: str,
        path: str,
        *,
        query: dict[str, Any] | None = None,
        body: dict[str, Any] | None = None,
        authenticated: bool = True,
    ) -> Any:
        query_string = urlencode([(key, value) for key, value in (query or {}).items() if value is not None])
        body_text = json.dumps(body, ensure_ascii=False, separators=(",", ":")) if body is not None else ""
        timestamp = str(int(self.clock()))
        headers = {"Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if authenticated:
            headers.update(self.signing_headers(method, path, query_string, body_text, timestamp))
            headers["X-Gate-Exptime"] = str(int((self.clock() + 5.0) * 1000))
        url = f"{self.base_url}{path}"
        if query_string:
            url = f"{url}?{query_string}"
        response = self._client.request(method, url, content=body_text or None, headers=headers)
        if response.is_redirect:
            raise GateTestnetApiError(response.status_code, "REDIRECT_BLOCKED", "TestNet 请求禁止重定向")
        payload = _response_json(response)
        if response.status_code >= 400:
            raise GateTestnetApiError(
                response.status_code,
                str(payload.get("label") or "HTTP_ERROR") if isinstance(payload, dict) else "HTTP_ERROR",
                str(payload.get("message") or response.text[:300]) if isinstance(payload, dict) else response.text[:300],
            )
        return payload


@dataclass
class GateTestnetAdapter:
    client: GateTestnetClient | None
    blocker: str | None = None

    adapter_id = "gate_testnet"
    provider = "gate"
    environment = "testnet"
    market_types = frozenset({"linear", "future", "perpetual"})
    display_name = "Gate TestNet"

    def readiness(self) -> dict[str, Any]:
        blockers = [value for value in (self.blocker, "缺少 Gate TestNet API key/secret" if self.client is None else None) if value]
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
        price = _positive_float(instrument.get("last_price")) or _positive_float(decision.get("entry_reference"))
        multiplier = _positive_float(instrument.get("contract_size"))
        if price is None or multiplier is None:
            raise PaperExecutionBlocked("Gate 合约缺少 Mainnet 价格或 quanto_multiplier")
        raw = _instrument_raw(instrument)
        minimum = max(1, int(math.ceil(_positive_float(instrument.get("min_amount")) or 1.0)))
        maximum = int(_positive_float(raw.get("order_size_max")) or 2_147_483_647)
        contracts = min(maximum, int(math.floor(max_notional_usdt / (price * multiplier))))
        if contracts < minimum:
            raise PaperExecutionBlocked("名义限额不足以满足 Gate 最小合约张数")
        action = str(decision["action"]).upper()
        signed_contracts = contracts if action == "BUY" else -contracts
        client_order_id = f"t-finbot-{stable_id(decision['decision_id'], self.adapter_id)[:16]}"
        request = {
            "contract": str(instrument["symbol"]),
            "size": signed_contracts,
            "price": "0",
            "tif": "ioc",
            "reduce_only": False,
            "text": client_order_id,
        }
        return PreparedPaperOrder(
            adapter_id=self.adapter_id,
            provider=self.provider,
            environment=self.environment,
            product_id=instrument.get("product_id"),
            instrument_id=str(instrument["instrument_id"]),
            symbol=str(instrument["symbol"]),
            market_type=str(instrument["market_type"]),
            action=action,
            client_order_id=client_order_id,
            requested_notional=round(contracts * multiplier * price, 8),
            requested_quantity=float(signed_contracts),
            request=request,
            metadata={"reference_price": price, "quanto_multiplier": multiplier, "settle": "usdt"},
        )

    def fetch_account_snapshot(self, *, pnl_window: PnlWindow) -> ExchangeAccountSnapshot:
        if self.client is None:
            raise PaperExecutionBlocked("Gate TestNet client 未配置")
        fetched_at = datetime.now(timezone.utc)
        account = self.client.get_account("usdt")
        warnings: list[str] = []

        try:
            raw_positions = self.client.list_positions("usdt", holding=True)
        except (GateTestnetApiError, httpx.HTTPError) as exc:
            raw_positions = []
            warnings.append(f"持仓读取失败：{_safe_warning(exc)}")

        try:
            account_book, realized_complete = self.client.list_account_book(
                "usdt",
                from_timestamp=int(pnl_window.start_at.timestamp()) if pnl_window.start_at else None,
                to_timestamp=int(pnl_window.end_at.timestamp()),
                max_records=10_000 if pnl_window.is_all else 2_000,
            )
            pnl_rows = [
                row
                for row in account_book
                if str(row.get("type") or "").lower() in {"pnl", "fee", "fund", "refr"}
            ]
            realized_pnl = round(
                sum(_float(row.get("change"), 0.0) for row in pnl_rows),
                8,
            )
            if not realized_complete:
                warnings.append("所选区间账户流水超过查询上限，已实现盈亏为截断结果")
        except (GateTestnetApiError, httpx.HTTPError) as exc:
            account_book = []
            pnl_rows = []
            realized_complete = False
            realized_pnl = None
            warnings.append(f"所选区间已实现盈亏读取失败：{_safe_warning(exc)}")

        positions = tuple(
            position
            for position in (_gate_position_snapshot(row) for row in raw_positions)
            if position is not None
        )
        wallet_balance = _optional_float(account.get("total"))
        unrealized_pnl = _optional_float(account.get("unrealised_pnl"))
        if unrealized_pnl is None:
            unrealized_pnl = round(sum(position.unrealized_pnl_usdt or 0.0 for position in positions), 8)
        total_equity = (
            round(wallet_balance + unrealized_pnl, 8)
            if wallet_balance is not None and unrealized_pnl is not None
            else None
        )
        margin_used = _sum_optional(
            _first_float(
                account.get("position_margin"),
                account.get("position_initial_margin"),
                account.get("cross_initial_margin"),
                account.get("isolated_position_margin"),
            ),
            _optional_float(account.get("order_margin")),
        )
        return ExchangeAccountSnapshot(
            adapter_id=self.adapter_id,
            display_name=self.display_name,
            provider=self.provider,
            environment=self.environment,
            status="partial" if warnings else "ready",
            currency=str(account.get("currency") or "USDT").upper(),
            total_equity_usdt=total_equity,
            wallet_balance_usdt=wallet_balance,
            available_balance_usdt=_optional_float(account.get("available")),
            margin_used_usdt=margin_used,
            maintenance_margin_usdt=_optional_float(account.get("maintenance_margin")),
            unrealized_pnl_usdt=unrealized_pnl,
            realized_pnl_usdt=realized_pnl,
            realized_pnl_complete=realized_complete,
            realized_pnl_record_count=len(pnl_rows),
            positions=positions,
            warnings=tuple(warnings),
            fetched_at=fetched_at.isoformat(),
            metadata={
                "margin_mode": account.get("margin_mode"),
                "dual_mode": bool(account.get("in_dual_mode")),
            },
        )

    def fetch_account_activity(self, *, query: AccountActivityQuery) -> dict[str, Any]:
        if self.client is None:
            raise PaperExecutionBlocked("Gate TestNet client 未配置")
        from_timestamp = int(query.start_at.timestamp()) if query.start_at else None
        to_timestamp = int(query.end_at.timestamp())
        errors: list[str] = []
        orders: list[dict[str, Any]] = []
        fills: list[dict[str, Any]] = []
        ledger: list[dict[str, Any]] = []
        orders_complete = False
        fills_complete = False
        ledger_complete = False
        try:
            orders, orders_complete = self.client.list_order_history(
                "usdt",
                from_timestamp=from_timestamp,
                to_timestamp=to_timestamp,
                max_records=query.fetch_limit,
            )
        except (GateTestnetApiError, httpx.HTTPError) as exc:
            errors.append(f"订单历史读取失败：{_safe_warning(exc)}")
        try:
            fills, fills_complete = self.client.list_trade_history(
                "usdt",
                from_timestamp=from_timestamp,
                to_timestamp=to_timestamp,
                max_records=query.fetch_limit,
            )
        except (GateTestnetApiError, httpx.HTTPError) as exc:
            errors.append(f"成交历史读取失败：{_safe_warning(exc)}")
        try:
            ledger, ledger_complete = self.client.list_account_book(
                "usdt",
                from_timestamp=from_timestamp,
                to_timestamp=to_timestamp,
                max_records=query.fetch_limit,
            )
        except (GateTestnetApiError, httpx.HTTPError) as exc:
            errors.append(f"账户流水读取失败：{_safe_warning(exc)}")
        if len(errors) == 3:
            raise GateTestnetApiError(502, "HISTORY_UNAVAILABLE", "; ".join(errors))
        activities = [gate_order_activity(row) for row in orders]
        activities.extend(gate_fill_activity(row) for row in fills)
        activities.extend(gate_account_activity(row) for row in ledger)
        matched = [activity for activity in activities if activity_matches(activity, query)]
        complete = bool(query.start_at) and orders_complete and fills_complete and ledger_complete and not errors
        message = (
            "Gate TestNet 只读订单、成交与账户流水"
            if query.start_at
            else "全部模式返回 Gate 当前可检索的最近记录，不代表永久完整归档"
        )
        return {
            "sources": [
                exchange_source(
                    adapter_id=self.adapter_id,
                    display_name=self.display_name,
                    status="partial" if errors else "ready",
                    complete=complete,
                    fetched_record_count=len(activities),
                    matched_record_count=len(matched),
                    coverage_start_at=query.start_at,
                    coverage_end_at=query.end_at,
                    message=message,
                    error="; ".join(errors) if errors else None,
                )
            ],
            "activities": matched,
        }

    def submit_order(self, order: PreparedPaperOrder) -> PaperSubmissionResult:
        if self.client is None:
            raise PaperExecutionBlocked("Gate TestNet client 未配置")
        contract = self.client.get_contract("usdt", order.symbol)
        if str(contract.get("status") or "").lower() != "trading":
            raise PaperExecutionBlocked("Gate TestNet 合约不是 trading 状态")
        testnet_multiplier = _positive_float(contract.get("quanto_multiplier"))
        expected_multiplier = _positive_float(order.metadata.get("quanto_multiplier"))
        if testnet_multiplier is None or expected_multiplier is None or not math.isclose(testnet_multiplier, expected_multiplier, rel_tol=1e-9):
            raise PaperExecutionBlocked("Gate TestNet 与 Mainnet 合约乘数不一致")
        position = self.client.get_position("usdt", order.symbol)
        if position is not None and abs(_float(position.get("size"), 0.0)) > 0:
            return PaperSubmissionResult(
                status="skipped_existing_position",
                exchange_order_id=None,
                response={"position": _safe_position(position)},
                error="Gate TestNet 已有该合约未平仓位",
            )
        response = self.client.create_order("usdt", order.request)
        exchange_order_id = str(response.get("id") or "") or None
        status = str(response.get("status") or "submitted").lower()
        finish_as = str(response.get("finish_as") or "").lower()
        normalized_status = "filled" if status == "finished" and finish_as == "filled" else "open" if status == "open" else "submitted"
        return PaperSubmissionResult(
            status=normalized_status,
            exchange_order_id=exchange_order_id,
            response=_safe_order_response(response),
            filled_quantity=(
                abs(_float(response.get("size"), 0.0) - _float(response.get("left"), 0.0))
                if response.get("size") is not None else None
            ),
            average_fill_price=_positive_float(response.get("fill_price")),
        )

    def close(self) -> None:
        if self.client is not None:
            self.client.close()


def _response_json(response: httpx.Response) -> Any:
    try:
        return response.json()
    except ValueError:
        return {"message": response.text[:300]}


def _instrument_raw(instrument: dict[str, Any]) -> dict[str, Any]:
    try:
        payload = json.loads(str(instrument.get("payload_json") or "{}"))
    except (TypeError, ValueError):
        return {}
    raw = payload.get("raw") if isinstance(payload, dict) else None
    return raw if isinstance(raw, dict) else {}


def _safe_position(position: dict[str, Any]) -> dict[str, Any]:
    return {key: position.get(key) for key in ("contract", "size", "leverage", "value", "mark_price")}


def _safe_order_response(response: dict[str, Any]) -> dict[str, Any]:
    allowed = ("id", "contract", "size", "price", "tif", "left", "fill_price", "status", "finish_as", "text", "create_time")
    return {key: response.get(key) for key in allowed if key in response}


def _gate_position_snapshot(position: dict[str, Any]) -> ExchangePositionSnapshot | None:
    signed_size = _float(position.get("size"), 0.0)
    if signed_size == 0:
        return None
    margin = _first_float(position.get("margin"), position.get("initial_margin"))
    unrealized_pnl = _optional_float(position.get("unrealised_pnl"))
    return ExchangePositionSnapshot(
        symbol=str(position.get("contract") or "UNKNOWN"),
        side="long" if signed_size > 0 else "short",
        size=abs(signed_size),
        leverage=_optional_float(position.get("leverage")),
        entry_price=_optional_float(position.get("entry_price")),
        mark_price=_optional_float(position.get("mark_price")),
        liquidation_price=_optional_float(position.get("liq_price")),
        position_value_usdt=_absolute_optional(position.get("value")),
        margin_usdt=margin,
        unrealized_pnl_usdt=unrealized_pnl,
        realized_pnl_usdt=_optional_float(position.get("realised_pnl")),
        roe_pct=position_roe_pct(unrealized_pnl, margin),
        updated_at=_timestamp_text(position.get("update_time")),
    )


def _safe_warning(error: Exception) -> str:
    return str(error).replace("\r", " ").replace("\n", " ")[:240]


def _sum_optional(*values: float | None) -> float | None:
    available = [value for value in values if value is not None]
    return round(sum(available), 8) if available else None


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


def _positive_float(value: Any) -> float | None:
    numeric = _float(value, 0.0)
    return numeric if numeric > 0 else None


def _optional_float(value: Any) -> float | None:
    if value in (None, ""):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _float(value: Any, default: float) -> float:
    try:
        return float(value) if value is not None else default
    except (TypeError, ValueError):
        return default
