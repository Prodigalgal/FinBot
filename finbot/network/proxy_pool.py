from __future__ import annotations

import itertools
import re
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit


class ProxyPool:
    """Small round-robin proxy pool for Firecrawl and crawler traffic."""

    def __init__(self, proxies: list[str] | None = None, include_direct: bool = True):
        cleaned = [p.strip().lstrip("\ufeff") for p in proxies or [] if p and p.strip().lstrip("\ufeff")]
        self._proxies = list(dict.fromkeys(cleaned))
        self._include_direct = include_direct
        candidates: list[str | None] = [*self._proxies]
        if include_direct:
            candidates.append(None)
        self._candidates = candidates
        self._cycle = itertools.cycle(self._candidates) if self._candidates else None

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
        if not self._cycle:
            return []
        count = attempts or len(self._candidates)
        return [next(self._cycle) for _ in range(max(1, count))]

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
