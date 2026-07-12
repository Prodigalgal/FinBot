from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from finbot.config.runtime_config import RuntimeConfigStore
from finbot.config.settings import Settings
from finbot.web.service import _proxy_policy_values, _update_proxy_policy


class RuntimeConfigTests(unittest.TestCase):
    def test_settings_from_env_applies_runtime_config_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            store = RuntimeConfigStore(root)
            store.update(
                {
                    "system.data_dir": str(root / "custom-data"),
                    "system.http_user_agent": "FinBot test agent",
                    "firecrawl.api_key": "secret-firecrawl-key",
                    "paper_execution.bybit_demo_api_key": "demo-key",
                    "paper_execution.bybit_demo_api_secret": "demo-secret",
                    "exchange.vless_max_nodes": 7,
                    "exchange.vless_preferred_node_names": ["preferred-jp", "fallback-gb"],
                    "exchange.hysteria2_urls": "hysteria2://password@proxy.example:443?sni=proxy.example",
                    "exchange.hysteria2_max_nodes": 3,
                }
            )

            settings = Settings.from_env(project_root=root, data_dir=root / "data")
            snapshot = store.snapshot(settings)

        self.assertEqual(settings.data_dir, root / "custom-data")
        self.assertEqual(settings.sqlite_path, root / "custom-data" / "finbot.sqlite3")
        self.assertEqual(settings.http_user_agent, "FinBot test agent")
        self.assertEqual(settings.exchange_vless_max_nodes, 7)
        self.assertEqual(settings.exchange_vless_preferred_node_names, ["preferred-jp", "fallback-gb"])
        self.assertEqual(settings.exchange_hysteria2_urls, "hysteria2://password@proxy.example:443?sni=proxy.example")
        self.assertEqual(settings.exchange_hysteria2_max_nodes, 3)
        self.assertEqual(settings.firecrawl_api_key, "secret-firecrawl-key")
        self.assertIsNone(snapshot["values"]["firecrawl.api_key"]["value"])
        self.assertTrue(snapshot["values"]["firecrawl.api_key"]["configured"])
        self.assertIsNone(snapshot["values"]["paper_execution.bybit_demo_api_key"]["value"])
        self.assertIsNone(snapshot["values"]["paper_execution.bybit_demo_api_secret"]["value"])
        self.assertTrue(snapshot["values"]["paper_execution.bybit_demo_api_key"]["configured"])
        self.assertIsNone(snapshot["values"]["exchange.hysteria2_urls"]["value"])
        self.assertTrue(snapshot["values"]["exchange.hysteria2_urls"]["configured"])

    def test_proxy_policy_update_is_hot_loadable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            policy_path = Path(temp_dir) / "config" / "proxy_policy.json"

            _update_proxy_policy(
                str(policy_path),
                {
                    "proxy_policy.exchange.bybit.allow_direct": True,
                    "proxy_policy.exchange.gate.allow_direct": False,
                },
                [],
            )
            values = _proxy_policy_values(str(policy_path))

        self.assertTrue(values["proxy_policy.exchange.bybit.allow_direct"])
        self.assertFalse(values["proxy_policy.exchange.gate.allow_direct"])


if __name__ == "__main__":
    unittest.main()
