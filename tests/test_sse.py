from __future__ import annotations

import asyncio
import json
import unittest

from finbot.web.sse import encode_sse, snapshot_event_stream


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


if __name__ == "__main__":
    unittest.main()
