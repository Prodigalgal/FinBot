from __future__ import annotations

import json
import logging
import unittest

from finbot.observability.logging import JsonLogFormatter


class StructuredLoggingTests(unittest.TestCase):
    def test_formatter_emits_fixed_fields_without_arbitrary_secrets(self) -> None:
        record = logging.LogRecord("finbot.test", logging.INFO, __file__, 1, "ready", (), None)
        record.event = "component_ready"
        record.api_key = "must-not-leak"
        payload = json.loads(JsonLogFormatter().format(record))

        self.assertEqual(payload["event"], "component_ready")
        self.assertNotIn("api_key", payload)
        self.assertNotIn("must-not-leak", json.dumps(payload))


if __name__ == "__main__":
    unittest.main()
