from __future__ import annotations

import json

from finbot_proxy.models import VlessNode
from finbot_proxy.singbox import build_configuration


def test_generates_one_fail_closed_inbound_per_node() -> None:
    node = VlessNode(
        address="example.com",
        port=443,
        uuid="00000000-0000-0000-0000-000000000001",
        flow=None,
        security="tls",
        transport="ws",
        server_name="edge.example.com",
        host="edge.example.com",
        path="/ws",
        service_name=None,
        fingerprint="chrome",
        reality_public_key=None,
        reality_short_id=None,
        insecure=False,
        name="SG",
    )
    result = json.loads(build_configuration((node,), listen_port_start=10000))

    assert result["route"]["final"] == "proxy-0"
    assert result["route"]["rules"] == [
        {
            "inbound": ["node-in-0"],
            "action": "route",
            "outbound": "proxy-0",
        }
    ]
    assert result["inbounds"][0]["listen"] == "127.0.0.1"
    assert result["inbounds"][0]["listen_port"] == 10000
    assert all(outbound["type"] != "urltest" for outbound in result["outbounds"])
    assert all(outbound["type"] != "direct" for outbound in result["outbounds"])


def test_generates_reality_vision_outbound() -> None:
    node = VlessNode(
        address="198.51.100.10",
        port=8443,
        uuid="00000000-0000-0000-0000-000000000002",
        flow="xtls-rprx-vision",
        security="reality",
        transport="tcp",
        server_name="www.microsoft.com",
        host=None,
        path="/",
        service_name=None,
        fingerprint="chrome",
        reality_public_key="test-public-key",
        reality_short_id="0123456789abcdef",
        insecure=False,
        name="VLESS-1",
    )

    outbound = json.loads(build_configuration((node,), listen_port_start=10000))["outbounds"][0]

    assert outbound["flow"] == "xtls-rprx-vision"
    assert outbound["tls"]["server_name"] == "www.microsoft.com"
    assert outbound["tls"]["reality"] == {
        "enabled": True,
        "public_key": "test-public-key",
        "short_id": "0123456789abcdef",
    }
