from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from finbot.config.paths import runtime_root
from finbot.config.settings import Settings
from finbot.web.service import FinBotWebApp


class RuntimePathTests(unittest.TestCase):
    def test_runtime_root_defaults_to_project_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(os.environ, {}, clear=False):
            os.environ.pop("FINBOT_RUNTIME_ROOT", None)
            project_root = Path(temp_dir)

            self.assertEqual(runtime_root(project_root), project_root.resolve())

    def test_runtime_root_moves_mutable_config_without_moving_code_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            project_root = root / "app"
            state_root = root / "state"
            project_root.mkdir()
            runtime_config = state_root / "config" / "runtime_config.json"
            runtime_config.parent.mkdir(parents=True)
            runtime_config.write_text(
                json.dumps(
                    {
                        "version": 1,
                        "values": {
                            "system.http_user_agent": "FinBot Kubernetes",
                        },
                    }
                ),
                encoding="utf-8",
            )

            with patch.dict(os.environ, {"FINBOT_RUNTIME_ROOT": str(state_root)}):
                settings = Settings.from_env(project_root=project_root, data_dir=state_root / "data")
                web = FinBotWebApp(data_dir=str(state_root / "data"))

            self.assertEqual(settings.project_root, project_root)
            self.assertEqual(settings.data_dir, state_root / "data")
            self.assertEqual(settings.http_user_agent, "FinBot Kubernetes")
            self.assertEqual(web.config_store.path, runtime_config)
            self.assertEqual(web.ai_config_store.path, state_root / "config" / "ai_sites.json")


if __name__ == "__main__":
    unittest.main()
