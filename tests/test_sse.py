from __future__ import annotations

import asyncio
import json
import unittest

from finbot.web.sse import encode_sse, snapshot_digest, snapshot_event_stream


class FakeRequest:
    async def is_disconnected(self) -> bool:
        return False


class ServerSentEventTests(unittest.TestCase):
    def test_encode_sse_uses_named_event_compact_json_and_retry(self) -> None:
        message = encode_sse("snapshot", {"status": "ok", "label": "运行中"}, retry_ms=3000)

        self.assertTrue(message.startswith("retry: 3000\nevent: snapshot\n"))
        self.assertIn('data: {"status":"ok","label":"运行中"}', message)
        self.assertTrue(message.endswith("\n\n"))

    def test_terminal_snapshot_stream_connects_emits_and_completes(self) -> None:
        async def collect() -> list[str]:
            return [
                message
                async for message in snapshot_event_stream(
                    FakeRequest(),
                    lambda: {"status": "succeeded", "session_id": "session-1"},
                    event_name="session",
                    poll_seconds=0.1,
                    heartbeat_seconds=0.1,
                    terminal_statuses=frozenset({"succeeded"}),
                )
            ]

        messages = asyncio.run(collect())

        self.assertEqual(len(messages), 3)
        self.assertIn("event: connected", messages[0])
        self.assertIn("event: session", messages[1])
        self.assertIn("event: complete", messages[2])
        payload = json.loads(messages[1].split("data: ", 1)[1])
        self.assertEqual(payload["session_id"], "session-1")

    def test_subsequent_snapshot_uses_compact_update_factory(self) -> None:
        snapshots = iter(
            (
                {"status": "running", "count": 1},
                {"status": "succeeded", "count": 2},
            )
        )

        async def collect() -> list[str]:
            return [
                message
                async for message in snapshot_event_stream(
                    FakeRequest(),
                    lambda: next(snapshots),
                    event_name="session",
                    poll_seconds=0.1,
                    heartbeat_seconds=0.1,
                    terminal_statuses=frozenset({"succeeded"}),
                    update_payload_factory=lambda current, previous: {
                        "partial": True,
                        "status": current["status"],
                        "previous_count": previous["count"],
                    },
                )
            ]

        messages = asyncio.run(collect())
        session_messages = [message for message in messages if "event: session" in message]
        first_payload = json.loads(session_messages[0].split("data: ", 1)[1])
        second_payload = json.loads(session_messages[1].split("data: ", 1)[1])

        self.assertEqual(first_payload, {"status": "running", "count": 1})
        self.assertEqual(second_payload, {"partial": True, "status": "succeeded", "previous_count": 1})

    def test_snapshot_digest_ignores_heartbeat_fields_but_not_business_state(self) -> None:
        first = {
            "generated_at": "2026-07-13T01:00:00Z",
            "worker": {
                "heartbeat_at": "2026-07-13T01:00:00Z",
                "status": "idle",
                "leases": [{"expires_at": "2026-07-13T01:00:30Z"}],
                "recent_requests": [{"lease_expires_at": "2026-07-13T01:00:30Z"}],
            },
            "runs": [{"status": "running", "updated_at": "2026-07-13T00:59:00Z"}],
        }
        heartbeat_only = {
            "generated_at": "2026-07-13T01:00:06Z",
            "worker": {
                "heartbeat_at": "2026-07-13T01:00:06Z",
                "status": "idle",
                "leases": [{"expires_at": "2026-07-13T01:00:36Z"}],
                "recent_requests": [{"lease_expires_at": "2026-07-13T01:00:36Z"}],
            },
            "runs": [{"status": "running", "updated_at": "2026-07-13T00:59:00Z"}],
        }
        completed = {
            **heartbeat_only,
            "runs": [{"status": "passed", "updated_at": "2026-07-13T01:00:05Z"}],
        }

        self.assertEqual(snapshot_digest(first), snapshot_digest(heartbeat_only))
        self.assertNotEqual(snapshot_digest(first), snapshot_digest(completed))


if __name__ == "__main__":
    unittest.main()
