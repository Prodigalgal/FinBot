from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable


PNL_RANGE_PRESETS = {
    "24h": timedelta(hours=24),
    "7d": timedelta(days=7),
    "30d": timedelta(days=30),
}
MAX_CUSTOM_RANGE_DAYS = 366


@dataclass(frozen=True)
class PnlWindow:
    mode: str
    start_at: datetime | None
    end_at: datetime

    @property
    def is_all(self) -> bool:
        return self.mode == "all"

    def to_dict(self) -> dict[str, Any]:
        return {
            "mode": self.mode,
            "start_at": self.start_at.isoformat() if self.start_at else None,
            "end_at": self.end_at.isoformat(),
        }


@dataclass(frozen=True)
class ExchangePositionSnapshot:
    symbol: str
    side: str
    size: float
    leverage: float | None
    entry_price: float | None
    mark_price: float | None
    liquidation_price: float | None
    position_value_usdt: float | None
    margin_usdt: float | None
    unrealized_pnl_usdt: float | None
    realized_pnl_usdt: float | None
    roe_pct: float | None
    updated_at: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class ExchangeAccountSnapshot:
    adapter_id: str
    display_name: str
    provider: str
    environment: str
    status: str
    currency: str = "USDT"
    total_equity_usdt: float | None = None
    wallet_balance_usdt: float | None = None
    available_balance_usdt: float | None = None
    margin_used_usdt: float | None = None
    maintenance_margin_usdt: float | None = None
    unrealized_pnl_usdt: float | None = None
    realized_pnl_usdt: float | None = None
    realized_pnl_complete: bool = True
    realized_pnl_record_count: int = 0
    positions: tuple[ExchangePositionSnapshot, ...] = ()
    warnings: tuple[str, ...] = ()
    error: str | None = None
    fetched_at: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["positions"] = [position.to_dict() for position in self.positions]
        payload["position_count"] = len(self.positions)
        return payload


def blocked_account_snapshot(
    *,
    adapter_id: str,
    display_name: str,
    provider: str,
    environment: str,
    status: str,
    error: str,
    fetched_at: str | None = None,
) -> ExchangeAccountSnapshot:
    return ExchangeAccountSnapshot(
        adapter_id=adapter_id,
        display_name=display_name,
        provider=provider,
        environment=environment,
        status=status,
        error=error,
        fetched_at=fetched_at or utc_now(),
    )


def account_snapshot_payload(
    snapshots: Iterable[ExchangeAccountSnapshot],
    *,
    pnl_window: PnlWindow,
    generated_at: str | None = None,
) -> dict[str, Any]:
    accounts = list(snapshots)
    readable = [account for account in accounts if account.status in {"ready", "partial"}]
    unavailable = [account for account in accounts if account.status in {"blocked", "failed"}]
    enabled = [account for account in accounts if account.status != "disabled"]
    if readable and (unavailable or any(account.status == "partial" for account in readable)):
        status = "partial"
    elif readable:
        status = "ok"
    elif enabled:
        status = "blocked"
    else:
        status = "disabled"

    totals = {
        "total_equity_usdt": _sum_available(account.total_equity_usdt for account in readable),
        "wallet_balance_usdt": _sum_available(account.wallet_balance_usdt for account in readable),
        "available_balance_usdt": _sum_available(account.available_balance_usdt for account in readable),
        "margin_used_usdt": _sum_available(account.margin_used_usdt for account in readable),
        "maintenance_margin_usdt": _sum_available(account.maintenance_margin_usdt for account in readable),
        "unrealized_pnl_usdt": _sum_available(account.unrealized_pnl_usdt for account in readable),
        "realized_pnl_usdt": _sum_available(account.realized_pnl_usdt for account in readable),
        "total_pnl_usdt": (
            _sum_available(
                _sum_if_complete(account.realized_pnl_usdt, account.unrealized_pnl_usdt)
                for account in readable
            )
            if pnl_window.is_all
            else None
        ),
        "position_count": sum(len(account.positions) for account in readable),
        "account_count": len(readable),
        "realized_pnl_complete": bool(readable) and all(
            account.realized_pnl_complete and account.realized_pnl_usdt is not None
            for account in readable
        ),
    }
    if not totals["realized_pnl_complete"]:
        totals["realized_pnl_usdt"] = None
        totals["total_pnl_usdt"] = None
    return {
        "status": status,
        "generated_at": generated_at or utc_now(),
        "currency_basis": "USDT-equivalent",
        "pnl_window": pnl_window.to_dict(),
        "totals": totals,
        "accounts": [account.to_dict() for account in accounts],
        "policy": {
            "read_only": True,
            "simulated_accounts_only": True,
            "mainnet_private_api_allowed": False,
            "write_requests_allowed": False,
        },
    }


def resolve_pnl_window(
    mode: str,
    *,
    start_at: str | None = None,
    end_at: str | None = None,
    now: datetime | None = None,
) -> PnlWindow:
    normalized_mode = str(mode or "all").strip().lower()
    observed_at = _as_utc(now or datetime.now(timezone.utc))
    if normalized_mode == "all":
        return PnlWindow(mode="all", start_at=None, end_at=observed_at)
    if normalized_mode in PNL_RANGE_PRESETS:
        return PnlWindow(
            mode=normalized_mode,
            start_at=observed_at - PNL_RANGE_PRESETS[normalized_mode],
            end_at=observed_at,
        )
    if normalized_mode != "custom":
        raise ValueError("盈亏区间仅支持 all、24h、7d、30d 或 custom")
    if not start_at or not end_at:
        raise ValueError("自定义盈亏区间必须同时提供 start_at 和 end_at")
    start = _parse_datetime(start_at, "start_at")
    end = _parse_datetime(end_at, "end_at")
    if start >= end:
        raise ValueError("自定义盈亏区间的 start_at 必须早于 end_at")
    if end > observed_at + timedelta(minutes=5):
        raise ValueError("自定义盈亏区间的 end_at 不能晚于当前时间")
    if end - start > timedelta(days=MAX_CUSTOM_RANGE_DAYS):
        raise ValueError(f"单次自定义盈亏区间不能超过 {MAX_CUSTOM_RANGE_DAYS} 天")
    return PnlWindow(mode="custom", start_at=start, end_at=end)


def position_roe_pct(unrealized_pnl: float | None, margin: float | None) -> float | None:
    if unrealized_pnl is None or margin is None or margin <= 0:
        return None
    return round(unrealized_pnl / margin * 100.0, 6)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _parse_datetime(value: str, field_name: str) -> datetime:
    normalized = str(value).strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(normalized)
    except ValueError as exc:
        raise ValueError(f"{field_name} 必须是 ISO-8601 时间") from exc
    return _as_utc(parsed)


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _sum_if_complete(left: float | None, right: float | None) -> float | None:
    if left is None or right is None:
        return None
    return round(left + right, 8)


def _sum_available(values: Iterable[float | int | None]) -> float | int | None:
    available = [value for value in values if value is not None]
    if not available:
        return None
    total = sum(available)
    return round(float(total), 8) if any(isinstance(value, float) for value in available) else int(total)
