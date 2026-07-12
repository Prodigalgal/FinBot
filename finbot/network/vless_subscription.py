from __future__ import annotations

import base64
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence
from urllib.parse import parse_qs, unquote, urlsplit


@dataclass(frozen=True)
class VlessNode:
    uuid: str
    address: str
    port: int
    security: str
    transport: str
    host: str | None
    sni: str | None
    path: str
    name: str | None = None

    @property
    def websocket_url(self) -> str:
        scheme = "wss" if self.security == "tls" else "ws"
        host = self.host or self.sni or self.address
        return f"{scheme}://{host}{self.path}"

    @property
    def tls_server_name(self) -> str:
        return self.sni or self.host or self.address

    @property
    def websocket_host_header(self) -> str:
        return self.host or self.sni or self.address

    def redacted(self) -> dict[str, Any]:
        value = asdict(self)
        value["uuid"] = "<redacted>"
        return value


@dataclass(frozen=True)
class VlessSubscription:
    source: str
    nodes: tuple[VlessNode, ...]

    def summary(self) -> dict[str, Any]:
        ports: dict[str, int] = {}
        transports: dict[str, int] = {}
        securities: dict[str, int] = {}
        for node in self.nodes:
            ports[str(node.port)] = ports.get(str(node.port), 0) + 1
            transports[node.transport] = transports.get(node.transport, 0) + 1
            securities[node.security] = securities.get(node.security, 0) + 1
        return {
            "source": self.source,
            "node_count": len(self.nodes),
            "ports": ports,
            "transports": transports,
            "securities": securities,
            "sample_nodes": [node.redacted() for node in self.nodes[:3]],
        }


def load_vless_subscription(url: str | None = None, file: str | None = None, timeout_seconds: float = 20.0) -> VlessSubscription:
    if url:
        request = urllib.request.Request(url, headers={"User-Agent": "FinBot proxy subscription loader"})
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            raw = response.read().decode("utf-8", errors="replace")
        return parse_vless_subscription(raw, source=_redact_url(url))
    if file:
        path = Path(file)
        raw = path.read_text(encoding="utf-8")
        return parse_vless_subscription(raw, source=str(path))
    return VlessSubscription(source="empty", nodes=())


def parse_vless_subscription(raw: str, source: str = "inline") -> VlessSubscription:
    decoded = _decode_subscription(raw)
    nodes = []
    for line in decoded.splitlines():
        clean = line.strip()
        if not clean or not clean.startswith("vless://"):
            continue
        nodes.append(parse_vless_link(clean))
    return VlessSubscription(source=source, nodes=tuple(nodes))


def prioritize_vless_nodes(
    subscription: VlessSubscription,
    preferred_names: Sequence[str],
) -> VlessSubscription:
    priorities: dict[str, int] = {}
    for name in preferred_names:
        normalized = str(name).strip().casefold()
        if normalized:
            priorities.setdefault(normalized, len(priorities))
    if not priorities:
        return subscription

    fallback_priority = len(priorities)
    ordered_nodes = tuple(
        node
        for _, node in sorted(
            enumerate(subscription.nodes),
            key=lambda entry: (
                priorities.get((entry[1].name or "").strip().casefold(), fallback_priority),
                entry[0],
            ),
        )
    )
    return VlessSubscription(source=subscription.source, nodes=ordered_nodes)


def parse_vless_link(link: str) -> VlessNode:
    parsed = urlsplit(link)
    if parsed.scheme != "vless":
        raise ValueError("Only vless:// links are supported")
    if not parsed.username or not parsed.hostname:
        raise ValueError("VLESS link must include uuid and host")
    query = parse_qs(parsed.query, keep_blank_values=True)
    security = _first(query, "security", "none").lower()
    transport = _first(query, "type", "tcp").lower()
    path = unquote(_first(query, "path", "/") or "/")
    if not path.startswith("/"):
        path = f"/{path}"
    return VlessNode(
        uuid=parsed.username,
        address=parsed.hostname,
        port=int(parsed.port or (443 if security == "tls" else 80)),
        security=security,
        transport=transport,
        host=_optional_first(query, "host"),
        sni=_optional_first(query, "sni"),
        path=path,
        name=unquote(parsed.fragment) if parsed.fragment else None,
    )


def _decode_subscription(raw: str) -> str:
    clean = "".join(raw.strip().split())
    if "vless://" in raw:
        return raw
    try:
        padding = "=" * (-len(clean) % 4)
        decoded = base64.b64decode(clean + padding, validate=False)
        text = decoded.decode("utf-8", errors="replace")
        return text if "vless://" in text else raw
    except Exception:
        return raw


def _first(query: dict[str, list[str]], key: str, default: str) -> str:
    values = query.get(key)
    return values[0] if values else default


def _optional_first(query: dict[str, list[str]], key: str) -> str | None:
    value = _first(query, key, "")
    return value or None


def _redact_url(url: str) -> str:
    parsed = urlsplit(url)
    query_items = []
    for item in parsed.query.split("&"):
        if not item:
            continue
        key = item.split("=", 1)[0]
        query_items.append(f"{key}=<redacted>" if key.lower() in {"token", "uuid", "key"} else item)
    query = "&".join(query_items)
    return parsed._replace(query=query).geturl()
