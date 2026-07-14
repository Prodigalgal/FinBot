from __future__ import annotations

import json
from typing import Any

from finbot_proxy.models import Hysteria2Node, ProxyNode, VlessNode


def build_configuration(nodes: tuple[ProxyNode, ...], *, listen_port: int) -> str:
    if not nodes:
        raise ValueError("At least one supported proxy node is required")
    outbounds = [_outbound(node, index) for index, node in enumerate(nodes)]
    tags = [str(outbound["tag"]) for outbound in outbounds]
    outbounds.append(
        {
            "type": "urltest",
            "tag": "proxy-pool",
            "outbounds": tags,
            "url": "https://www.gstatic.com/generate_204",
            "interval": "3m",
            "tolerance": 100,
            "interrupt_exist_connections": False,
        }
    )
    configuration: dict[str, Any] = {
        "log": {"level": "warn", "timestamp": True},
        "inbounds": [
            {
                "type": "mixed",
                "tag": "gateway-in",
                "listen": "0.0.0.0",
                "listen_port": listen_port,
            }
        ],
        "outbounds": outbounds,
        "route": {"final": "proxy-pool", "auto_detect_interface": True},
    }
    return json.dumps(configuration, ensure_ascii=True, separators=(",", ":"))


def _outbound(node: ProxyNode, index: int) -> dict[str, Any]:
    tag = f"proxy-{index}"
    if isinstance(node, Hysteria2Node):
        return _hysteria2_outbound(node, tag)
    return _vless_outbound(node, tag)


def _vless_outbound(node: VlessNode, tag: str) -> dict[str, Any]:
    outbound: dict[str, Any] = {
        "type": "vless",
        "tag": tag,
        "server": node.address,
        "server_port": node.port,
        "uuid": node.uuid,
    }
    if node.security in {"tls", "reality"}:
        tls: dict[str, Any] = {
            "enabled": True,
            "server_name": node.server_name,
            "insecure": node.insecure,
            "utls": {"enabled": True, "fingerprint": node.fingerprint},
        }
        if node.security == "reality":
            tls["reality"] = {
                "enabled": True,
                "public_key": node.reality_public_key,
                "short_id": node.reality_short_id or "",
            }
        outbound["tls"] = tls
    if node.transport == "ws":
        outbound["transport"] = {
            "type": "ws",
            "path": node.path,
            "headers": {"Host": node.host or node.server_name},
        }
    elif node.transport == "grpc":
        outbound["transport"] = {
            "type": "grpc",
            "service_name": node.service_name or "",
        }
    return outbound


def _hysteria2_outbound(node: Hysteria2Node, tag: str) -> dict[str, Any]:
    outbound: dict[str, Any] = {
        "type": "hysteria2",
        "tag": tag,
        "server": node.address,
        "password": node.password,
        "hop_interval": node.hop_interval,
        "tls": {
            "enabled": True,
            "server_name": node.server_name,
            "insecure": node.insecure,
        },
    }
    if len(node.server_ports) == 1 and ":" not in node.server_ports[0]:
        outbound["server_port"] = int(node.server_ports[0])
    else:
        outbound["server_ports"] = list(node.server_ports)
    if node.obfs_type:
        outbound["obfs"] = {
            "type": node.obfs_type,
            "password": node.obfs_password,
        }
    return outbound
