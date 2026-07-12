from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.config.ai_sites import AISitesConfigStore
from finbot.config.runtime_config import CONFIG_FIELD_MAP, RuntimeConfigStore
from finbot.config.setup_profiles import (
    SETUP_PROFILES,
    apply_setup_profile,
    setup_readiness,
)


class SetupProfileTests(unittest.TestCase):
    def test_recommended_profile_fills_defaults_and_preserves_existing_values(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store = RuntimeConfigStore(Path(temp_dir))
            store.update(
                {
                    "autonomous.interval_minutes": 99,
                    "firecrawl.api_key": "existing-secret",
                }
            )

            result = apply_setup_profile(
                store,
                profile_id="recommended",
                preserve_existing=True,
            )
            values = store.values()

        self.assertGreater(len(result["applied_keys"]), 40)
        self.assertIn("autonomous.interval_minutes", result["preserved_keys"])
        self.assertEqual(values["autonomous.interval_minutes"], 99)
        self.assertTrue(values["autonomous.enabled"])
        self.assertEqual(values["autonomous.ai_budget_max_cost_usd_per_loop"], 0.5)
        self.assertEqual(values["firecrawl.api_key"], "existing-secret")

    def test_profiles_never_include_sensitive_or_proxy_policy_fields(self) -> None:
        for profile in SETUP_PROFILES:
            for key in profile.values():
                self.assertFalse(CONFIG_FIELD_MAP[key].sensitive, key)
                self.assertIsNone(CONFIG_FIELD_MAP[key].settings_field, key)
                self.assertFalse(key.startswith("proxy_policy."), key)

    def test_readiness_reports_ready_for_complete_defaults(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            config_store = RuntimeConfigStore(root)
            apply_setup_profile(config_store, profile_id="recommended")
            ai_store = AISitesConfigStore(root)
            ai_payload = ai_store.public_payload(
                env={
                    "DEEPSEEK_API_KEY": "key-a",
                    "MIMO_API_KEY": "key-b",
                    "SUB2API_API_KEY": "key-c",
                }
            )

            readiness = setup_readiness(
                config_store,
                ai_payload=ai_payload,
                scheduler_snapshot={"active_worker_count": 1},
            )

        self.assertEqual(readiness["status"], "ready")
        self.assertEqual(readiness["required_failure_count"], 0)
        self.assertEqual(readiness["warning_count"], 0)
        pricing_check = next(item for item in readiness["checks"] if item["check_id"] == "ai_pricing")
        self.assertEqual(pricing_check["detail"], "1/1 个启用站点型号费率匹配")


if __name__ == "__main__":
    unittest.main()
