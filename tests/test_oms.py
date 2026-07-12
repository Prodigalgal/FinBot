from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.execution import OmsRepository, OmsService, OrderStatus
from finbot.storage.sqlite_store import SQLiteStore


class OmsTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        store = SQLiteStore(Path(self.temp_dir.name) / "finbot.sqlite3")
        self.repository = OmsRepository(store)
        self.service = OmsService(self.repository)

    def test_full_partial_fill_and_reconcile_history(self) -> None:
        order = self._plan("create-1")
        submitted = self.service.transition(
            order.order_id,
            to_status="submitted",
            idempotency_key="submit-1",
            exchange_order_id="exchange-1",
        )
        partial = self.service.transition(
            order.order_id,
            to_status="partial",
            idempotency_key="partial-1",
            filled_quantity=2,
            average_fill_price=100,
        )
        filled = self.service.transition(
            order.order_id,
            to_status="filled",
            idempotency_key="filled-1",
            average_fill_price=101,
        )
        reconciled = self.service.reconcile(
            order.order_id,
            idempotency_key="reconcile-1",
            exchange_payload={"status": "closed"},
        )

        self.assertEqual(submitted.status, OrderStatus.SUBMITTED)
        self.assertEqual(partial.remaining_quantity, 3)
        self.assertEqual(filled.filled_quantity, 5)
        self.assertEqual(reconciled.status, OrderStatus.RECONCILED)
        self.assertEqual([event.sequence for event in self.repository.list_events(order.order_id)], [1, 2, 3, 4, 5])

    def test_duplicate_transition_is_idempotent(self) -> None:
        order = self._plan("create-2")
        first = self.service.transition(
            order.order_id,
            to_status="submitted",
            idempotency_key="submit-2",
        )
        duplicate = self.service.transition(
            order.order_id,
            to_status="submitted",
            idempotency_key="submit-2",
        )

        self.assertEqual(first.version, duplicate.version)
        self.assertEqual(len(self.repository.list_events(order.order_id)), 2)

    def test_cancel_replace_is_idempotent_and_links_orders(self) -> None:
        original = self._plan("create-3")
        replacement = self.service.replace_order(
            original.order_id,
            idempotency_key="replace-3",
            replacement_client_order_id="client-replacement",
            requested_quantity=4,
        )
        duplicate = self.service.replace_order(
            original.order_id,
            idempotency_key="replace-3",
            replacement_client_order_id="client-replacement",
            requested_quantity=4,
        )

        self.assertEqual(self.repository.get(original.order_id).status, OrderStatus.CANCELLED)
        self.assertEqual(replacement.order_id, duplicate.order_id)
        self.assertEqual(replacement.replaces_order_id, original.order_id)

    def test_invalid_transition_and_mainnet_are_blocked(self) -> None:
        order = self._plan("create-4")
        with self.assertRaisesRegex(ValueError, "非法 OMS"):
            self.service.transition(order.order_id, to_status="filled", idempotency_key="filled-early")
        with self.assertRaisesRegex(ValueError, "paper/testnet/demo"):
            self.service.plan_order(
                idempotency_key="mainnet",
                client_order_id="mainnet",
                venue="gate",
                environment="mainnet",
                symbol="BTC_USDT",
                side="BUY",
                requested_quantity=1,
            )

    def test_reduce_only_exit_plan_is_linked_idempotent_and_oco(self) -> None:
        entry = self._filled_entry("create-exit")
        plan = self.service.plan_exit_orders(
            entry.order_id,
            idempotency_key="exit-plan-1",
            stop_loss_price=95,
            take_profit_price=110,
        )
        duplicate = self.service.plan_exit_orders(
            entry.order_id,
            idempotency_key="exit-plan-1",
            stop_loss_price=95,
            take_profit_price=110,
        )

        self.assertTrue(plan.stop_loss_order.reduce_only)
        self.assertTrue(plan.take_profit_order.reduce_only)
        self.assertEqual(plan.stop_loss_order.parent_order_id, entry.order_id)
        self.assertEqual(plan.take_profit_order.side.value, "SELL")
        self.assertEqual(duplicate.stop_loss_order.order_id, plan.stop_loss_order.order_id)
        self.assertEqual(len(self.repository.list_child_orders(entry.order_id)), 2)

        for exit_order in (plan.stop_loss_order, plan.take_profit_order):
            self.service.transition(
                exit_order.order_id,
                to_status="submitted",
                idempotency_key=f"submit-{exit_order.order_id}",
            )
        filled = self.service.fill_exit_order(
            plan.take_profit_order.order_id,
            idempotency_key="fill-take-profit",
            average_fill_price=110,
        )
        replayed = self.service.fill_exit_order(
            plan.take_profit_order.order_id,
            idempotency_key="fill-take-profit",
            average_fill_price=110,
        )

        self.assertEqual(filled.status, OrderStatus.FILLED)
        self.assertEqual(replayed.version, filled.version)
        self.assertEqual(self.repository.get(plan.stop_loss_order.order_id).status, OrderStatus.CANCELLED)

    def test_exit_plan_validates_parent_fill_quantity_and_price_direction(self) -> None:
        unfilled = self._plan("unfilled-exit")
        with self.assertRaisesRegex(ValueError, "已成交"):
            self.service.plan_exit_orders(
                unfilled.order_id,
                idempotency_key="invalid-unfilled",
                stop_loss_price=95,
                take_profit_price=110,
            )

        filled = self._filled_entry("invalid-prices")
        with self.assertRaisesRegex(ValueError, "stop_loss"):
            self.service.plan_exit_orders(
                filled.order_id,
                idempotency_key="invalid-prices",
                stop_loss_price=105,
                take_profit_price=110,
            )
        with self.assertRaisesRegex(ValueError, "退出数量"):
            self.service.plan_exit_orders(
                filled.order_id,
                idempotency_key="invalid-quantity",
                stop_loss_price=95,
                take_profit_price=110,
                quantity=6,
            )

    def _plan(self, key: str):
        return self.service.plan_order(
            idempotency_key=key,
            client_order_id=f"client-{key}",
            venue="gate",
            environment="testnet",
            symbol="BTC_USDT",
            side="BUY",
            requested_quantity=5,
        )

    def _filled_entry(self, key: str):
        order = self._plan(key)
        self.service.transition(
            order.order_id,
            to_status="submitted",
            idempotency_key=f"submit-{key}",
        )
        return self.service.transition(
            order.order_id,
            to_status="filled",
            idempotency_key=f"fill-{key}",
            average_fill_price=100,
        )


if __name__ == "__main__":
    unittest.main()
