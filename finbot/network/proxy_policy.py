from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


EXCHANGE_PROVIDERS = ("binance", "bybit", "gate")


@dataclass(frozen=True)
class LoadedProxyPolicy:
    source: str | None = None
    exchange_allow_direct: bool = False
    exchange_provider_overrides: dict[str, dict[str, Any]] = field(default_factory=dict)

    def summary(self) -> dict[str, Any]:
        return {
            "source": self.source,
            "exchange_allow_direct": self.exchange_allow_direct,
            "exchange_provider_overrides": self.exchange_provider_overrides,
        }


def load_proxy_policy(policy_file: str | None) -> LoadedProxyPolicy:
    routes, source = _load_policy_file(policy_file)
    exchange_route = _clean_override(routes.get("exchange") or {})
    provider_overrides = {
        route: _clean_override(value)
        for route, value in routes.items()
        if route.startswith("exchange:") and isinstance(value, dict)
    }

    exchange_allow_direct = _bool_value(exchange_route.get("allow_direct"), default=False)
    env_exchange_allow = _env_bool("EXCHANGE_ALLOW_DIRECT")
    if env_exchange_allow is not None:
        exchange_allow_direct = env_exchange_allow

    for provider in EXCHANGE_PROVIDERS:
        env_allow = _env_bool(f"EXCHANGE_{provider.upper()}_ALLOW_DIRECT")
        if env_allow is None:
            continue
        route = f"exchange:{provider}"
        override = dict(provider_overrides.get(route, {}))
        override["allow_direct"] = env_allow
        override.setdefault("description", f"{provider} direct fallback controlled by environment override")
        provider_overrides[route] = override

    return LoadedProxyPolicy(
        source=source,
        exchange_allow_direct=exchange_allow_direct,
        exchange_provider_overrides=provider_overrides,
    )


def _load_policy_file(policy_file: str | None) -> tuple[dict[str, Any], str | None]:
    if not policy_file:
        return {}, None
    path = Path(policy_file)
    if not path.exists():
        return {}, None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError(f"Invalid proxy policy JSON: {path}: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError(f"Invalid proxy policy JSON: {path}: root must be an object")
    routes = payload.get("routes") or {}
    if not isinstance(routes, dict):
        raise ValueError(f"Invalid proxy policy JSON: {path}: routes must be an object")
    return routes, str(path)


def _clean_override(value: dict[str, Any]) -> dict[str, Any]:
    allowed_keys = {
        "require_proxy",
        "allow_direct",
        "proxy_ip_family",
        "dns_mode",
        "allowed_ip_families",
        "description",
        "notes",
    }
    return {key: item for key, item in value.items() if key in allowed_keys}


def _env_bool(key: str) -> bool | None:
    raw_value = os.getenv(key)
    if raw_value is None or not raw_value.strip():
        return None
    return _bool_value(raw_value, default=False)


def _bool_value(value: Any, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "y", "on"}
    return default
