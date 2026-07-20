from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlsplit, urlunsplit


@dataclass(frozen=True, slots=True)
class BrowserWorkerSettings:
    proxy_url: str
    maximum_concurrent_solves: int = 2
    acquire_timeout_ms: int = 5_000

    def __post_init__(self) -> None:
        object.__setattr__(self, "proxy_url", _validated_proxy_url(self.proxy_url))
        if not 1 <= self.maximum_concurrent_solves <= 16:
            raise ValueError("maximum_concurrent_solves must be between 1 and 16")
        if not 100 <= self.acquire_timeout_ms <= 60_000:
            raise ValueError("acquire_timeout_ms must be between 100 and 60000")

    @classmethod
    def from_environment(cls) -> BrowserWorkerSettings:
        proxy_url = os.getenv("FINBOT_BROWSER_WORKER_PROXY_URL", "").strip()
        if not proxy_url:
            raise RuntimeError("FINBOT_BROWSER_WORKER_PROXY_URL is required")
        return cls(
            proxy_url=proxy_url,
            maximum_concurrent_solves=_integer(
                "FINBOT_BROWSER_WORKER_MAX_CONCURRENT_SOLVES",
                default=2,
            ),
            acquire_timeout_ms=_integer(
                "FINBOT_BROWSER_WORKER_ACQUIRE_TIMEOUT_MS",
                default=5_000,
            ),
        )

    @property
    def proxy_origin(self) -> str:
        parsed = urlsplit(self.proxy_url)
        return urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))


def _integer(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError as error:
        raise RuntimeError(f"{name} must be an integer") from error


def _validated_proxy_url(value: str) -> str:
    normalized = value.strip()
    parsed = urlsplit(normalized)
    if parsed.scheme not in {"http", "https", "socks5"}:
        raise ValueError("proxy_url must use http, https, or socks5")
    if parsed.hostname is None:
        raise ValueError("proxy_url must include a host")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("proxy_url must not contain inline credentials")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValueError("proxy_url must not contain a path, query, or fragment")
    try:
        parsed.port
    except ValueError as error:
        raise ValueError("proxy_url contains an invalid port") from error
    return urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))
