from __future__ import annotations

import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class ArchitectureGuardTests(unittest.TestCase):
    def test_known_large_files_cannot_keep_growing(self) -> None:
        limits = {
            "finbot/web/service.py": 110_000,
            "finbot/storage/sqlite_store.py": 230_000,
            "finbot/autonomous/ai_debate.py": 60_000,
            "finbot/autonomous/runner.py": 58_000,
            "web-ui/src/App.tsx": 82_000,
            "web-ui/src/CouncilWorkflowPanel.tsx": 58_000,
        }
        violations = [
            f"{path}: {(ROOT / path).stat().st_size} > {limit}"
            for path, limit in limits.items()
            if (ROOT / path).stat().st_size > limit
        ]
        self.assertEqual(violations, [], "大文件继续增长，新增职责必须拆到独立模块: " + "; ".join(violations))

    def test_domain_modules_do_not_depend_on_web_layer(self) -> None:
        domain_roots = ("backtest", "execution", "experiments", "risk")
        violations: list[str] = []
        for domain_root in domain_roots:
            for path in (ROOT / "finbot" / domain_root).rglob("*.py"):
                tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
                for node in ast.walk(tree):
                    module = node.module if isinstance(node, ast.ImportFrom) else None
                    names = [alias.name for alias in node.names] if isinstance(node, ast.Import) else []
                    if (module and module.startswith("finbot.web")) or any(name.startswith("finbot.web") for name in names):
                        violations.append(str(path.relative_to(ROOT)))
        self.assertEqual(violations, [], "领域层不得依赖 web 层")


if __name__ == "__main__":
    unittest.main()
