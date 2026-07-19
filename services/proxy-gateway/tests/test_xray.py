from __future__ import annotations

import json

import pytest

from finbot_proxy.models import Hysteria2Node, VlessNode
from finbot_proxy.xray import build_configuration


def test_generates_isolated_http_inbound_for_reality_node() -> None:
    node = VlessNode(
        address="198.51.100.10",
        port=8443,
        uuid="00000000-0000-0000-0000-000000000002",
        flow="xtls-rprx-vision",
        security="reality",
        transport="tcp",
        server_name="www.amazon.com",
        host=None,
        path="/",
        service_name=None,
        fingerprint="chrome",
        reality_public_key="test-public-key",
        reality_short_id="0123456789abcdef",
        insecure=False,
        name="VLESS-1",
    )

    result = json.loads(build_configuration((node,), listen_port_start=10000))

    assert result["inbounds"] == [
        {
            "listen": "127.0.0.1",
            "port": 10000,
            "protocol": "http",
            "tag": "node-in-0",
        }
    ]
    outbound = result["outbounds"][0]
    assert outbound["settings"]["vnext"][0]["users"][0]["flow"] == "xtls-rprx-vision"
    assert outbound["streamSettings"]["realitySettings"] == {
        "serverName": "www.amazon.com",
        "fingerprint": "chrome",
        "publicKey": "test-public-key",
        "shortId": "0123456789abcdef",
    }
    assert result["routing"]["rules"][0]["outboundTag"] == "proxy-0"


def test_rejects_hysteria2_for_xray_engine() -> None:
    node = Hysteria2Node(
        address="hy.example.com",
        port=443,
        password="secret",
        server_ports=("443",),
        hop_interval="30s",
        server_name="hy.example.com",
        insecure=False,
        obfs_type=None,
        obfs_password=None,
        name="HY2",
    )

    with pytest.raises(ValueError, match="supports VLESS nodes only"):
        build_configuration((node,), listen_port_start=10000)
