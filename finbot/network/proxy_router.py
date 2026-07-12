from __future__ import annotations

import socket
from dataclasses import asdict, dataclass
from typing import Any
from urllib.parse import urlsplit

from finbot.network.proxy_pool import ProxyPool


PROXY_IP_FAMILIES = {"ipv4", "ipv6", "dualstack", "unknown"}


@dataclass(frozen=True)
class ProxyRoutePolicy:
    route: str
    require_proxy: bool
    allow_direct: bool
    proxy_ip_family: str
    dns_mode: str
    allowed_ip_families: tuple[str, ...]
    description: str

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["allowed_ip_families"] = list(self.allowed_ip_families)
        return value


@dataclass(frozen=True)
class ProxyRouteDecision:
    route: str
    target_url: str
    target_host: str | None
    status: str
    proxy: str | None
    proxy_redacted: str
    proxy_pool_size: int
    proxy_ip_family: str
    dns_mode: str
    reason: str | None
    policy: dict[str, Any]

    @property
    def ok(self) -> bool:
        return self.status == "ok"

    def to_dict(self) -> dict[str, Any]:
        return {
            "route": self.route,
            "target_url": self.target_url,
            "target_host": self.target_host,
            "status": self.status,
            "proxy": self.proxy_redacted,
            "proxy_pool_size": self.proxy_pool_size,
            "proxy_ip_family": self.proxy_ip_family,
            "dns_mode": self.dns_mode,
            "reason": self.reason,
            "policy": self.policy,
        }


class ProxyRouteBlocked(RuntimeError):
    def __init__(self, decision: ProxyRouteDecision):
        self.decision = decision
        super().__init__(decision.reason or "proxy route blocked")


@dataclass(frozen=True)
class _RouteConfig:
    policy: ProxyRoutePolicy
    pool: ProxyPool
    overrides: dict[str, dict[str, Any]] | None = None


class ProxyRouter:
    def __init__(self, routes: dict[str, _RouteConfig]):
        self._routes = routes

    @classmethod
    def from_settings(cls, settings: Any) -> "ProxyRouter":
        firecrawl_pool = ProxyPool.from_values(
            single_proxy=settings.firecrawl_proxy,
            pool_value=settings.firecrawl_proxy_pool,
            pool_file=settings.firecrawl_proxy_file,
            include_direct=False,
        )
        exchange_pool = ProxyPool.from_values(
            single_proxy=settings.exchange_proxy,
            pool_value=settings.exchange_proxy_pool,
            pool_file=settings.exchange_proxy_file,
            include_direct=False,
        )
        return cls.from_pools(
            firecrawl_pool=firecrawl_pool,
            exchange_pool=exchange_pool,
            firecrawl_proxy_ip_family=settings.firecrawl_proxy_ip_family,
            firecrawl_dns_mode=settings.firecrawl_proxy_dns_mode,
            exchange_proxy_ip_family=settings.exchange_proxy_ip_family,
            exchange_dns_mode=settings.exchange_proxy_dns_mode,
        )

    @classmethod
    def from_pools(
        cls,
        firecrawl_pool: ProxyPool | None = None,
        exchange_pool: ProxyPool | None = None,
        firecrawl_proxy_ip_family: str = "ipv4",
        firecrawl_dns_mode: str = "remote",
        exchange_proxy_ip_family: str = "ipv4",
        exchange_dns_mode: str = "remote",
        exchange_allow_direct: bool = False,
        exchange_provider_overrides: dict[str, dict[str, Any]] | None = None,
    ) -> "ProxyRouter":
        return cls(
            {
                "firecrawl": _RouteConfig(
                    policy=ProxyRoutePolicy(
                        route="firecrawl",
                        require_proxy=True,
                        allow_direct=False,
                        proxy_ip_family=_clean_family(firecrawl_proxy_ip_family, "ipv4"),
                        dns_mode=_clean_dns_mode(firecrawl_dns_mode),
                        allowed_ip_families=("ipv4", "dualstack"),
                        description="Firecrawl keyless must use the configured IPv4-capable proxy pool and never fall back to direct.",
                    ),
                    pool=firecrawl_pool or ProxyPool([], include_direct=False),
                ),
                "exchange": _RouteConfig(
                    policy=ProxyRoutePolicy(
                        route="exchange",
                        require_proxy=True,
                        allow_direct=exchange_allow_direct,
                        proxy_ip_family=_clean_family(exchange_proxy_ip_family, "ipv4"),
                        dns_mode=_clean_dns_mode(exchange_dns_mode),
                        allowed_ip_families=("ipv4", "dualstack"),
                        description="Exchange public market data must use an IPv4 or dualstack-capable proxy.",
                    ),
                    pool=exchange_pool or ProxyPool([], include_direct=False),
                    overrides=exchange_provider_overrides or {},
                ),
            }
        )

    @classmethod
    def direct_for_tests(cls) -> "ProxyRouter":
        return cls(
            {
                "firecrawl": _RouteConfig(
                    policy=ProxyRoutePolicy(
                        route="firecrawl",
                        require_proxy=False,
                        allow_direct=True,
                        proxy_ip_family="unknown",
                        dns_mode="local",
                        allowed_ip_families=("ipv4", "ipv6", "dualstack", "unknown"),
                        description="Test-only direct route.",
                    ),
                    pool=ProxyPool([], include_direct=True),
                ),
                "exchange": _RouteConfig(
                    policy=ProxyRoutePolicy(
                        route="exchange",
                        require_proxy=False,
                        allow_direct=True,
                        proxy_ip_family="unknown",
                        dns_mode="local",
                        allowed_ip_families=("ipv4", "ipv6", "dualstack", "unknown"),
                        description="Test-only direct route.",
                    ),
                    pool=ProxyPool([], include_direct=True),
                ),
            }
        )

    def has_proxy(self, route: str) -> bool:
        config = self._route_config(route)
        return config.pool.has_proxy

    def pool_size(self, route: str) -> int:
        return self._route_config(route).pool.size

    def redacted(self, proxy: str | None, route: str = "firecrawl") -> str:
        return self._route_config(route).pool.redacted(proxy)

    def report_success(self, route: str, proxy: str | None) -> None:
        self._route_config(route).pool.report_success(proxy)

    def report_failure(self, route: str, proxy: str | None, error_type: str) -> None:
        self._route_config(route).pool.report_failure(proxy, error_type)

    def decide(self, route: str, target_url: str) -> ProxyRouteDecision:
        decisions = self.candidate_decisions(route, target_url, attempts=1)
        return decisions[0]

    def candidate_decisions(self, route: str, target_url: str, attempts: int | None = None) -> list[ProxyRouteDecision]:
        config = self._route_config(route)
        policy = self._effective_policy(route, config)
        candidates = config.pool.candidates(attempts=attempts or max(1, config.pool.size))
        if not candidates:
            if policy.allow_direct:
                return [self._direct_decision(route, target_url, config, policy, "direct fallback explicitly allowed; no proxy candidate configured")]
            return [self._blocked_decision(route, target_url, config, "no proxy candidate configured", policy=policy)]
        decisions = [self._decision_for_candidate(route, target_url, config, proxy, policy) for proxy in candidates]
        if policy.allow_direct:
            reason = (
                "direct fallback explicitly allowed; evaluated after proxy candidates"
                if any(decision.ok for decision in decisions)
                else "direct fallback explicitly allowed; proxy candidates are unusable"
            )
            decisions.append(self._direct_decision(route, target_url, config, policy, reason))
        return decisions

    def snapshot(self) -> dict[str, Any]:
        return {
            route: {
                "policy": config.policy.to_dict(),
                "proxy_pool_size": config.pool.size,
                "proxies": [config.pool.redacted(proxy) for proxy in config.pool.proxies],
                "health": config.pool.health_summary(),
                "overrides": config.overrides or {},
            }
            for route, config in self._routes.items()
        }

    def diagnose_targets(self, targets: dict[str, str]) -> dict[str, Any]:
        rows = []
        for route, url in targets.items():
            decision = self.decide(route, url)
            rows.append(
                {
                    "route": route,
                    "target_url": url,
                    "decision": decision.to_dict(),
                    "dns": _resolve_host(decision.target_host),
                }
            )
        return {"routes": self.snapshot(), "targets": rows}

    def _route_config(self, route: str) -> _RouteConfig:
        key = "exchange" if route.startswith("exchange") else route
        if key not in self._routes:
            raise KeyError(f"Unsupported proxy route: {route}")
        return self._routes[key]

    def _effective_policy(self, route: str, config: _RouteConfig) -> ProxyRoutePolicy:
        policy = config.policy
        overrides = config.overrides or {}
        override = overrides.get(route) or overrides.get(route.split(":", 1)[1] if ":" in route else route) or {}
        if not override:
            return policy
        return ProxyRoutePolicy(
            route=policy.route,
            require_proxy=_clean_bool(override.get("require_proxy"), policy.require_proxy),
            allow_direct=_clean_bool(override.get("allow_direct"), policy.allow_direct),
            proxy_ip_family=_clean_family(str(override.get("proxy_ip_family", policy.proxy_ip_family)), policy.proxy_ip_family),
            dns_mode=_clean_dns_mode(str(override.get("dns_mode", policy.dns_mode))),
            allowed_ip_families=_clean_allowed_families(override.get("allowed_ip_families"), policy.allowed_ip_families),
            description=str(override.get("description", policy.description)),
        )

    def _decision_for_candidate(
        self,
        route: str,
        target_url: str,
        config: _RouteConfig,
        proxy: str | None,
        policy: ProxyRoutePolicy,
    ) -> ProxyRouteDecision:
        host = _host(target_url)
        if proxy is None:
            if policy.allow_direct and not policy.require_proxy:
                return self._direct_decision(route, target_url, config, policy, "direct route explicitly allowed")
            return self._blocked_decision(route, target_url, config, "direct fallback is disabled for this route", policy=policy)
        if policy.proxy_ip_family not in policy.allowed_ip_families:
            allowed = ", ".join(policy.allowed_ip_families)
            return self._blocked_decision(
                route,
                target_url,
                config,
                f"proxy family {policy.proxy_ip_family} is not allowed for {route}; allowed: {allowed}",
                proxy,
                policy=policy,
            )
        return ProxyRouteDecision(
            route=route,
            target_url=target_url,
            target_host=host,
            status="ok",
            proxy=proxy,
            proxy_redacted=config.pool.redacted(proxy),
            proxy_pool_size=config.pool.size,
            proxy_ip_family=policy.proxy_ip_family,
            dns_mode=policy.dns_mode,
            reason=None,
            policy=policy.to_dict(),
        )

    def _direct_decision(self, route: str, target_url: str, config: _RouteConfig, policy: ProxyRoutePolicy, reason: str) -> ProxyRouteDecision:
        return ProxyRouteDecision(
            route=route,
            target_url=target_url,
            target_host=_host(target_url),
            status="ok",
            proxy=None,
            proxy_redacted="direct",
            proxy_pool_size=config.pool.size,
            proxy_ip_family="direct",
            dns_mode="local",
            reason=reason,
            policy=policy.to_dict(),
        )

    def _blocked_decision(
        self,
        route: str,
        target_url: str,
        config: _RouteConfig,
        reason: str,
        proxy: str | None = None,
        policy: ProxyRoutePolicy | None = None,
    ) -> ProxyRouteDecision:
        effective_policy = policy or config.policy
        return ProxyRouteDecision(
            route=route,
            target_url=target_url,
            target_host=_host(target_url),
            status="blocked-by-proxy",
            proxy=None,
            proxy_redacted=config.pool.redacted(proxy),
            proxy_pool_size=config.pool.size,
            proxy_ip_family=effective_policy.proxy_ip_family,
            dns_mode=effective_policy.dns_mode,
            reason=reason,
            policy=effective_policy.to_dict(),
        )


def _clean_family(value: str | None, default: str) -> str:
    clean = (value or default).strip().lower()
    return clean if clean in PROXY_IP_FAMILIES else default


def _clean_dns_mode(value: str | None) -> str:
    clean = (value or "local").strip().lower()
    return clean if clean in {"local", "remote"} else "local"


def _clean_bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        clean = value.strip().lower()
        if clean in {"1", "true", "yes", "y", "on"}:
            return True
        if clean in {"0", "false", "no", "n", "off"}:
            return False
    return default


def _clean_allowed_families(value: Any, default: tuple[str, ...]) -> tuple[str, ...]:
    if value is None:
        return default
    if isinstance(value, str):
        items = [item.strip().lower() for item in value.split(",")]
    elif isinstance(value, (list, tuple, set)):
        items = [str(item).strip().lower() for item in value]
    else:
        return default
    families = tuple(item for item in items if item in PROXY_IP_FAMILIES)
    return families or default


def _host(url: str) -> str | None:
    try:
        return urlsplit(url).hostname
    except Exception:
        return None


def _resolve_host(host: str | None) -> dict[str, Any]:
    if not host:
        return {"status": "skipped", "reason": "missing host", "ipv4": [], "ipv6": []}
    ipv4: set[str] = set()
    ipv6: set[str] = set()
    try:
        for family, _, _, _, sockaddr in socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP):
            if family == socket.AF_INET:
                ipv4.add(sockaddr[0])
            elif family == socket.AF_INET6:
                ipv6.add(sockaddr[0])
    except OSError as exc:
        return {"status": "failed", "reason": str(exc), "ipv4": [], "ipv6": []}
    return {"status": "ok", "ipv4": sorted(ipv4), "ipv6": sorted(ipv6)}
