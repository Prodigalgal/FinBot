from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import StrEnum
from typing import Any


class OrderSide(StrEnum):
    BUY = "BUY"
    SELL = "SELL"


class OrderStatus(StrEnum):
    PLANNED = "planned"
    SUBMITTED = "submitted"
    PARTIAL = "partial"
    FILLED = "filled"
    CANCELLED = "cancelled"
    REJECTED = "rejected"
    EXPIRED = "expired"
    RECONCILED = "reconciled"


TERMINAL_STATUSES = frozenset(
    {
        OrderStatus.FILLED,
        OrderStatus.CANCELLED,
        OrderStatus.REJECTED,
        OrderStatus.EXPIRED,
        OrderStatus.RECONCILED,
    }
)


@dataclass(frozen=True)
class OmsOrder:
    order_id: str
    client_order_id: str
    venue: str
    environment: str
    symbol: str
    side: OrderSide
    requested_quantity: float
    filled_quantity: float
    average_fill_price: float | None
    status: OrderStatus
    reduce_only: bool
    parent_order_id: str | None
    replaces_order_id: str | None
    exchange_order_id: str | None
    version: int
    created_at: str
    updated_at: str
    metadata: dict[str, Any]

    @property
    def remaining_quantity(self) -> float:
        return max(0.0, self.requested_quantity - self.filled_quantity)

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["side"] = self.side.value
        payload["status"] = self.status.value
        payload["remaining_quantity"] = self.remaining_quantity
        return payload


@dataclass(frozen=True)
class OmsOrderEvent:
    event_id: str
    order_id: str
    sequence: int
    idempotency_key: str
    event_type: str
    from_status: OrderStatus | None
    to_status: OrderStatus
    filled_quantity: float
    average_fill_price: float | None
    reason: str | None
    occurred_at: str
    payload: dict[str, Any]

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["from_status"] = self.from_status.value if self.from_status else None
        payload["to_status"] = self.to_status.value
        return payload


@dataclass(frozen=True)
class OmsExitPlan:
    parent_order_id: str
    oco_group_id: str
    stop_loss_order: OmsOrder
    take_profit_order: OmsOrder

    def to_dict(self) -> dict[str, Any]:
        return {
            "parent_order_id": self.parent_order_id,
            "oco_group_id": self.oco_group_id,
            "stop_loss_order": self.stop_loss_order.to_dict(),
            "take_profit_order": self.take_profit_order.to_dict(),
        }
