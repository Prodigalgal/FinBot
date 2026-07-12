from __future__ import annotations

import os
from pathlib import Path


RUNTIME_ROOT_ENV = "FINBOT_RUNTIME_ROOT"


def runtime_root(project_root: Path | None = None) -> Path:
    configured = os.getenv(RUNTIME_ROOT_ENV, "").strip()
    if configured:
        return Path(configured).expanduser().resolve()
    return (project_root or Path.cwd()).resolve()
