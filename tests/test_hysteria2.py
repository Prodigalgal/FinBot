from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from finbot.network.hysteria2 import parse_hysteria2_url, parse_hysteria2_urls
from finbot.network.sing_box_bridge import SingBoxBridgeConfig, SingBoxBridgeManager, _sing_box_config


class _FakeProcess:
    def __init__(self) -> None:
        self.running = True

    def poll(self):
        return None if self.running else 0

    def terminate(self) -> None:
        self.running = False

    def wait(self, timeout=None) -> int:
        self.running = False
        return 0

    def kill(self) -> None:
        self.running = False


class Hysteria2Tests(unittest.TestCase):
    def test_parse_url_decodes_credentials_and_port_hopping(self) -> None:
        node = parse_hysteria2_url(
            "hysteria2://secret%40value@proxy.example:443"
            "?sni=tls.example&insecure=0&obfs=salamander&obfs-password=obfs%40secret"
            "&mport=20000-29999#osaka"
        )

        self.assertEqual(node.password, "secret@value")
        self.assertEqual(node.server_ports, ("20000:29999",))
        self.assertEqual(node.sni, "tls.example")
        self.assertFalse(node.insecure)
        self.assertEqual(node.obfs_password, "obfs@secret")
        self.assertEqual(node.name, "osaka")
        self.assertEqual(node.redacted()["password"], "<redacted>")
        self.assertNotIn("secret@value", json.dumps(node.redacted()))

    def test_parse_multiline_urls_preserves_priority(self) -> None:
        nodes = parse_hysteria2_urls(
            "\n".join(
                (
                    "hysteria2://one@first.example:443",
                    "hysteria2://two@second.example:8443?allowInsecure=1",
                )
            )
        )

        self.assertEqual([node.address for node in nodes], ["first.example", "second.example"])
        self.assertTrue(nodes[1].insecure)

    def test_sing_box_config_maps_hysteria2_fields(self) -> None:
        node = parse_hysteria2_url(
            "hysteria2://password@proxy.example:443?sni=proxy.example"
            "&obfs=salamander&obfs-password=obfs&mport=20000-29999"
        )

        config = _sing_box_config(node, "127.0.0.1", 31000)
        outbound = config["outbounds"][0]

        self.assertEqual(outbound["type"], "hysteria2")
        self.assertEqual(outbound["server_ports"], ["20000:29999"])
        self.assertEqual(outbound["password"], "password")
        self.assertEqual(outbound["obfs"], {"type": "salamander", "password": "obfs"})
        self.assertFalse(outbound["tls"]["insecure"])

    def test_bridge_isolates_startup_failure_and_removes_secret_configs(self) -> None:
        nodes = parse_hysteria2_urls(
            "\n".join(
                (
                    "hysteria2://first-secret@first.example:443",
                    "hysteria2://second-secret@second.example:443",
                    "hysteria2://third-secret@third.example:443",
                )
            )
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            binary = root / "sing-box.exe"
            binary.touch()
            manager = SingBoxBridgeManager(
                nodes,
                SingBoxBridgeConfig(binary_path=str(binary), work_dir=root / "runtime", max_nodes=2),
            )
            fake_process = _FakeProcess()
            with patch.object(
                manager,
                "_check_config",
                side_effect=(RuntimeError("first failed"), None, None),
            ), patch.object(manager, "_start_process", return_value=fake_process), patch(
                "finbot.network.sing_box_bridge._wait_port"
            ):
                proxies = manager.start()

            config_paths = list((root / "runtime").glob("*.json"))
            self.assertEqual(len(proxies), 2)
            self.assertEqual(manager.summary()["startup_error_count"], 1)
            self.assertEqual(len(config_paths), 2)
            config_payload = "\n".join(path.read_text(encoding="utf-8") for path in config_paths)
            self.assertIn("second-secret", config_payload)
            self.assertIn("third-secret", config_payload)

            manager.close()

            self.assertTrue(all(not path.exists() for path in config_paths))


if __name__ == "__main__":
    unittest.main()
