from __future__ import annotations

import unittest

from finbot.network.proxy_pool import ProxyPool


class FakeClock:
    def __init__(self) -> None:
        self.now = 100.0

    def __call__(self) -> float:
        return self.now


class ProxyPoolTests(unittest.TestCase):
    def test_failed_candidate_is_cooled_without_stopping_other_candidates(self) -> None:
        clock = FakeClock()
        pool = ProxyPool(
            ["http://proxy-a:8080", "http://proxy-b:8080", "http://proxy-c:8080"],
            include_direct=False,
            cooldown_base_seconds=10,
            cooldown_max_seconds=60,
            clock=clock,
        )

        first = pool.candidates(attempts=1)[0]
        pool.report_failure(first, "ConnectError: token=must-not-leak")

        self.assertEqual(pool.candidates(attempts=3), ["http://proxy-b:8080", "http://proxy-c:8080"])
        summary = pool.health_summary()
        self.assertEqual(summary["available_count"], 2)
        self.assertEqual(summary["cooling_down_count"], 1)
        self.assertEqual(summary["candidates"][0]["last_error_type"], "ConnectError")

        clock.now += 11
        self.assertEqual(len(pool.candidates(attempts=3)), 3)

    def test_failure_cooldown_is_exponential_and_success_resets_health(self) -> None:
        clock = FakeClock()
        proxy = "http://user:password@proxy-a:8080"
        pool = ProxyPool(
            [proxy],
            include_direct=False,
            cooldown_base_seconds=5,
            cooldown_max_seconds=20,
            clock=clock,
        )

        pool.report_failure(proxy, "ConnectTimeout")
        self.assertEqual(pool.candidates(), [])
        clock.now += 5
        pool.report_failure(proxy, "ConnectTimeout")
        self.assertEqual(pool.health_summary()["candidates"][0]["cooldown_remaining_seconds"], 10)
        clock.now += 10
        pool.report_success(proxy)

        candidate = pool.health_summary()["candidates"][0]
        self.assertEqual(candidate["status"], "available")
        self.assertEqual(candidate["consecutive_failures"], 0)
        self.assertEqual(candidate["proxy"], "http://<redacted>@proxy-a:8080")


if __name__ == "__main__":
    unittest.main()
