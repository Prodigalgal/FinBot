from __future__ import annotations

import hashlib
from dataclasses import replace
from datetime import datetime, timezone
from typing import Any

from finbot.execution.models import OmsExitPlan, OmsOrder, OmsOrderEvent, OrderSide, OrderStatus, TERMINAL_STATUSES
from finbot.execution.repository import OmsRepository


ALLOWED_TRANSITIONS: dict[OrderStatus, frozenset[OrderStatus]] = {
    OrderStatus.PLANNED: frozenset({OrderStatus.SUBMITTED, OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.EXPIRED}),
    OrderStatus.SUBMITTED: frozenset({OrderStatus.PARTIAL, OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED, OrderStatus.EXPIRED}),
    OrderStatus.PARTIAL: frozenset({OrderStatus.PARTIAL, OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.EXPIRED}),
    OrderStatus.FILLED: frozenset({OrderStatus.RECONCILED}),
    OrderStatus.CANCELLED: frozenset({OrderStatus.RECONCILED}),
    OrderStatus.REJECTED: frozenset({OrderStatus.RECONCILED}),
    OrderStatus.EXPIRED: frozenset({OrderStatus.RECONCILED}),
    OrderStatus.RECONCILED: frozenset(),
}


class OmsService:
    def __init__(self, repository: OmsRepository):
        self.repository = repository

    def plan_order(
        self,
        *,
        idempotency_key: str,
        client_order_id: str,
        venue: str,
        environment: str,
        symbol: str,
        side: OrderSide | str,
        requested_quantity: float,
        reduce_only: bool = False,
        parent_order_id: str | None = None,
        replaces_order_id: str | None = None,
        metadata: dict[str, Any] | None = None,
        occurred_at: datetime | None = None,
    ) -> OmsOrder:
        _validate_idempotency_key(idempotency_key)
        if environment.lower() not in {"paper", "testnet", "demo"}:
            raise ValueError("OMS 只允许 paper/testnet/demo 环境")
        if requested_quantity <= 0:
            raise ValueError("requested_quantity 必须大于 0")
        timestamp = _timestamp(occurred_at)
        order_id = _id("oms-order", client_order_id)
        order = OmsOrder(
            order_id=order_id,
            client_order_id=client_order_id,
            venue=venue.lower(),
            environment=environment.lower(),
            symbol=symbol,
            side=OrderSide(side),
            requested_quantity=float(requested_quantity),
            filled_quantity=0.0,
            average_fill_price=None,
            status=OrderStatus.PLANNED,
            reduce_only=bool(reduce_only),
            parent_order_id=parent_order_id,
            replaces_order_id=replaces_order_id,
            exchange_order_id=None,
            version=1,
            created_at=timestamp,
            updated_at=timestamp,
            metadata=dict(metadata or {}),
        )
        event = _event(order, idempotency_key, "planned", None, timestamp=timestamp)
        return self.repository.create(order, event)

    def transition(
        self,
        order_id: str,
        *,
        to_status: OrderStatus | str,
        idempotency_key: str,
        filled_quantity: float | None = None,
        average_fill_price: float | None = None,
        exchange_order_id: str | None = None,
        reason: str | None = None,
        payload: dict[str, Any] | None = None,
        occurred_at: datetime | None = None,
    ) -> OmsOrder:
        _validate_idempotency_key(idempotency_key)
        duplicate = self.repository.get_by_idempotency_key(idempotency_key)
        if duplicate:
            return duplicate
        previous = self.repository.get(order_id)
        if previous is None:
            raise KeyError(f"OMS order {order_id} not found")
        target = OrderStatus(to_status)
        if target not in ALLOWED_TRANSITIONS[previous.status]:
            raise ValueError(f"非法 OMS 状态转换: {previous.status.value} -> {target.value}")
        next_filled = previous.filled_quantity if filled_quantity is None else float(filled_quantity)
        if next_filled < previous.filled_quantity or next_filled > previous.requested_quantity:
            raise ValueError("filled_quantity 必须单调递增且不超过 requested_quantity")
        if target is OrderStatus.PARTIAL and not 0 < next_filled < previous.requested_quantity:
            raise ValueError("partial 状态要求 0 < filled_quantity < requested_quantity")
        if target is OrderStatus.FILLED:
            next_filled = previous.requested_quantity
        next_average = previous.average_fill_price if average_fill_price is None else float(average_fill_price)
        if next_filled > 0 and (next_average is None or next_average <= 0):
            raise ValueError("有成交数量时必须提供有效 average_fill_price")
        timestamp = _timestamp(occurred_at)
        current = replace(
            previous,
            filled_quantity=next_filled,
            average_fill_price=next_average,
            status=target,
            exchange_order_id=exchange_order_id or previous.exchange_order_id,
            version=previous.version + 1,
            updated_at=timestamp,
        )
        event = _event(
            current,
            idempotency_key,
            target.value,
            previous.status,
            reason=reason,
            payload=payload,
            timestamp=timestamp,
        )
        return self.repository.transition(previous=previous, current=current, event=event)

    def cancel(self, order_id: str, *, idempotency_key: str, reason: str | None = None) -> OmsOrder:
        return self.transition(
            order_id,
            to_status=OrderStatus.CANCELLED,
            idempotency_key=idempotency_key,
            reason=reason,
        )

    def reconcile(
        self,
        order_id: str,
        *,
        idempotency_key: str,
        exchange_payload: dict[str, Any],
    ) -> OmsOrder:
        return self.transition(
            order_id,
            to_status=OrderStatus.RECONCILED,
            idempotency_key=idempotency_key,
            payload={"exchange_snapshot": exchange_payload},
        )

    def replace_order(
        self,
        order_id: str,
        *,
        idempotency_key: str,
        replacement_client_order_id: str,
        requested_quantity: float,
    ) -> OmsOrder:
        duplicate = self.repository.get_by_idempotency_key(idempotency_key)
        if duplicate and duplicate.replaces_order_id == order_id:
            return duplicate
        previous = self.repository.get(order_id)
        if previous is None:
            raise KeyError(f"OMS order {order_id} not found")
        self.cancel(order_id, idempotency_key=f"{idempotency_key}:cancel", reason="cancel_replace")
        return self.plan_order(
            idempotency_key=idempotency_key,
            client_order_id=replacement_client_order_id,
            venue=previous.venue,
            environment=previous.environment,
            symbol=previous.symbol,
            side=previous.side,
            requested_quantity=requested_quantity,
            reduce_only=previous.reduce_only,
            parent_order_id=previous.parent_order_id,
            replaces_order_id=previous.order_id,
            metadata={**previous.metadata, "replace_reason": "cancel_replace"},
        )

    def plan_exit_orders(
        self,
        parent_order_id: str,
        *,
        idempotency_key: str,
        stop_loss_price: float,
        take_profit_price: float,
        quantity: float | None = None,
    ) -> OmsExitPlan:
        _validate_idempotency_key(idempotency_key)
        parent = self.repository.get(parent_order_id)
        if parent is None:
            raise KeyError(f"OMS order {parent_order_id} not found")
        if parent.reduce_only:
            raise ValueError("退出计划的父订单不能是 reduce-only 订单")
        if parent.status not in {OrderStatus.FILLED, OrderStatus.RECONCILED} or parent.filled_quantity <= 0:
            raise ValueError("只有已成交的入场订单可以创建退出计划")
        exit_quantity = parent.filled_quantity if quantity is None else float(quantity)
        if exit_quantity <= 0 or exit_quantity > parent.filled_quantity:
            raise ValueError("退出数量必须大于 0 且不超过父订单已成交数量")
        entry_price = parent.average_fill_price
        if entry_price is None or entry_price <= 0:
            raise ValueError("父订单缺少有效成交均价")
        _validate_exit_prices(parent.side, entry_price, stop_loss_price, take_profit_price)
        exit_side = OrderSide.SELL if parent.side is OrderSide.BUY else OrderSide.BUY
        oco_group_id = _id("oms-oco", f"{parent.order_id}:{idempotency_key}")
        common_metadata = {
            "oco_group_id": oco_group_id,
            "entry_side": parent.side.value,
            "entry_price": entry_price,
        }
        stop_loss = self.plan_order(
            idempotency_key=f"{idempotency_key}:stop-loss",
            client_order_id=f"{parent.client_order_id}:stop-loss",
            venue=parent.venue,
            environment=parent.environment,
            symbol=parent.symbol,
            side=exit_side,
            requested_quantity=exit_quantity,
            reduce_only=True,
            parent_order_id=parent.order_id,
            metadata={**common_metadata, "exit_kind": "stop_loss", "trigger_price": float(stop_loss_price)},
        )
        take_profit = self.plan_order(
            idempotency_key=f"{idempotency_key}:take-profit",
            client_order_id=f"{parent.client_order_id}:take-profit",
            venue=parent.venue,
            environment=parent.environment,
            symbol=parent.symbol,
            side=exit_side,
            requested_quantity=exit_quantity,
            reduce_only=True,
            parent_order_id=parent.order_id,
            metadata={**common_metadata, "exit_kind": "take_profit", "trigger_price": float(take_profit_price)},
        )
        return OmsExitPlan(parent.order_id, oco_group_id, stop_loss, take_profit)

    def fill_exit_order(
        self,
        order_id: str,
        *,
        idempotency_key: str,
        average_fill_price: float,
    ) -> OmsOrder:
        order = self.repository.get(order_id)
        if order is None:
            raise KeyError(f"OMS order {order_id} not found")
        if not order.reduce_only or not order.parent_order_id or not order.metadata.get("oco_group_id"):
            raise ValueError("只有 OCO reduce-only 退出订单可以使用 fill_exit_order")
        filled = self.transition(
            order_id,
            to_status=OrderStatus.FILLED,
            idempotency_key=idempotency_key,
            average_fill_price=average_fill_price,
            reason="exit_triggered",
        )
        for sibling in self.repository.list_child_orders(order.parent_order_id):
            if (
                sibling.order_id != order_id
                and sibling.metadata.get("oco_group_id") == order.metadata.get("oco_group_id")
                and sibling.status not in TERMINAL_STATUSES
            ):
                self.cancel(
                    sibling.order_id,
                    idempotency_key=f"{idempotency_key}:cancel-sibling:{sibling.order_id}",
                    reason="oco_sibling_filled",
                )
        return filled


def _event(
    order: OmsOrder,
    idempotency_key: str,
    event_type: str,
    from_status: OrderStatus | None,
    *,
    reason: str | None = None,
    payload: dict[str, Any] | None = None,
    timestamp: str,
) -> OmsOrderEvent:
    return OmsOrderEvent(
        event_id=_id("oms-event", idempotency_key),
        order_id=order.order_id,
        sequence=order.version,
        idempotency_key=idempotency_key,
        event_type=event_type,
        from_status=from_status,
        to_status=order.status,
        filled_quantity=order.filled_quantity,
        average_fill_price=order.average_fill_price,
        reason=reason,
        occurred_at=timestamp,
        payload=dict(payload or {}),
    )


def _id(namespace: str, value: str) -> str:
    return hashlib.sha256(f"{namespace}:{value}".encode("utf-8")).hexdigest()[:32]


def _timestamp(value: datetime | None) -> str:
    timestamp = value or datetime.now(timezone.utc)
    if timestamp.tzinfo is None:
        raise ValueError("occurred_at 必须包含时区")
    return timestamp.astimezone(timezone.utc).isoformat()


def _validate_idempotency_key(value: str) -> None:
    if not value or len(value) > 200:
        raise ValueError("idempotency_key 必须为 1 到 200 个字符")


def _validate_exit_prices(
    entry_side: OrderSide,
    entry_price: float,
    stop_loss_price: float,
    take_profit_price: float,
) -> None:
    stop_loss = float(stop_loss_price)
    take_profit = float(take_profit_price)
    if min(stop_loss, take_profit) <= 0:
        raise ValueError("止损价和止盈价必须大于 0")
    if entry_side is OrderSide.BUY and not stop_loss < entry_price < take_profit:
        raise ValueError("BUY 入场要求 stop_loss < entry_price < take_profit")
    if entry_side is OrderSide.SELL and not take_profit < entry_price < stop_loss:
        raise ValueError("SELL 入场要求 take_profit < entry_price < stop_loss")
