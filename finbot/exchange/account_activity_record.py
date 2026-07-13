from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

from finbot.instruments.models import stable_id


def activity_record(
    *,
    source_type: str,
    source_id: str,
    adapter_id: str | None,
    provider: str | None,
    environment: str | None,
    stage: str,
    event_type: str,
    native_id: str | None,
    occurred_at: str | None,
    status: str,
    title: str,
    detail: str | None,
    symbol: str | None,
    side: str | None,
    order_type: str | None,
    quantity: float | None,
    filled_quantity: float | None,
    remaining_quantity: float | None,
    price: float | None,
    average_fill_price: float | None,
    fee: float | None,
    realized_pnl: float | None = None,
    client_order_id: str | None = None,
    exchange_order_id: str | None = None,
    oms_order_id: str | None = None,
    paper_execution_id: str | None = None,
    decision_id: str | None = None,
    loop_run_id: str | None = None,
    details: dict[str, Any] | None = None,
) -> dict[str, Any]:
    record_id = native_id or stable_id(source_id, stage, event_type, occurred_at, title)
    return {
        "activity_id": stable_id("account-activity", source_type, source_id, stage, record_id),
        "source_type": source_type,
        "source_id": source_id,
        "adapter_id": adapter_id,
        "provider": provider,
        "environment": environment,
        "stage": stage,
        "event_type": event_type,
        "occurred_at": occurred_at or datetime.min.replace(tzinfo=timezone.utc).isoformat(),
        "status": snake_case(status),
        "title": title,
        "detail": detail,
        "symbol": symbol,
        "side": side,
        "order_type": order_type,
        "quantity": quantity,
        "filled_quantity": filled_quantity,
        "remaining_quantity": remaining_quantity,
        "price": price,
        "average_fill_price": average_fill_price,
        "fee": fee,
        "realized_pnl": realized_pnl,
        "client_order_id": client_order_id,
        "exchange_order_id": exchange_order_id,
        "oms_order_id": oms_order_id,
        "paper_execution_id": paper_execution_id,
        "decision_id": decision_id,
        "loop_run_id": loop_run_id,
        "details": details or {},
    }


def timestamp_iso(value: Any, *, milliseconds: bool = False) -> str | None:
    if value is None or value == "":
        return None
    numeric = number(value)
    numeric_text = isinstance(value, str) and re.fullmatch(r"\d+(?:\.\d+)?", value.strip()) is not None
    if numeric is not None and (not isinstance(value, str) or numeric_text):
        timestamp = numeric / 1000.0 if milliseconds or numeric > 10_000_000_000 else numeric
        try:
            return datetime.fromtimestamp(timestamp, tz=timezone.utc).isoformat()
        except (OSError, OverflowError, ValueError):
            return None
    parsed = parse_datetime(value)
    return parsed.isoformat() if parsed else None


def parse_datetime(value: Any) -> datetime | None:
    if not value:
        return None
    normalized = str(value).strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError:
        return None
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)


def normalized_symbol(value: Any) -> str:
    return re.sub(r"[^A-Z0-9]", "", str(value or "").upper())


def snake_case(value: Any) -> str:
    raw = str(value or "").strip()
    if not raw:
        return "unknown"
    return re.sub(r"(?<!^)(?=[A-Z])", "_", raw).replace("-", "_").replace(" ", "_").lower()


def side(value: Any) -> str | None:
    normalized = str(value or "").strip().upper()
    return "BUY" if normalized in {"BUY", "LONG"} else "SELL" if normalized in {"SELL", "SHORT"} else None


def number(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def sum_numbers(*values: Any) -> float | None:
    available = [numeric for value in values if (numeric := number(value)) is not None]
    return round(sum(available), 8) if available else None


def text(value: Any) -> str | None:
    normalized = str(value).strip() if value is not None else ""
    return normalized or None


def first_text(*values: Any) -> str | None:
    return next((normalized for value in values if (normalized := text(value))), None)


def pick(payload: Any, *keys: str) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    return {
        key: payload[key]
        for key in keys
        if key in payload and payload[key] is not None and payload[key] != ""
    }
