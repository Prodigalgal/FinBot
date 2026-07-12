from __future__ import annotations

import unittest

import httpx

from finbot.ingestion.adapters.firecrawl import _proxy_failure_reason


class FirecrawlProxyFeedbackTests(unittest.TestCase):
    def test_suspicious_ip_response_is_retryable_on_another_proxy(self) -> None:
        response = httpx.Response(
            403,
            json={
                "success": False,
                "error": "Unfortunately, your IP address looks suspicious, so Firecrawl can't be used without an API key from here.",
            },
        )

        self.assertEqual(_proxy_failure_reason(response), "provider-suspicious-ip")

    def test_unrelated_forbidden_response_is_not_treated_as_proxy_failure(self) -> None:
        response = httpx.Response(403, json={"error": "API key does not have permission"})

        self.assertIsNone(_proxy_failure_reason(response))

    def test_gateway_and_rate_limit_responses_are_retryable(self) -> None:
        for status_code in (407, 429, 502, 503, 504):
            with self.subTest(status_code=status_code):
                self.assertEqual(
                    _proxy_failure_reason(httpx.Response(status_code)),
                    f"http-{status_code}",
                )


if __name__ == "__main__":
    unittest.main()
