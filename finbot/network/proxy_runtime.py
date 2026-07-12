from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from finbot.config.settings import Settings
from finbot.network.hysteria2 import Hysteria2Node, parse_hysteria2_urls
from finbot.network.proxy_pool import ProxyPool
from finbot.network.proxy_policy import LoadedProxyPolicy, load_proxy_policy
from finbot.network.proxy_router import ProxyRouter
from finbot.network.sing_box_bridge import SingBoxBridgeConfig, SingBoxBridgeManager
from finbot.network.vless_subscription import VlessSubscription, load_vless_subscription, prioritize_vless_nodes


@dataclass
class ProxyRuntime:
    router: ProxyRouter
    firecrawl_vless: VlessSubscription | None = None
    exchange_vless: VlessSubscription | None = None
    exchange_hysteria2: tuple[Hysteria2Node, ...] = ()
    proxy_policy: LoadedProxyPolicy = LoadedProxyPolicy()
    _bridges: list[SingBoxBridgeManager] = field(default_factory=list)

    @classmethod
    def from_settings(
        cls,
        settings: Settings,
        start_bridges: bool = True,
        *,
        start_firecrawl_bridges: bool | None = None,
        start_exchange_bridges: bool | None = None,
    ) -> "ProxyRuntime":
        start_firecrawl = start_bridges if start_firecrawl_bridges is None else start_firecrawl_bridges
        start_exchange = start_bridges if start_exchange_bridges is None else start_exchange_bridges
        firecrawl_proxies = _configured_proxies(
            settings.firecrawl_proxy,
            settings.firecrawl_proxy_pool,
            settings.firecrawl_proxy_file,
        )
        exchange_proxies = _configured_proxies(
            settings.exchange_proxy,
            settings.exchange_proxy_pool,
            settings.exchange_proxy_file,
        )
        exchange_family = settings.exchange_proxy_ip_family
        bridges: list[SingBoxBridgeManager] = []
        proxy_policy = load_proxy_policy(settings.proxy_policy_file)

        firecrawl_vless = (
            _load_vless(settings.firecrawl_vless_subscription_url, settings.firecrawl_vless_subscription_file)
            if start_firecrawl
            else None
        )
        firecrawl_family = settings.firecrawl_proxy_ip_family
        if start_firecrawl and firecrawl_vless and firecrawl_vless.nodes:
            manager = SingBoxBridgeManager(
                firecrawl_vless.nodes,
                SingBoxBridgeConfig(
                    binary_path=settings.sing_box_path,
                    work_dir=settings.proxy_runtime_dir / "firecrawl-sing-box",
                    max_nodes=settings.firecrawl_vless_max_nodes,
                ),
            )
            firecrawl_proxies.extend(manager.start())
            bridges.append(manager)
            firecrawl_family = "ipv4"

        exchange_hysteria2 = parse_hysteria2_urls(settings.exchange_hysteria2_urls) if start_exchange else ()
        if start_exchange and exchange_hysteria2:
            manager = SingBoxBridgeManager(
                exchange_hysteria2,
                SingBoxBridgeConfig(
                    binary_path=settings.sing_box_path,
                    work_dir=settings.proxy_runtime_dir / "exchange-hysteria2",
                    max_nodes=settings.exchange_hysteria2_max_nodes,
                ),
            )
            hysteria2_proxies = manager.start()
            exchange_proxies = [*hysteria2_proxies, *exchange_proxies]
            bridges.append(manager)
            if hysteria2_proxies:
                exchange_family = "ipv4"

        exchange_vless = (
            _load_vless(settings.exchange_vless_subscription_url, settings.exchange_vless_subscription_file)
            if start_exchange
            else None
        )
        if exchange_vless:
            exchange_vless = prioritize_vless_nodes(
                exchange_vless,
                settings.exchange_vless_preferred_node_names,
            )
        if start_exchange and exchange_vless and exchange_vless.nodes:
            manager = SingBoxBridgeManager(
                exchange_vless.nodes,
                SingBoxBridgeConfig(
                    binary_path=settings.sing_box_path,
                    work_dir=settings.proxy_runtime_dir / "exchange-sing-box",
                    max_nodes=settings.exchange_vless_max_nodes,
                ),
            )
            exchange_proxies.extend(manager.start())
            bridges.append(manager)
            exchange_family = "ipv4"

        router = ProxyRouter.from_pools(
            firecrawl_pool=ProxyPool(firecrawl_proxies, include_direct=False),
            exchange_pool=ProxyPool(exchange_proxies, include_direct=False),
            firecrawl_proxy_ip_family=firecrawl_family,
            firecrawl_dns_mode=settings.firecrawl_proxy_dns_mode,
            exchange_proxy_ip_family=exchange_family,
            exchange_dns_mode=settings.exchange_proxy_dns_mode,
            exchange_allow_direct=proxy_policy.exchange_allow_direct,
            exchange_provider_overrides=proxy_policy.exchange_provider_overrides,
        )
        return cls(
            router=router,
            firecrawl_vless=firecrawl_vless,
            exchange_vless=exchange_vless,
            exchange_hysteria2=exchange_hysteria2,
            proxy_policy=proxy_policy,
            _bridges=bridges,
        )

    def close(self) -> None:
        for bridge in self._bridges:
            bridge.close()
        self._bridges.clear()

    def summary(self) -> dict[str, Any]:
        return {
            "router": self.router.snapshot(),
            "firecrawl_vless": self.firecrawl_vless.summary() if self.firecrawl_vless else None,
            "exchange_vless": self.exchange_vless.summary() if self.exchange_vless else None,
            "exchange_hysteria2": {
                "node_count": len(self.exchange_hysteria2),
                "sample_nodes": [node.redacted() for node in self.exchange_hysteria2[:3]],
            }
            if self.exchange_hysteria2
            else None,
            "proxy_policy": self.proxy_policy.summary(),
            "bridges": [bridge.summary() for bridge in self._bridges],
        }


def _configured_proxies(single_proxy: str | None, pool_value: str | None, pool_file: str | None) -> list[str]:
    pool = ProxyPool.from_values(single_proxy=single_proxy, pool_value=pool_value, pool_file=pool_file, include_direct=False)
    return list(pool.proxies)


def _load_vless(url: str | None, file: str | None) -> VlessSubscription | None:
    if not url and not file:
        return None
    return load_vless_subscription(url=url, file=file)
