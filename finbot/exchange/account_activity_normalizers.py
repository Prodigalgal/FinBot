from __future__ import annotations

from typing import Any

from finbot.exchange.account_activity_record import (
    activity_record,
    first_text,
    number,
    pick,
    side,
    snake_case,
    sum_numbers,
    text,
    timestamp_iso,
)
from finbot.instruments.models import stable_id


def gate_order_activity(order: dict[str, Any]) -> dict[str, Any]:
    signed_size = number(order.get("size")) or 0.0
    left = abs(number(order.get("left")) or 0.0)
    quantity = abs(signed_size)
    filled = max(0.0, quantity - left)
    status = _gate_order_status(order, filled)
    exchange_order_id = text(order.get("id"))
    client_order_id = text(order.get("text"))
    occurred_at = timestamp_iso(order.get("update_time") or order.get("finish_time") or order.get("create_time"))
    return activity_record(
        source_type="exchange",
        source_id="gate_testnet",
        adapter_id="gate_testnet",
        provider="gate",
        environment="testnet",
        stage="order",
        event_type="exchange_order",
        native_id=exchange_order_id,
        occurred_at=occurred_at,
        status=status,
        title=f"{text(order.get('contract')) or '未知合约'} · Gate 订单",
        detail=text(order.get("finish_as")),
        symbol=text(order.get("contract")),
        side="BUY" if signed_size > 0 else "SELL" if signed_size < 0 else None,
        order_type="market" if str(order.get("price") or "") == "0" else "limit",
        quantity=quantity,
        filled_quantity=filled,
        remaining_quantity=left,
        price=number(order.get("price")),
        average_fill_price=number(order.get("fill_price")),
        fee=None,
        client_order_id=client_order_id,
        exchange_order_id=exchange_order_id,
        details=pick(order, "finish_as", "tif", "reduce_only", "is_close", "create_time", "finish_time"),
    )


def gate_fill_activity(fill: dict[str, Any]) -> dict[str, Any]:
    signed_size = number(fill.get("size")) or 0.0
    exchange_order_id = text(fill.get("order_id"))
    fill_id = text(fill.get("trade_id"))
    return activity_record(
        source_type="exchange",
        source_id="gate_testnet",
        adapter_id="gate_testnet",
        provider="gate",
        environment="testnet",
        stage="fill",
        event_type="exchange_fill",
        native_id=fill_id or stable_id("gate-fill", exchange_order_id, fill.get("create_time"), signed_size),
        occurred_at=timestamp_iso(fill.get("create_time")),
        status="filled",
        title=f"{text(fill.get('contract')) or '未知合约'} · Gate 成交",
        detail=text(fill.get("role")),
        symbol=text(fill.get("contract")),
        side="BUY" if signed_size > 0 else "SELL" if signed_size < 0 else None,
        order_type=None,
        quantity=abs(signed_size),
        filled_quantity=abs(signed_size),
        remaining_quantity=None,
        price=number(fill.get("price")),
        average_fill_price=number(fill.get("price")),
        fee=number(fill.get("fee")),
        client_order_id=text(fill.get("text")),
        exchange_order_id=exchange_order_id,
        details=pick(fill, "trade_id", "close_size", "role", "point_fee"),
    )


def gate_account_activity(entry: dict[str, Any]) -> dict[str, Any]:
    entry_type = snake_case(entry.get("type"))
    change = number(entry.get("change"))
    labels = {
        "dnw": "充值或提现",
        "pnl": "已实现盈亏",
        "fee": "交易手续费",
        "refr": "推荐返佣",
        "fund": "资金费用",
        "point_dnw": "点卡变动",
        "point_fee": "点卡手续费",
        "point_refr": "点卡返佣",
        "bonus_offset": "体验金抵扣",
    }
    record_id = text(entry.get("id")) or stable_id(
        "gate-account-book",
        entry.get("time"),
        entry.get("trade_id"),
        entry_type,
        change,
    )
    return activity_record(
        source_type="exchange",
        source_id="gate_testnet",
        adapter_id="gate_testnet",
        provider="gate",
        environment="testnet",
        stage="account",
        event_type="account_ledger",
        native_id=record_id,
        occurred_at=timestamp_iso(entry.get("time")),
        status="recorded",
        title=f"{text(entry.get('contract')) or 'USDT 账户'} · {labels.get(entry_type, entry_type)}",
        detail=text(entry.get("text")),
        symbol=text(entry.get("contract")),
        side=None,
        order_type=None,
        quantity=None,
        filled_quantity=None,
        remaining_quantity=None,
        price=None,
        average_fill_price=None,
        fee=change if entry_type in {"fee", "point_fee"} else None,
        realized_pnl=change if entry_type == "pnl" else None,
        exchange_order_id=None,
        details={
            "change": change,
            "balance": number(entry.get("balance")),
            "ledger_type": entry_type,
            "trade_id": text(entry.get("trade_id")),
        },
    )


def bybit_order_activity(order: dict[str, Any]) -> dict[str, Any]:
    exchange_order_id = text(order.get("orderId"))
    return activity_record(
        source_type="exchange",
        source_id="bybit_demo",
        adapter_id="bybit_demo",
        provider="bybit",
        environment="demo",
        stage="order",
        event_type="exchange_order",
        native_id=exchange_order_id,
        occurred_at=timestamp_iso(order.get("updatedTime") or order.get("createdTime"), milliseconds=True),
        status=_bybit_order_status(order.get("orderStatus")),
        title=f"{text(order.get('symbol')) or '未知合约'} · Bybit 订单",
        detail=first_text(order.get("rejectReason"), order.get("cancelType")),
        symbol=text(order.get("symbol")),
        side=side(order.get("side")),
        order_type=snake_case(order.get("orderType")),
        quantity=number(order.get("qty")),
        filled_quantity=number(order.get("cumExecQty")),
        remaining_quantity=number(order.get("leavesQty")),
        price=number(order.get("price")),
        average_fill_price=number(order.get("avgPrice")),
        fee=number(order.get("cumExecFee")),
        client_order_id=text(order.get("orderLinkId")),
        exchange_order_id=exchange_order_id,
        details=pick(
            order,
            "timeInForce",
            "reduceOnly",
            "cumExecValue",
            "rejectReason",
            "cancelType",
            "createdTime",
            "updatedTime",
        ),
    )


def bybit_fill_activity(fill: dict[str, Any]) -> dict[str, Any]:
    exchange_order_id = text(fill.get("orderId"))
    fill_id = text(fill.get("execId"))
    return activity_record(
        source_type="exchange",
        source_id="bybit_demo",
        adapter_id="bybit_demo",
        provider="bybit",
        environment="demo",
        stage="fill",
        event_type="exchange_fill",
        native_id=fill_id or stable_id("bybit-fill", exchange_order_id, fill.get("execTime")),
        occurred_at=timestamp_iso(fill.get("execTime"), milliseconds=True),
        status="filled",
        title=f"{text(fill.get('symbol')) or '未知合约'} · Bybit 成交",
        detail="maker" if fill.get("isMaker") is True else "taker" if fill.get("isMaker") is False else None,
        symbol=text(fill.get("symbol")),
        side=side(fill.get("side")),
        order_type=snake_case(fill.get("orderType")),
        quantity=number(fill.get("orderQty")),
        filled_quantity=number(fill.get("execQty")),
        remaining_quantity=number(fill.get("leavesQty")),
        price=number(fill.get("orderPrice")),
        average_fill_price=number(fill.get("execPrice")),
        fee=number(fill.get("execFee")),
        client_order_id=text(fill.get("orderLinkId")),
        exchange_order_id=exchange_order_id,
        details=pick(fill, "execId", "execType", "execValue", "feeCurrency", "feeRate", "closedSize", "isMaker"),
    )


def bybit_closed_pnl_activity(entry: dict[str, Any]) -> dict[str, Any]:
    exchange_order_id = text(entry.get("orderId"))
    closed_pnl = number(entry.get("closedPnl"))
    record_id = exchange_order_id or stable_id(
        "bybit-closed-pnl",
        entry.get("symbol"),
        entry.get("updatedTime"),
        closed_pnl,
    )
    return activity_record(
        source_type="exchange",
        source_id="bybit_demo",
        adapter_id="bybit_demo",
        provider="bybit",
        environment="demo",
        stage="account",
        event_type="closed_position_pnl",
        native_id=record_id,
        occurred_at=timestamp_iso(entry.get("updatedTime") or entry.get("createdTime"), milliseconds=True),
        status="recorded",
        title=f"{text(entry.get('symbol')) or '未知合约'} · 平仓盈亏入账",
        detail=None,
        symbol=text(entry.get("symbol")),
        side=side(entry.get("side")),
        order_type=snake_case(entry.get("orderType")),
        quantity=number(entry.get("qty")),
        filled_quantity=number(entry.get("qty")),
        remaining_quantity=0.0,
        price=number(entry.get("avgEntryPrice")),
        average_fill_price=number(entry.get("avgExitPrice")),
        fee=sum_numbers(entry.get("openFee"), entry.get("closeFee")),
        realized_pnl=closed_pnl,
        exchange_order_id=exchange_order_id,
        details=pick(entry, "leverage", "avgEntryPrice", "avgExitPrice", "openFee", "closeFee", "createdTime", "updatedTime"),
    )


def _gate_order_status(order: dict[str, Any], filled_quantity: float) -> str:
    status = snake_case(order.get("status"))
    finish_as = snake_case(order.get("finish_as"))
    if status == "open":
        return "partial" if filled_quantity > 0 else "open"
    if finish_as == "filled":
        return "filled"
    if filled_quantity > 0:
        return "partial"
    if finish_as in {"cancelled", "ioc", "reduce_only", "position_closed", "stp"}:
        return "cancelled"
    if finish_as in {"liquidated", "auto_deleveraged"}:
        return finish_as
    return status or finish_as or "unknown"


def _bybit_order_status(value: Any) -> str:
    normalized = snake_case(value)
    mapping = {
        "new": "open",
        "partially_filled": "partial",
        "partially_filled_canceled": "partial",
        "filled": "filled",
        "cancelled": "cancelled",
        "rejected": "rejected",
        "deactivated": "cancelled",
    }
    return mapping.get(normalized, normalized or "unknown")
