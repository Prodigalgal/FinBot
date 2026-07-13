from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class ReadinessCheck:
    name: str
    status: str
    detail: str | None = None

    def to_dict(self) -> dict[str, str | None]:
        return {
            "name": self.name,
            "status": self.status,
            "detail": self.detail,
        }


class HealthService:
    def __init__(self, app_state: Any):
        self.app_state = app_state

    def liveness(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "service": "finbot-web",
            "check": "liveness",
        }

    def readiness(self) -> tuple[int, dict[str, Any]]:
        checks = [
            self._runtime_directory_check(),
            self._database_check(),
            self._production_safety_check(),
        ]
        ready = all(check.status == "passed" for check in checks)
        payload = {
            "status": "ready" if ready else "not_ready",
            "service": "finbot-web",
            "check": "readiness",
            "checks": [check.to_dict() for check in checks],
        }
        return (200 if ready else 503), payload

    def _runtime_directory_check(self) -> ReadinessCheck:
        data_dir = Path(self.app_state.autonomous_config().data_dir)
        try:
            data_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            return ReadinessCheck("runtime_directory", "failed", type(exc).__name__)
        if not os.access(data_dir, os.R_OK | os.W_OK):
            return ReadinessCheck("runtime_directory", "failed", "目录不可读写")
        return ReadinessCheck("runtime_directory", "passed", str(data_dir))

    def _database_check(self) -> ReadinessCheck:
        try:
            store = self.app_state.autonomous_store()
            with store.connect() as connection:
                result = connection.execute("select 1").fetchone()
            if result is None or int(result[0]) != 1:
                return ReadinessCheck("database", "failed", "探针结果无效")
        except Exception as exc:
            return ReadinessCheck("database", "failed", type(exc).__name__)
        return ReadinessCheck("database", "passed", str(getattr(store, "backend", "sqlite")))

    def _production_safety_check(self) -> ReadinessCheck:
        if os.getenv("FINBOT_DEPLOYMENT_MODE", "local").strip().lower() != "production":
            return ReadinessCheck("production_safety", "passed", "local")
        blockers: list[str] = []
        auth_manager = getattr(self.app_state, "auth_manager", None)
        auth_settings = getattr(auth_manager, "settings", None)
        if auth_settings is None or not auth_settings.enabled:
            blockers.append("认证未启用")
        elif not auth_settings.cookie_secure:
            blockers.append("认证 Cookie 未启用 Secure")
        submit_orders = self.app_state.config_store.value("paper_execution.submit_orders", False)
        require_human_review = self.app_state.config_store.value(
            "paper_execution.require_human_review", False
        )
        execution_robot_enabled = self.app_state.config_store.value(
            "execution_robot.enabled", True
        )
        if submit_orders and not require_human_review and not execution_robot_enabled:
            blockers.append("自动批准模拟执行必须启用最终执行机器人")
        if os.getenv("FINBOT_REQUIRE_PAPER_SUBMIT_DISABLED", "1").strip().lower() in {
            "1",
            "true",
            "yes",
            "on",
        } and submit_orders:
            blockers.append("首次生产部署不允许开启模拟订单提交")
        proxy_pool = self.app_state.config_store.value("exchange.proxy_pool", "")
        if not str(proxy_pool or "").strip():
            blockers.append("交易所代理池未配置")
        if blockers:
            return ReadinessCheck("production_safety", "failed", "；".join(blockers))
        return ReadinessCheck("production_safety", "passed", "production")
