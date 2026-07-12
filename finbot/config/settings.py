from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from finbot.config.paths import runtime_root


@dataclass(frozen=True)
class Settings:
    project_root: Path
    data_dir: Path
    evidence_dir: Path
    reports_dir: Path
    sqlite_path: Path
    http_user_agent: str
    firecrawl_api_base: str
    firecrawl_api_key: str | None
    firecrawl_proxy: str | None
    firecrawl_proxy_pool: str | None
    firecrawl_proxy_file: str | None
    firecrawl_proxy_include_direct: bool
    firecrawl_proxy_ip_family: str
    firecrawl_proxy_dns_mode: str
    firecrawl_vless_subscription_url: str | None
    firecrawl_vless_subscription_file: str | None
    firecrawl_vless_max_nodes: int
    firecrawl_auth_mode: str
    exchange_proxy: str | None
    exchange_proxy_pool: str | None
    exchange_proxy_file: str | None
    exchange_proxy_ip_family: str
    exchange_proxy_dns_mode: str
    exchange_vless_subscription_url: str | None
    exchange_vless_subscription_file: str | None
    exchange_vless_max_nodes: int
    exchange_vless_preferred_node_names: tuple[str, ...]
    exchange_hysteria2_urls: str | None
    exchange_hysteria2_max_nodes: int
    sing_box_path: str
    proxy_runtime_dir: Path
    proxy_policy_file: str | None
    fred_api_key: str | None
    bea_api_key: str | None
    alpha_vantage_api_key: str | None
    openbb_pat: str | None

    @classmethod
    def from_env(cls, project_root: Path | None = None, data_dir: Path | None = None) -> "Settings":
        root = project_root or Path.cwd()
        data = data_dir or root / "data"
        evidence = data / "evidence"
        reports = data / "reports"
        settings = cls(
            project_root=root,
            data_dir=data,
            evidence_dir=evidence,
            reports_dir=reports,
            sqlite_path=data / "finbot.sqlite3",
            http_user_agent=os.getenv("HTTP_USER_AGENT", "FinBot research bot"),
            firecrawl_api_base=os.getenv("FIRECRAWL_API_BASE", "https://api.firecrawl.dev/v2"),
            firecrawl_api_key=os.getenv("FIRECRAWL_API_KEY") or None,
            firecrawl_proxy=os.getenv("FIRECRAWL_PROXY") or None,
            firecrawl_proxy_pool=os.getenv("FIRECRAWL_PROXY_POOL") or None,
            firecrawl_proxy_file=os.getenv("FIRECRAWL_PROXY_FILE") or str(root / "config" / "firecrawl_proxies.txt"),
            firecrawl_proxy_include_direct=os.getenv("FIRECRAWL_PROXY_INCLUDE_DIRECT", "0").strip().lower() not in {"0", "false", "no"},
            firecrawl_proxy_ip_family=os.getenv("FIRECRAWL_PROXY_IP_FAMILY", "ipv4"),
            firecrawl_proxy_dns_mode=os.getenv("FIRECRAWL_PROXY_DNS_MODE", "remote"),
            firecrawl_vless_subscription_url=os.getenv("FIRECRAWL_VLESS_SUBSCRIPTION_URL") or None,
            firecrawl_vless_subscription_file=os.getenv("FIRECRAWL_VLESS_SUBSCRIPTION_FILE") or None,
            firecrawl_vless_max_nodes=_env_int("FIRECRAWL_VLESS_MAX_NODES", 8),
            firecrawl_auth_mode=os.getenv("FIRECRAWL_AUTH_MODE", "bearer" if os.getenv("FIRECRAWL_API_KEY") else "keyless"),
            exchange_proxy=os.getenv("EXCHANGE_PROXY") or None,
            exchange_proxy_pool=os.getenv("EXCHANGE_PROXY_POOL") or None,
            exchange_proxy_file=os.getenv("EXCHANGE_PROXY_FILE")
            or os.getenv("MARKET_PROXY_FILE")
            or None,
            exchange_proxy_ip_family=os.getenv("EXCHANGE_PROXY_IP_FAMILY", "ipv4"),
            exchange_proxy_dns_mode=os.getenv("EXCHANGE_PROXY_DNS_MODE", "remote"),
            exchange_vless_subscription_url=os.getenv("EXCHANGE_VLESS_SUBSCRIPTION_URL") or os.getenv("CF_VLESS_SUBSCRIPTION_URL") or None,
            exchange_vless_subscription_file=os.getenv("EXCHANGE_VLESS_SUBSCRIPTION_FILE") or os.getenv("CF_VLESS_SUBSCRIPTION_FILE") or None,
            exchange_vless_max_nodes=_env_int("EXCHANGE_VLESS_MAX_NODES", _env_int("CF_VLESS_MAX_NODES", 2)),
            exchange_vless_preferred_node_names=_env_list("EXCHANGE_VLESS_PREFERRED_NODE_NAMES"),
            exchange_hysteria2_urls=os.getenv("EXCHANGE_HYSTERIA2_URLS") or None,
            exchange_hysteria2_max_nodes=_env_int("EXCHANGE_HYSTERIA2_MAX_NODES", 4),
            sing_box_path=os.getenv("SING_BOX_PATH") or _default_sing_box_path(),
            proxy_runtime_dir=Path(os.getenv("PROXY_RUNTIME_DIR") or data / "runtime" / "proxy"),
            proxy_policy_file=os.getenv("PROXY_POLICY_FILE") or str(root / "config" / "proxy_policy.json"),
            fred_api_key=os.getenv("FRED_API_KEY") or None,
            bea_api_key=os.getenv("BEA_API_KEY") or None,
            alpha_vantage_api_key=os.getenv("ALPHA_VANTAGE_API_KEY") or None,
            openbb_pat=os.getenv("OPENBB_PAT") or None,
        )
        from finbot.config.runtime_config import RuntimeConfigStore

        return RuntimeConfigStore(runtime_root(root)).apply_to_settings(settings)

    def ensure_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        self.reports_dir.mkdir(parents=True, exist_ok=True)
        self.proxy_runtime_dir.mkdir(parents=True, exist_ok=True)


def _env_int(key: str, default: int) -> int:
    raw_value = os.getenv(key)
    if raw_value is None or not raw_value.strip():
        return default
    try:
        return int(raw_value)
    except ValueError:
        return default


def _default_sing_box_path() -> str:
    if os.name == "nt":
        return r"D:\DevlopTools\sing-box\1.13.14\sing-box.exe"
    return "/usr/local/bin/sing-box"


def _env_list(key: str) -> tuple[str, ...]:
    raw_value = os.getenv(key, "")
    return tuple(item.strip() for item in raw_value.split(",") if item.strip())
