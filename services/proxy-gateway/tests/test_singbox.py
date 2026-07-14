from __future__ import annotations

import json

from finbot_proxy.models import VlessNode
from finbot_proxy.singbox import build_configuration


def test_generates_one_fail_closed_urltest_pool() -> None:
    node = VlessNode(
        address="example.com",
        port=443,
        uuid="00000000-0000-0000-0000-000000000001",
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
    result = json.loads(build_configuration((node,), listen_port=8080))

    assert result["route"]["final"] == "proxy-pool"
    assert result["inbounds"][0]["listen"] == "0.0.0.0"
    assert result["outbounds"][-1]["outbounds"] == ["proxy-0"]
    assert all(outbound["type"] != "direct" for outbound in result["outbounds"])
