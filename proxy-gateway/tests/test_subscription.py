from __future__ import annotations

import base64

from finbot_proxy.models import Hysteria2Node, VlessNode
from finbot_proxy.subscription import parse_subscription, select_nodes


def test_parses_base64_vless_and_hysteria2_and_prioritizes_names() -> None:
    raw = "\n".join(
        (
            "vless://00000000-0000-0000-0000-000000000001@example.com:443"
            "?security=tls&type=ws&host=edge.example.com&path=%2Fws&sni=edge.example.com#SG-1",
            "hysteria2://password@hy.example.com:443?sni=hy.example.com"
            "&obfs=salamander&obfs-password=secret#JP-1",
        )
    )
    encoded = base64.b64encode(raw.encode()).decode()
    subscription = parse_subscription(encoded)
    selected = select_nodes(subscription, maximum_nodes=2, preferred_names=("jp",))

    assert subscription.invalid_node_count == 0
    assert isinstance(selected[0], Hysteria2Node)
    assert isinstance(selected[1], VlessNode)


def test_rejects_unsupported_nodes_without_leaking_values() -> None:
    subscription = parse_subscription("trojan://secret@example.com:443\nnot-a-url")

    assert subscription.nodes == ()
    assert subscription.invalid_node_count == 2
