from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.network.vless_subscription import _SecureRedirectHandler, load_vless_subscription


class VlessSubscriptionSecurityTests(unittest.TestCase):
    def test_redirect_handler_rejects_https_downgrade(self) -> None:
        handler = _SecureRedirectHandler()

        with self.assertRaisesRegex(ValueError, "must use HTTPS"):
            handler.redirect_request(None, None, 302, "Found", {}, "http://proxy.example/sub")

    def test_file_subscription_rejects_oversized_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "oversized.txt"
            with path.open("wb") as stream:
                stream.truncate(5_000_001)

            with self.assertRaisesRegex(ValueError, "exceeds 5 MB"):
                load_vless_subscription(file=str(path))


if __name__ == "__main__":
    unittest.main()
