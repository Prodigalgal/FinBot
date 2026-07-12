from __future__ import annotations

import threading
from time import monotonic
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from finbot.autonomous.config import AutonomousLoopConfig
from finbot.autonomous.runner import AutonomousResearchLoopRunner


ConfigLoader = Callable[[], AutonomousLoopConfig]
RunnerFactory = Callable[[AutonomousLoopConfig], AutonomousResearchLoopRunner]


@dataclass(frozen=True)
class SchedulerSnapshot:
    status: str
    enabled: bool
    interval_minutes: int
    running: bool
    next_run_at: str | None
    current_trigger_type: str | None
    last_started_at: str | None
    last_finished_at: str | None
    last_loop_run_id: str | None
    last_error: str | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "enabled": self.enabled,
            "interval_minutes": self.interval_minutes,
            "running": self.running,
            "next_run_at": self.next_run_at,
            "current_trigger_type": self.current_trigger_type,
            "last_started_at": self.last_started_at,
            "last_finished_at": self.last_finished_at,
            "last_loop_run_id": self.last_loop_run_id,
            "last_error": self.last_error,
        }


class AutonomousLoopScheduler:
    def __init__(
        self,
        config_loader: ConfigLoader,
        runner_factory: RunnerFactory | None = None,
        poll_seconds: float = 5.0,
    ):
        self.config_loader = config_loader
        self.runner_factory = runner_factory or (lambda _config: AutonomousResearchLoopRunner())
        self.poll_seconds = max(1.0, poll_seconds)
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._scheduler_thread: threading.Thread | None = None
        self._run_thread: threading.Thread | None = None
        self._next_run_at: datetime | None = None
        self._current_trigger_type: str | None = None
        self._last_started_at: str | None = None
        self._last_finished_at: str | None = None
        self._last_loop_run_id: str | None = None
        self._last_error: str | None = None

    def start(self) -> None:
        with self._lock:
            if self._scheduler_thread and self._scheduler_thread.is_alive():
                return
            self._stop_event.clear()
            self._scheduler_thread = threading.Thread(
                target=self._run_scheduler,
                name="finbot-autonomous-scheduler",
                daemon=True,
            )
            self._scheduler_thread.start()

    def stop(self, timeout_seconds: float = 2.0) -> None:
        self._stop_event.set()
        deadline = monotonic() + max(0.0, timeout_seconds)
        for thread in (self._scheduler_thread, self._run_thread):
            if thread and thread.is_alive():
                thread.join(timeout=max(0.0, deadline - monotonic()))

    def trigger_now(self, trigger_type: str = "manual") -> dict[str, Any]:
        config = self.config_loader()
        return self._launch(config, trigger_type)

    def snapshot(self) -> dict[str, Any]:
        try:
            config = self.config_loader()
            enabled = config.enabled
            interval_minutes = config.interval_minutes
            config_error = None
        except Exception as exc:
            enabled = False
            interval_minutes = 0
            config_error = f"{type(exc).__name__}: {exc}"
        with self._lock:
            running = self._run_thread is not None and self._run_thread.is_alive()
            status = "config-error" if config_error else "running" if running else "enabled" if enabled else "disabled"
            return SchedulerSnapshot(
                status=status,
                enabled=enabled,
                interval_minutes=interval_minutes,
                running=running,
                next_run_at=_format_dt(self._next_run_at),
                current_trigger_type=self._current_trigger_type if running else None,
                last_started_at=self._last_started_at,
                last_finished_at=self._last_finished_at,
                last_loop_run_id=self._last_loop_run_id,
                last_error=config_error or self._last_error,
            ).to_dict()

    def _run_scheduler(self) -> None:
        while not self._stop_event.is_set():
            try:
                config = self.config_loader()
                now = datetime.now(timezone.utc)
                if not config.enabled:
                    with self._lock:
                        self._next_run_at = None
                elif self._should_launch(now):
                    self._launch(config, "scheduler")
            except Exception as exc:
                with self._lock:
                    self._last_error = f"{type(exc).__name__}: {exc}"
            self._stop_event.wait(self.poll_seconds)

    def _should_launch(self, now: datetime) -> bool:
        with self._lock:
            if self._run_thread is not None and self._run_thread.is_alive():
                return False
            if self._next_run_at is None:
                return True
            return now >= self._next_run_at

    def _launch(self, config: AutonomousLoopConfig, trigger_type: str) -> dict[str, Any]:
        with self._lock:
            if self._run_thread is not None and self._run_thread.is_alive():
                raise RuntimeError("已有自动循环正在运行")
            started_at = datetime.now(timezone.utc)
            self._current_trigger_type = trigger_type
            self._last_started_at = started_at.isoformat()
            self._last_finished_at = None
            self._last_error = None
            self._run_thread = threading.Thread(
                target=self._run_once,
                args=(config, trigger_type),
                name=f"finbot-autonomous-run-{trigger_type}",
                daemon=True,
            )
            self._run_thread.start()
            return {
                "status": "accepted",
                "trigger_type": trigger_type,
                "started_at": started_at.isoformat(),
            }

    def _run_once(self, config: AutonomousLoopConfig, trigger_type: str) -> None:
        try:
            result = self.runner_factory(config).run(config, trigger_type=trigger_type)
            with self._lock:
                self._last_loop_run_id = result.get("loop_run_id")
                self._last_finished_at = datetime.now(timezone.utc).isoformat()
                self._last_error = result.get("summary", {}).get("first_error")
                self._next_run_at = datetime.now(timezone.utc) + timedelta(minutes=max(1, config.interval_minutes))
                self._current_trigger_type = None
        except Exception as exc:
            with self._lock:
                self._last_finished_at = datetime.now(timezone.utc).isoformat()
                self._last_error = f"{type(exc).__name__}: {exc}"
                self._next_run_at = datetime.now(timezone.utc) + timedelta(minutes=max(1, config.interval_minutes))
                self._current_trigger_type = None


def _format_dt(value: datetime | None) -> str | None:
    return value.isoformat() if value else None
