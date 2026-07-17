from __future__ import annotations

import base64
import re
import urllib.request
from collections.abc import Iterable
from urllib.parse import parse_qs, unquote, urlsplit

from finbot_proxy.models import (
    Hysteria2Node,
    NodeSelection,
    ProxyNode,
    Subscription,
    VlessNode,
)

MAX_SUBSCRIPTION_BYTES = 5_000_000
MAX_SUBSCRIPTION_NODES = 10_000


class SecureRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(  # type: ignore[no-untyped-def]
        self, request, file_pointer, code, message, headers, new_url
    ):
        _validate_subscription_url(new_url)
        return super().redirect_request(
            request, file_pointer, code, message, headers, new_url
        )


def load_subscription(url: str, *, timeout_seconds: float) -> Subscription:
    _validate_subscription_url(url)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "FinBot proxy gateway/2.0"},
    )
    opener = urllib.request.build_opener(SecureRedirectHandler())
    with opener.open(request, timeout=timeout_seconds) as response:
        _validate_subscription_url(response.geturl())
        payload = response.read(MAX_SUBSCRIPTION_BYTES + 1)
    if len(payload) > MAX_SUBSCRIPTION_BYTES:
        raise ValueError("Proxy subscription exceeds 5 MB")
    return parse_subscription(payload.decode("utf-8", errors="replace"))


def parse_subscription(raw: str) -> Subscription:
    decoded = _decode_subscription(raw)
    nodes: list[ProxyNode] = []
    seen: set[ProxyNode] = set()
    invalid = 0
    for line in decoded.splitlines():
        value = line.strip()
        if not value or value.startswith("#"):
            continue
        try:
            node = _parse_node(value)
        except (TypeError, ValueError):
            invalid += 1
            continue
        if node in seen:
            continue
        seen.add(node)
        nodes.append(node)
        if len(nodes) > MAX_SUBSCRIPTION_NODES:
            raise ValueError("Proxy subscription exceeds 10000 unique nodes")
    return Subscription(tuple(nodes), invalid)


def select_nodes(
    subscription: Subscription,
    *,
    maximum_nodes: int,
    preferred_names: Iterable[str],
    allow_insecure_tls: bool = False,
    selection_offset: int = 0,
) -> NodeSelection:
    preferences = tuple(name.strip().casefold() for name in preferred_names if name.strip())
    supported = tuple(node for node in subscription.nodes if _is_supported(node))
    insecure_node_count = sum(1 for node in supported if node.insecure)
    if not allow_insecure_tls:
        supported = tuple(node for node in supported if not node.insecure)
    if preferences:
        preferred = tuple(
            node
            for node in supported
            if any(pattern in (node.name or "").casefold() for pattern in preferences)
        )
        remaining = tuple(node for node in supported if node not in preferred)
        supported = (*preferred, *remaining)
    eligible_node_count = len(supported)
    normalized_offset = selection_offset % eligible_node_count if eligible_node_count else 0
    if normalized_offset:
        supported = (*supported[normalized_offset:], *supported[:normalized_offset])
    return NodeSelection(
        nodes=supported[:maximum_nodes],
        insecure_node_count=insecure_node_count,
        rejected_insecure_node_count=0 if allow_insecure_tls else insecure_node_count,
        eligible_node_count=eligible_node_count,
        selection_offset=normalized_offset,
    )


def _parse_node(value: str) -> ProxyNode:
    scheme = urlsplit(value).scheme.lower()
    if scheme == "vless":
        return _parse_vless(value)
    if scheme in {"hysteria2", "hy2"}:
        return _parse_hysteria2(value)
    raise ValueError("Unsupported proxy protocol")


def _parse_vless(value: str) -> VlessNode:
    parsed = urlsplit(value)
    if not parsed.username or not parsed.hostname:
        raise ValueError("VLESS link must include UUID and host")
    query = parse_qs(parsed.query, keep_blank_values=True)
    security = _first(query, "security", "none").lower()
    transport = _first(query, "type", "tcp").lower()
    host = _optional(query, "host")
    server_name = _first(query, "sni", host or parsed.hostname)
    path = unquote(_first(query, "path", "/")) or "/"
    if not path.startswith("/"):
        path = "/" + path
    return VlessNode(
        address=parsed.hostname,
        port=int(parsed.port or (443 if security in {"tls", "reality"} else 80)),
        uuid=unquote(parsed.username),
        security=security,
        transport=transport,
        server_name=server_name,
        host=host,
        path=path,
        service_name=_optional(query, "serviceName") or _optional(query, "service_name"),
        fingerprint=_first(query, "fp", "chrome"),
        reality_public_key=_optional(query, "pbk"),
        reality_short_id=_optional(query, "sid"),
        insecure=_truthy(_first(query, "insecure", "0"))
        or _truthy(_first(query, "allowInsecure", "0")),
        name=unquote(parsed.fragment) if parsed.fragment else None,
    )


def _parse_hysteria2(value: str) -> Hysteria2Node:
    parsed = urlsplit(value)
    if not parsed.hostname or not parsed.username:
        raise ValueError("Hysteria2 link must include password and host")
    query = parse_qs(parsed.query, keep_blank_values=True)
    port = int(parsed.port or 443)
    obfs_type = _optional(query, "obfs")
    obfs_password = _optional(query, "obfs-password")
    if obfs_type and not obfs_password:
        raise ValueError("Hysteria2 obfs requires a password")
    return Hysteria2Node(
        address=parsed.hostname,
        port=port,
        password=unquote(parsed.username),
        server_ports=_server_ports(_first(query, "mport", ""), port),
        hop_interval=_first(query, "hop-interval", "30s") or "30s",
        server_name=_first(query, "sni", parsed.hostname),
        insecure=_truthy(_first(query, "insecure", "0"))
        or _truthy(_first(query, "allowInsecure", "0")),
        obfs_type=obfs_type,
        obfs_password=unquote(obfs_password) if obfs_password else None,
        name=unquote(parsed.fragment) if parsed.fragment else None,
    )


def _is_supported(node: ProxyNode) -> bool:
    if isinstance(node, Hysteria2Node):
        return True
    if node.security not in {"none", "tls", "reality"}:
        return False
    if node.transport not in {"tcp", "ws", "grpc"}:
        return False
    return node.security != "reality" or bool(node.reality_public_key)


def _decode_subscription(raw: str) -> str:
    if re.search(r"(?m)^(?:vless|hysteria2|hy2)://", raw):
        return raw
    compact = "".join(raw.strip().split())
    try:
        payload = base64.b64decode(compact + "=" * (-len(compact) % 4), validate=False)
        decoded = payload.decode("utf-8", errors="replace")
        return decoded if "://" in decoded else raw
    except (ValueError, UnicodeError):
        return raw


def _server_ports(raw: str, fallback: int) -> tuple[str, ...]:
    if not raw:
        return (str(fallback),)
    result: list[str] = []
    for item in raw.split(","):
        value = item.strip().replace("-", ":")
        if not value:
            continue
        parts = value.split(":", 1)
        ports = tuple(int(part) for part in parts)
        if any(port < 1 or port > 65535 for port in ports):
            raise ValueError("Hysteria2 port is out of range")
        if len(ports) == 2 and ports[0] > ports[1]:
            raise ValueError("Hysteria2 port range is reversed")
        result.append(":".join(str(port) for port in ports))
    if not result:
        raise ValueError("Hysteria2 mport is empty")
    return tuple(result)


def _first(values: dict[str, list[str]], name: str, default: str) -> str:
    candidates = values.get(name)
    return candidates[0] if candidates else default


def _optional(values: dict[str, list[str]], name: str) -> str | None:
    result = _first(values, name, "").strip()
    return result or None


def _truthy(value: str) -> bool:
    return value.strip().casefold() in {"1", "true", "yes", "on"}


def _validate_subscription_url(url: str) -> None:
    parsed = urlsplit(url)
    if parsed.scheme == "https" and parsed.hostname:
        return
    if parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}:
        return
    raise ValueError("Proxy subscription must use HTTPS")
