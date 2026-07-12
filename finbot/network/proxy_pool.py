from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib.parse import urlsplit, urlunsplit


@dataclass
class _ProxyHealth:
    consecutive_failures: int = 0
    cooldown_until: float = 0.0
    last_error_type: str | None = None


class ProxyPool:
    """Thread-safe round-robin pool with per-candidate exponential cooldown."""

    def __init__(
        self,
        proxies: list[str] | None = None,
        include_direct: bool = True,
        *,
        cooldown_base_seconds: float = 15.0,
        cooldown_max_seconds: float = 300.0,
        clock: Callable[[], float] = time.monotonic,
    ):
        cleaned = [p.strip().lstrip("\ufeff") for p in proxies or [] if p and p.strip().lstrip("\ufeff")]
        self._proxies = list(dict.fromkeys(cleaned))
        self._include_direct = include_direct
        self._cooldown_base_seconds = max(0.0, cooldown_base_seconds)
        self._cooldown_max_seconds = max(self._cooldown_base_seconds, cooldown_max_seconds)
        self._clock = clock
        self._cursor = 0
        self._health = {proxy: _ProxyHealth() for proxy in self._proxies}
        self._lock = threading.Lock()

    @classmethod
    def from_values(
        cls,
        single_proxy: str | None = None,
        pool_value: str | None = None,
        pool_file: str | None = None,
        include_direct: bool = True,
    ) -> "ProxyPool":
        proxies: list[str] = []
        if single_proxy:
            proxies.append(single_proxy)
        if pool_value:
            proxies.extend([item for item in re.split(r"[\r\n,;]+", pool_value) if item.strip()])
        if pool_file:
            path = Path(pool_file)
            if path.exists():
                for line in path.read_text(encoding="utf-8").splitlines():
                    value = line.strip().lstrip("\ufeff")
                    if not value or value.startswith("#"):
                        continue
                    proxies.append(value)
        return cls(proxies=proxies, include_direct=include_direct)

    @property
    def size(self) -> int:
        return len(self._proxies)

    @property
    def proxies(self) -> tuple[str, ...]:
        return tuple(self._proxies)

    @property
    def has_proxy(self) -> bool:
        return bool(self._proxies)

    def candidates(self, attempts: int | None = None) -> list[str | None]:
        with self._lock:
            now = self._clock()
            ordered = self._ordered_proxies()
            available = [proxy for proxy in ordered if self._health[proxy].cooldown_until <= now]
            total = len(available) + (1 if self._include_direct else 0)
            if total == 0:
                return []
            count = min(max(1, attempts or total), total)
            selected: list[str | None] = list(available[:count])
            if self._include_direct and len(selected) < count:
                selected.append(None)
            if self._proxies:
                self._cursor = (self._cursor + 1) % len(self._proxies)
            return selected

    def report_success(self, proxy: str | None) -> None:
        if not proxy:
            return
        with self._lock:
            health = self._health.get(proxy)
            if health is None:
                return
            health.consecutive_failures = 0
            health.cooldown_until = 0.0
            health.last_error_type = None

    def report_failure(self, proxy: str | None, error_type: str) -> None:
        if not proxy:
            return
        with self._lock:
            health = self._health.get(proxy)
            if health is None:
                return
            health.consecutive_failures += 1
            delay = min(
                self._cooldown_base_seconds * (2 ** max(0, health.consecutive_failures - 1)),
                self._cooldown_max_seconds,
            )
            health.cooldown_until = self._clock() + delay
            health.last_error_type = _clean_error_type(error_type)

    def health_summary(self) -> dict[str, object]:
        with self._lock:
            now = self._clock()
            rows = []
            for proxy in self._proxies:
                health = self._health[proxy]
                rows.append(
                    {
                        "proxy": self.redacted(proxy),
                        "status": "cooling-down" if health.cooldown_until > now else "available",
                        "consecutive_failures": health.consecutive_failures,
                        "cooldown_remaining_seconds": round(max(0.0, health.cooldown_until - now), 3),
                        "last_error_type": health.last_error_type,
                    }
                )
            return {
                "available_count": sum(row["status"] == "available" for row in rows),
                "cooling_down_count": sum(row["status"] == "cooling-down" for row in rows),
                "candidates": rows,
            }

    def _ordered_proxies(self) -> list[str]:
        if not self._proxies:
            return []
        return [*self._proxies[self._cursor :], *self._proxies[: self._cursor]]

    def redacted(self, proxy: str | None) -> str:
        if not proxy:
            return "direct"
        try:
            parts = urlsplit(proxy)
            if "@" in parts.netloc:
                host = parts.netloc.rsplit("@", 1)[1]
                return urlunsplit((parts.scheme, f"<redacted>@{host}", parts.path, parts.query, parts.fragment))
            return proxy
        except Exception:
            return "<invalid-proxy>"


def _clean_error_type(value: str) -> str:
    category = re.split(r"[:\s]", str(value).strip(), maxsplit=1)[0]
    clean = re.sub(r"[^a-zA-Z0-9_.-]+", "-", category)[:80]
    return clean or "unknown"
