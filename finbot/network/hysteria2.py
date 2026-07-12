from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable
from urllib.parse import parse_qs, unquote, urlsplit


@dataclass(frozen=True)
class Hysteria2Node:
    address: str
    port: int
    password: str
    server_ports: tuple[str, ...]
    hop_interval: str
    sni: str
    insecure: bool
    obfs_type: str | None = None
    obfs_password: str | None = None
    name: str | None = None

    def redacted(self) -> dict[str, Any]:
        return {
            "address": self.address,
            "port": self.port,
            "server_ports": list(self.server_ports),
            "hop_interval": self.hop_interval,
            "sni": self.sni,
            "insecure": self.insecure,
            "obfs_type": self.obfs_type,
            "password": "<redacted>",
            "obfs_password": "<redacted>" if self.obfs_password else None,
            "name": self.name,
        }


def parse_hysteria2_urls(raw_urls: str | Iterable[str] | None) -> tuple[Hysteria2Node, ...]:
    if raw_urls is None:
        return ()
    values = (raw_urls,) if isinstance(raw_urls, str) else tuple(raw_urls)
    links = [line.strip() for value in values for line in str(value).splitlines() if line.strip()]
    return tuple(parse_hysteria2_url(link) for link in links)


def parse_hysteria2_url(link: str) -> Hysteria2Node:
    parsed = urlsplit(link.strip())
    if parsed.scheme.lower() not in {"hysteria2", "hy2"}:
        raise ValueError("Only hysteria2:// or hy2:// links are supported")
    if not parsed.hostname:
        raise ValueError("Hysteria2 link must include a server host")
    password = unquote(parsed.username or "")
    if not password:
        raise ValueError("Hysteria2 link must include a password")

    query = parse_qs(parsed.query, keep_blank_values=True)
    port = int(parsed.port or 443)
    server_ports = _server_ports(_first(query, "mport", ""), port)
    obfs_type = _optional_first(query, "obfs")
    obfs_password = _optional_first(query, "obfs-password")
    if obfs_type and not obfs_password:
        raise ValueError("Hysteria2 obfs requires obfs-password")

    return Hysteria2Node(
        address=parsed.hostname,
        port=port,
        password=password,
        server_ports=server_ports,
        hop_interval=_first(query, "hop-interval", "30s") or "30s",
        sni=_first(query, "sni", parsed.hostname) or parsed.hostname,
        insecure=_truthy(_first(query, "insecure", "0")) or _truthy(_first(query, "allowInsecure", "0")),
        obfs_type=obfs_type,
        obfs_password=unquote(obfs_password) if obfs_password else None,
        name=unquote(parsed.fragment) if parsed.fragment else None,
    )


def _server_ports(raw_value: str, fallback_port: int) -> tuple[str, ...]:
    if not raw_value:
        _validate_port(fallback_port)
        return (str(fallback_port),)
    values = []
    for raw_part in raw_value.split(","):
        part = raw_part.strip()
        if not part:
            continue
        separator = ":" if ":" in part else "-" if "-" in part else None
        if separator:
            start_text, end_text = part.split(separator, 1)
            start = int(start_text)
            end = int(end_text)
            _validate_port(start)
            _validate_port(end)
            if start > end:
                raise ValueError("Hysteria2 port range start must not exceed end")
            values.append(f"{start}:{end}")
        else:
            port = int(part)
            _validate_port(port)
            values.append(str(port))
    if not values:
        raise ValueError("Hysteria2 mport must include at least one port")
    return tuple(values)


def _validate_port(port: int) -> None:
    if port < 1 or port > 65535:
        raise ValueError("Hysteria2 port must be between 1 and 65535")


def _first(query: dict[str, list[str]], key: str, default: str) -> str:
    values = query.get(key)
    return values[0] if values else default


def _optional_first(query: dict[str, list[str]], key: str) -> str | None:
    value = _first(query, key, "")
    return value or None


def _truthy(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}
