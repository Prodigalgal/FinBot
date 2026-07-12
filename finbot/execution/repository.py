from __future__ import annotations

import json
from typing import Any

from finbot.execution.models import OmsOrder, OmsOrderEvent, OrderSide, OrderStatus
from finbot.storage.sqlite_store import SQLiteStore, StaleRecordError


class OmsRepository:
    def __init__(self, store: SQLiteStore):
        self.store = store
        self.init_schema()

    def init_schema(self) -> None:
        with self.store.connect() as connection:
            connection.executescript(
                """
                create table if not exists oms_orders (
                  order_id text primary key,
                  client_order_id text not null unique,
                  venue text not null,
                  environment text not null,
                  symbol text not null,
                  side text not null,
                  requested_quantity real not null,
                  filled_quantity real not null,
                  average_fill_price real,
                  status text not null,
                  reduce_only integer not null,
                  parent_order_id text,
                  replaces_order_id text,
                  exchange_order_id text,
                  version integer not null,
                  created_at text not null,
                  updated_at text not null,
                  metadata_json text not null
                );
                create index if not exists idx_oms_orders_status_updated
                  on oms_orders(status, updated_at);
                create table if not exists oms_order_events (
                  event_id text primary key,
                  order_id text not null,
                  sequence integer not null,
                  idempotency_key text not null unique,
                  event_type text not null,
                  from_status text,
                  to_status text not null,
                  filled_quantity real not null,
                  average_fill_price real,
                  reason text,
                  occurred_at text not null,
                  payload_json text not null,
                  unique(order_id, sequence),
                  foreign key(order_id) references oms_orders(order_id)
                );
                create index if not exists idx_oms_events_order_sequence
                  on oms_order_events(order_id, sequence);
                """
            )

    def get_by_idempotency_key(self, idempotency_key: str) -> OmsOrder | None:
        with self.store.connect() as connection:
            row = connection.execute(
                """
                select orders.* from oms_order_events events
                join oms_orders orders on orders.order_id = events.order_id
                where events.idempotency_key = ?
                """,
                (idempotency_key,),
            ).fetchone()
        return _order(row) if row else None

    def get(self, order_id: str) -> OmsOrder | None:
        with self.store.connect() as connection:
            row = connection.execute(
                "select * from oms_orders where order_id = ?",
                (order_id,),
            ).fetchone()
        return _order(row) if row else None

    def list_orders(self, *, limit: int = 100) -> list[OmsOrder]:
        with self.store.connect() as connection:
            rows = connection.execute(
                "select * from oms_orders order by updated_at desc limit ?",
                (max(1, min(limit, 500)),),
            ).fetchall()
        return [_order(row) for row in rows]

    def list_events(self, order_id: str) -> list[OmsOrderEvent]:
        with self.store.connect() as connection:
            rows = connection.execute(
                "select * from oms_order_events where order_id = ? order by sequence",
                (order_id,),
            ).fetchall()
        return [_event(row) for row in rows]

    def list_child_orders(self, parent_order_id: str) -> list[OmsOrder]:
        with self.store.connect() as connection:
            rows = connection.execute(
                "select * from oms_orders where parent_order_id = ? order by created_at, order_id",
                (parent_order_id,),
            ).fetchall()
        return [_order(row) for row in rows]

    def create(self, order: OmsOrder, event: OmsOrderEvent) -> OmsOrder:
        with self.store.connect() as connection:
            existing = connection.execute(
                "select order_id from oms_order_events where idempotency_key = ?",
                (event.idempotency_key,),
            ).fetchone()
            if existing:
                row = connection.execute(
                    "select * from oms_orders where order_id = ?",
                    (existing["order_id"],),
                ).fetchone()
                return _order(row)
            connection.execute(
                """
                insert into oms_orders (
                  order_id, client_order_id, venue, environment, symbol, side,
                  requested_quantity, filled_quantity, average_fill_price, status,
                  reduce_only, parent_order_id, replaces_order_id, exchange_order_id,
                  version, created_at, updated_at, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                _order_values(order),
            )
            self._insert_event(connection, event)
        return order

    def transition(
        self,
        *,
        previous: OmsOrder,
        current: OmsOrder,
        event: OmsOrderEvent,
    ) -> OmsOrder:
        with self.store.connect() as connection:
            duplicate = connection.execute(
                "select order_id from oms_order_events where idempotency_key = ?",
                (event.idempotency_key,),
            ).fetchone()
            if duplicate:
                row = connection.execute(
                    "select * from oms_orders where order_id = ?",
                    (duplicate["order_id"],),
                ).fetchone()
                return _order(row)
            cursor = connection.execute(
                """
                update oms_orders set
                  filled_quantity = ?, average_fill_price = ?, status = ?,
                  exchange_order_id = ?, version = ?, updated_at = ?, metadata_json = ?
                where order_id = ? and version = ?
                """,
                (
                    current.filled_quantity,
                    current.average_fill_price,
                    current.status.value,
                    current.exchange_order_id,
                    current.version,
                    current.updated_at,
                    json.dumps(current.metadata, ensure_ascii=False, default=str),
                    current.order_id,
                    previous.version,
                ),
            )
            if cursor.rowcount != 1:
                raise StaleRecordError(f"OMS order {current.order_id} version conflict")
            self._insert_event(connection, event)
        return current

    @staticmethod
    def _insert_event(connection: Any, event: OmsOrderEvent) -> None:
        connection.execute(
            """
            insert into oms_order_events (
              event_id, order_id, sequence, idempotency_key, event_type,
              from_status, to_status, filled_quantity, average_fill_price,
              reason, occurred_at, payload_json
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                event.event_id,
                event.order_id,
                event.sequence,
                event.idempotency_key,
                event.event_type,
                event.from_status.value if event.from_status else None,
                event.to_status.value,
                event.filled_quantity,
                event.average_fill_price,
                event.reason,
                event.occurred_at,
                json.dumps(event.payload, ensure_ascii=False, default=str),
            ),
        )


def _order_values(order: OmsOrder) -> tuple[Any, ...]:
    return (
        order.order_id,
        order.client_order_id,
        order.venue,
        order.environment,
        order.symbol,
        order.side.value,
        order.requested_quantity,
        order.filled_quantity,
        order.average_fill_price,
        order.status.value,
        int(order.reduce_only),
        order.parent_order_id,
        order.replaces_order_id,
        order.exchange_order_id,
        order.version,
        order.created_at,
        order.updated_at,
        json.dumps(order.metadata, ensure_ascii=False, default=str),
    )


def _order(row: Any) -> OmsOrder:
    return OmsOrder(
        order_id=row["order_id"],
        client_order_id=row["client_order_id"],
        venue=row["venue"],
        environment=row["environment"],
        symbol=row["symbol"],
        side=OrderSide(row["side"]),
        requested_quantity=float(row["requested_quantity"]),
        filled_quantity=float(row["filled_quantity"]),
        average_fill_price=(float(row["average_fill_price"]) if row["average_fill_price"] is not None else None),
        status=OrderStatus(row["status"]),
        reduce_only=bool(row["reduce_only"]),
        parent_order_id=row["parent_order_id"],
        replaces_order_id=row["replaces_order_id"],
        exchange_order_id=row["exchange_order_id"],
        version=int(row["version"]),
        created_at=row["created_at"],
        updated_at=row["updated_at"],
        metadata=json.loads(row["metadata_json"] or "{}"),
    )


def _event(row: Any) -> OmsOrderEvent:
    return OmsOrderEvent(
        event_id=row["event_id"],
        order_id=row["order_id"],
        sequence=int(row["sequence"]),
        idempotency_key=row["idempotency_key"],
        event_type=row["event_type"],
        from_status=OrderStatus(row["from_status"]) if row["from_status"] else None,
        to_status=OrderStatus(row["to_status"]),
        filled_quantity=float(row["filled_quantity"]),
        average_fill_price=(float(row["average_fill_price"]) if row["average_fill_price"] is not None else None),
        reason=row["reason"],
        occurred_at=row["occurred_at"],
        payload=json.loads(row["payload_json"] or "{}"),
    )
