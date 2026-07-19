from __future__ import annotations

import json
from typing import Any

from finbot_proxy.models import Hysteria2Node, ProxyNode, VlessNode


def build_configuration(
    nodes: tuple[ProxyNode, ...],
    *,
    listen_port_start: int,
) -> str:
    if not nodes:
        raise ValueError("At least one supported proxy node is required")
    if any(isinstance(node, Hysteria2Node) for node in nodes):
        raise ValueError("Xray engine supports VLESS nodes only")

    vless_nodes = tuple(node for node in nodes if isinstance(node, VlessNode))
    configuration: dict[str, Any] = {
        "log": {"loglevel": "warning"},
        "inbounds": [
            {
                "listen": "127.0.0.1",
                "port": listen_port_start + index,
                "protocol": "http",
                "tag": f"node-in-{index}",
            }
            for index in range(len(vless_nodes))
        ],
        "outbounds": [
            _vless_outbound(node, f"proxy-{index}")
            for index, node in enumerate(vless_nodes)
        ],
        "routing": {
            "domainStrategy": "AsIs",
            "rules": [
                {
                    "type": "field",
                    "inboundTag": [f"node-in-{index}"],
                    "outboundTag": f"proxy-{index}",
                }
                for index in range(len(vless_nodes))
            ],
        },
    }
    return json.dumps(configuration, ensure_ascii=True, separators=(",", ":"))


def _vless_outbound(node: VlessNode, tag: str) -> dict[str, Any]:
    user: dict[str, Any] = {
        "id": node.uuid,
        "encryption": "none",
    }
    if node.flow:
        user["flow"] = node.flow
    outbound: dict[str, Any] = {
        "protocol": "vless",
        "tag": tag,
        "settings": {
            "vnext": [
                {
                    "address": node.address,
                    "port": node.port,
                    "users": [user],
                }
            ]
        },
        "streamSettings": _stream_settings(node),
    }
    return outbound


def _stream_settings(node: VlessNode) -> dict[str, Any]:
    settings: dict[str, Any] = {
        "network": "tcp" if node.transport == "tcp" else node.transport,
        "security": node.security,
    }
    if node.security == "tls":
        settings["tlsSettings"] = {
            "serverName": node.server_name,
            "allowInsecure": node.insecure,
            "fingerprint": node.fingerprint,
        }
    elif node.security == "reality":
        settings["realitySettings"] = {
            "serverName": node.server_name,
            "fingerprint": node.fingerprint,
            "publicKey": node.reality_public_key,
            "shortId": node.reality_short_id or "",
        }
    if node.transport == "ws":
        settings["wsSettings"] = {
            "path": node.path,
            "headers": {"Host": node.host or node.server_name},
        }
    elif node.transport == "grpc":
        settings["grpcSettings"] = {
            "serviceName": node.service_name or "",
        }
    return settings
