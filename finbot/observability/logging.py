from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timezone
from typing import Any


class JsonLogFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        for field in ("event", "component", "request_id", "loop_run_id", "status"):
            value = getattr(record, field, None)
            if value is not None:
                payload[field] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def configure_logging(component: str, level: str | None = None) -> logging.Logger:
    resolved_level = (level or os.getenv("FINBOT_LOG_LEVEL", "INFO")).upper()
    handler = logging.StreamHandler()
    if os.getenv("FINBOT_LOG_FORMAT", "text").lower() == "json":
        handler.setFormatter(JsonLogFormatter())
    else:
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s"))
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(resolved_level)
    logger = logging.getLogger(f"finbot.{component}")
    logger.info("component_started", extra={"event": "component_started", "component": component})
    return logger
