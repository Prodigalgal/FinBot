from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Iterable

from finbot.exchange.account_activity_record import (
    activity_record as _activity,
    first_text as _first_text,
    normalized_symbol as _normalized_symbol,
    number as _number,
    parse_datetime as _parse_datetime,
    pick as _pick,
    snake_case as _snake_case,
    text as _text,
    timestamp_iso as _timestamp_iso,
)
from finbot.exchange.account_snapshot import resolve_pnl_window, utc_now
from finbot.execution import OmsRepository
from finbot.storage.sqlite_store import SQLiteStore


SUPPORTED_ACTIVITY_ADAPTERS = frozenset({"all", "local", "gate_testnet", "bybit_demo"})
SUPPORTED_ACTIVITY_STAGES = frozenset({"all", "decision", "proposal", "execution", "order", "fill", "account"})
MAX_ACTIVITY_PAGE_SIZE = 200
MAX_ACTIVITY_OFFSET = 500


@dataclass(frozen=True)
class AccountActivityQuery:
    mode: str
    start_at: datetime | None
    end_at: datetime
    adapter_id: str = "all"
    stage: str = "all"
    status: str = "all"
    symbol: str | None = None
    offset: int = 0
    limit: int = 100

    @property
    def fetch_limit(self) -> int:
        return min(500, max(100, (self.offset + self.limit) * 3))

    @property
    def selected_exchange_adapters(self) -> tuple[str, ...]:
        if self.adapter_id == "local":
            return ()
        if self.adapter_id in {"gate_testnet", "bybit_demo"}:
            return (self.adapter_id,)
        return ("gate_testnet", "bybit_demo")

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "start_at": self.start_at.isoformat() if self.start_at else None,
            "end_at": self.end_at.isoformat(),
            "adapter_id": self.adapter_id,
            "stage": self.stage,
            "status": self.status,
            "symbol": self.symbol,
            "offset": self.offset,
            "limit": self.limit,
        }


def resolve_activity_query(
    *,
    range_mode: str = "7d",
    start_at: str | None = None,
    end_at: str | None = None,
    adapter_id: str = "all",
    stage: str = "all",
    status: str = "all",
    symbol: str | None = None,
    offset: int = 0,
    limit: int = 100,
    now: datetime | None = None,
) -> AccountActivityQuery:
    normalized_adapter = str(adapter_id or "all").strip().lower()
    normalized_stage = str(stage or "all").strip().lower()
    normalized_status = _snake_case(status or "all")
    if normalized_adapter not in SUPPORTED_ACTIVITY_ADAPTERS:
        raise ValueError("交易历史来源仅支持 all、local、gate_testnet 或 bybit_demo")
    if normalized_stage not in SUPPORTED_ACTIVITY_STAGES:
        raise ValueError("交易历史阶段仅支持 decision、proposal、execution、order、fill 或 account")
    if offset < 0 or offset > MAX_ACTIVITY_OFFSET:
        raise ValueError(f"offset 必须在 0 到 {MAX_ACTIVITY_OFFSET} 之间")
    if limit < 1 or limit > MAX_ACTIVITY_PAGE_SIZE:
        raise ValueError(f"limit 必须在 1 到 {MAX_ACTIVITY_PAGE_SIZE} 之间")
    window = resolve_pnl_window(range_mode, start_at=start_at, end_at=end_at, now=now)
    normalized_symbol = _normalized_symbol(symbol) if symbol else None
    return AccountActivityQuery(
        mode=window.mode,
        start_at=window.start_at,
        end_at=window.end_at,
        adapter_id=normalized_adapter,
        stage=normalized_stage,
        status=normalized_status,
        symbol=normalized_symbol,
        offset=offset,
        limit=limit,
    )


def local_account_activity_payload(store: SQLiteStore, query: AccountActivityQuery) -> dict[str, Any]:
    activities: list[dict[str, Any]] = []
    truncation_checks: list[bool] = []

    decisions = store.list_ai_trade_decisions(limit=query.fetch_limit)
    truncation_checks.append(len(decisions) >= query.fetch_limit)
    activities.extend(_decision_activity(row) for row in decisions)

    proposals = store.list_paper_order_proposals(limit=query.fetch_limit)
    truncation_checks.append(len(proposals) >= query.fetch_limit)
    activities.extend(_proposal_activity(row) for row in proposals)

    executions = store.list_paper_executions(limit=query.fetch_limit)
    truncation_checks.append(len(executions) >= query.fetch_limit)
    activities.extend(_paper_execution_activity(row) for row in executions)

    oms_repository = OmsRepository(store)
    orders = oms_repository.list_orders(limit=query.fetch_limit)
    truncation_checks.append(len(orders) >= query.fetch_limit)
    for order in orders:
        for event in oms_repository.list_events(order.order_id):
            activities.append(_oms_activity(order.to_dict(), event.to_dict()))

    matched = [activity for activity in activities if activity_matches(activity, query)]
    complete = not any(truncation_checks)
    return {
        "sources": [
            {
                "source_id": "local_audit",
                "source_type": "local",
                "display_name": "FinBot 本地审计",
                "adapter_id": "local",
                "status": "ready",
                "complete": complete,
                "truncated": not complete,
                "fetched_record_count": len(activities),
                "matched_record_count": len(matched),
                "coverage_start_at": query.start_at.isoformat() if query.start_at else None,
                "coverage_end_at": query.end_at.isoformat(),
                "message": "本地记录包含 AI 决策、建议草案、模拟执行与 OMS 状态事件",
                "error": None,
            }
        ],
        "activities": matched,
    }


def merge_account_activity_payload(
    *,
    query: AccountActivityQuery,
    local_payload: dict[str, Any],
    exchange_payload: dict[str, Any],
) -> dict[str, Any]:
    sources = [*local_payload.get("sources", []), *exchange_payload.get("sources", [])]
    activities = [*local_payload.get("activities", []), *exchange_payload.get("activities", [])]
    correlated = _correlate_exchange_records(activities)
    matched = [activity for activity in correlated if activity_matches(activity, query)]
    matched.sort(key=lambda item: (str(item.get("occurred_at") or ""), str(item.get("activity_id") or "")), reverse=True)
    total_matches = len(matched)
    page_items = matched[query.offset : query.offset + query.limit]
    source_statuses = {str(source.get("status") or "unknown") for source in sources}
    available = any(status in {"ready", "partial"} for status in source_statuses)
    unavailable = any(status in {"blocked", "failed"} for status in source_statuses)
    status = "partial" if available and unavailable else "ok" if available else "blocked"
    counts_by_stage = _count_by(matched, "stage")
    counts_by_status = _count_by(matched, "status")
    return {
        "status": status,
        "generated_at": utc_now(),
        "query": query.to_dict(),
        "summary": {
            "returned_count": len(page_items),
            "matched_count": total_matches,
            "decision_count": counts_by_stage.get("decision", 0),
            "proposal_count": counts_by_stage.get("proposal", 0),
            "local_execution_count": counts_by_stage.get("execution", 0),
            "exchange_order_count": sum(
                1 for item in matched if item.get("source_type") == "exchange" and item.get("stage") == "order"
            ),
            "exchange_fill_count": sum(
                1 for item in matched if item.get("source_type") == "exchange" and item.get("stage") == "fill"
            ),
            "account_change_count": counts_by_stage.get("account", 0),
            "counts_by_stage": counts_by_stage,
            "counts_by_status": counts_by_status,
        },
        "sources": sources,
        "activities": page_items,
        "page": {
            "offset": query.offset,
            "limit": query.limit,
            "returned": len(page_items),
            "has_more": total_matches > query.offset + len(page_items),
        },
        "policy": {
            "read_only": True,
            "simulated_accounts_only": True,
            "mainnet_private_api_allowed": False,
            "write_requests_allowed": False,
            "local_status_is_not_exchange_confirmation": True,
        },
    }


def activity_matches(activity: dict[str, Any], query: AccountActivityQuery) -> bool:
    if query.adapter_id == "local":
        if activity.get("source_type") != "local":
            return False
    elif query.adapter_id != "all" and activity.get("adapter_id") != query.adapter_id:
        return False
    if query.stage != "all" and activity.get("stage") != query.stage:
        return False
    if query.status != "all" and _snake_case(activity.get("status")) != query.status:
        return False
    if query.symbol and _normalized_symbol(activity.get("symbol")) != query.symbol:
        return False
    observed_at = _parse_datetime(activity.get("occurred_at"))
    if observed_at is None or observed_at > query.end_at:
        return False
    return query.start_at is None or observed_at >= query.start_at


def history_windows(
    start_at: datetime | None,
    end_at: datetime,
    *,
    max_days: int = 7,
) -> tuple[tuple[datetime | None, datetime], ...]:
    if start_at is None:
        return ((None, end_at),)
    windows: list[tuple[datetime | None, datetime]] = []
    cursor = end_at
    span = timedelta(days=max(1, max_days))
    while cursor > start_at:
        window_start = max(start_at, cursor - span)
        windows.append((window_start, cursor))
        cursor = window_start
    return tuple(windows)




def exchange_source(
    *,
    adapter_id: str,
    display_name: str,
    status: str,
    complete: bool,
    fetched_record_count: int,
    matched_record_count: int,
    coverage_start_at: datetime | None,
    coverage_end_at: datetime,
    message: str,
    error: str | None = None,
) -> dict[str, Any]:
    return {
        "source_id": adapter_id,
        "source_type": "exchange",
        "display_name": display_name,
        "adapter_id": adapter_id,
        "status": status,
        "complete": complete,
        "truncated": not complete,
        "fetched_record_count": fetched_record_count,
        "matched_record_count": matched_record_count,
        "coverage_start_at": coverage_start_at.isoformat() if coverage_start_at else None,
        "coverage_end_at": coverage_end_at.isoformat(),
        "message": message,
        "error": error,
    }


def _decision_activity(row: Any) -> dict[str, Any]:
    symbol = _text(row["symbol"] or row["normalized_symbol"])
    action = _text(row["action"])
    return _activity(
        source_type="local",
        source_id="local_audit",
        adapter_id=None,
        provider=_text(row["provider"]),
        environment="analysis",
        stage="decision",
        event_type="ai_trade_decision",
        native_id=_text(row["decision_id"]),
        occurred_at=_timestamp_iso(row["created_at"]),
        status=_snake_case(row["status"]),
        title=f"{symbol or '未知合约'} · AI 最终决策 {action or '-'}",
        detail=f"置信度 {float(row['confidence']):.2%}",
        symbol=symbol,
        side=action if action in {"BUY", "SELL"} else None,
        order_type=None,
        quantity=None,
        filled_quantity=None,
        remaining_quantity=None,
        price=_number(row["entry_reference"]),
        average_fill_price=None,
        fee=None,
        decision_id=_text(row["decision_id"]),
        loop_run_id=_text(row["loop_run_id"]),
        details={
            "confidence": _number(row["confidence"]),
            "score": _number(row["score"]),
            "target_price": _number(row["target_price"]),
            "invalidation_price": _number(row["invalidation_price"]),
            "risk_warnings": _loads(row["risk_warnings_json"], []),
        },
    )


def _proposal_activity(row: Any) -> dict[str, Any]:
    symbol = _text(row["symbol"])
    action = _text(row["action"])
    return _activity(
        source_type="local",
        source_id="local_audit",
        adapter_id=None,
        provider=_text(row["provider"]),
        environment="paper",
        stage="proposal",
        event_type="paper_order_proposal",
        native_id=_text(row["proposal_id"]),
        occurred_at=_timestamp_iso(row["created_at"]),
        status=_snake_case(row["status"]),
        title=f"{symbol or '未知合约'} · 建议草案 {action or '-'}",
        detail="仅为本地建议，未代表已经下单",
        symbol=symbol,
        side=action if action in {"BUY", "SELL"} else None,
        order_type=None,
        quantity=None,
        filled_quantity=None,
        remaining_quantity=None,
        price=None,
        average_fill_price=None,
        fee=None,
        details={
            "report_id": _text(row["report_id"]),
            "advice_id": _text(row["advice_id"]),
            "market_type": _text(row["market_type"]),
            "execution_mode": _text(row["execution_mode"]),
        },
    )


def _paper_execution_activity(row: Any) -> dict[str, Any]:
    adapter_id = _text(row["adapter_id"])
    display_name = "Gate TestNet" if adapter_id == "gate_testnet" else "Bybit Demo" if adapter_id == "bybit_demo" else adapter_id
    request = _loads(row["request_json"], {})
    response = _loads(row["response_json"], {})
    return _activity(
        source_type="local",
        source_id="local_audit",
        adapter_id=adapter_id,
        provider=_text(row["provider"]),
        environment=_text(row["environment"]),
        stage="execution",
        event_type="paper_execution",
        native_id=_text(row["execution_id"]),
        occurred_at=_timestamp_iso(row["updated_at"] or row["created_at"]),
        status=_snake_case(row["status"]),
        title=f"{_text(row['symbol']) or '未知合约'} · {display_name} 模拟执行",
        detail=_text(row["error"]),
        symbol=_text(row["symbol"]),
        side=_text(row["action"]),
        order_type=_first_text(request.get("orderType"), "market" if str(request.get("price") or "") == "0" else None),
        quantity=_number(row["requested_quantity"]),
        filled_quantity=_number(row["filled_quantity"]),
        remaining_quantity=None,
        price=_number(request.get("price")),
        average_fill_price=_number(row["average_fill_price"]),
        fee=None,
        client_order_id=_text(row["client_order_id"]),
        exchange_order_id=_text(row["exchange_order_id"]),
        paper_execution_id=_text(row["execution_id"]),
        decision_id=_text(row["decision_id"]),
        loop_run_id=_text(row["loop_run_id"]),
        details={
            "requested_notional": _number(row["requested_notional"]),
            "error": _text(row["error"]),
            "request": _pick(request, "contract", "symbol", "side", "size", "qty", "price", "tif", "timeInForce", "orderType", "reduce_only", "positionIdx"),
            "response": _pick(response, "id", "orderId", "contract", "symbol", "size", "qty", "left", "fill_price", "status", "finish_as"),
        },
    )


def _oms_activity(order: dict[str, Any], event: dict[str, Any]) -> dict[str, Any]:
    adapter_id = _adapter_id(order.get("venue"), order.get("environment"))
    metadata = order.get("metadata") if isinstance(order.get("metadata"), dict) else {}
    return _activity(
        source_type="local",
        source_id="local_audit",
        adapter_id=adapter_id,
        provider=_text(order.get("venue")),
        environment=_text(order.get("environment")),
        stage="order",
        event_type="oms_order_event",
        native_id=_text(event.get("event_id")),
        occurred_at=_timestamp_iso(event.get("occurred_at")),
        status=_snake_case(event.get("to_status")),
        title=f"{_text(order.get('symbol')) or '未知合约'} · OMS {event.get('event_type') or '状态变化'}",
        detail=_text(event.get("reason")),
        symbol=_text(order.get("symbol")),
        side=_text(order.get("side")),
        order_type=None,
        quantity=_number(order.get("requested_quantity")),
        filled_quantity=_number(event.get("filled_quantity")),
        remaining_quantity=_number(order.get("remaining_quantity")),
        price=None,
        average_fill_price=_number(event.get("average_fill_price")),
        fee=None,
        client_order_id=_text(order.get("client_order_id")),
        exchange_order_id=_text(order.get("exchange_order_id")),
        oms_order_id=_text(order.get("order_id")),
        paper_execution_id=_text(metadata.get("paper_execution_id")),
        decision_id=_text(metadata.get("decision_id")),
        loop_run_id=_text(metadata.get("loop_run_id")),
        details={
            "from_status": event.get("from_status"),
            "to_status": event.get("to_status"),
            "sequence": event.get("sequence"),
            "version": order.get("version"),
            "reduce_only": order.get("reduce_only"),
        },
    )


def _correlate_exchange_records(activities: list[dict[str, Any]]) -> list[dict[str, Any]]:
    links: dict[tuple[str, str], dict[str, str]] = {}
    link_fields = ("oms_order_id", "paper_execution_id", "decision_id", "loop_run_id")
    for activity in activities:
        if activity.get("source_type") != "local":
            continue
        link = {field: str(activity[field]) for field in link_fields if activity.get(field)}
        for field in ("client_order_id", "exchange_order_id"):
            value = activity.get(field)
            if value and link:
                links[(field, str(value))] = link
    correlated: list[dict[str, Any]] = []
    for activity in activities:
        if activity.get("source_type") != "exchange":
            correlated.append(activity)
            continue
        link = None
        for field in ("exchange_order_id", "client_order_id"):
            value = activity.get(field)
            if value and (field, str(value)) in links:
                link = links[(field, str(value))]
                break
        correlated.append({**activity, **(link or {})})
    return correlated


def _count_by(items: Iterable[dict[str, Any]], field: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for item in items:
        key = str(item.get(field) or "unknown")
        counts[key] = counts.get(key, 0) + 1
    return counts


def _adapter_id(venue: Any, environment: Any) -> str | None:
    normalized_venue = str(venue or "").strip().lower()
    normalized_environment = str(environment or "").strip().lower()
    if normalized_venue == "gate" and normalized_environment == "testnet":
        return "gate_testnet"
    if normalized_venue == "bybit" and normalized_environment == "demo":
        return "bybit_demo"
    return None


def _loads(value: Any, fallback: Any) -> Any:
    try:
        return json.loads(str(value or ""))
    except (TypeError, ValueError):
        return fallback
