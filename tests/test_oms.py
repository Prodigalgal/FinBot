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


if __name__ == "__main__":
    unittest.main()
