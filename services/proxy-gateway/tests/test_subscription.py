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
    selection = select_nodes(subscription, maximum_nodes=2, preferred_names=("jp",))

    assert subscription.invalid_node_count == 0
    assert isinstance(selection.nodes[0], Hysteria2Node)
    assert isinstance(selection.nodes[1], VlessNode)


def test_rejects_unsupported_nodes_without_leaking_values() -> None:
    subscription = parse_subscription("trojan://secret@example.com:443\nnot-a-url")

    assert subscription.nodes == ()
    assert subscription.invalid_node_count == 2


def test_rejects_insecure_tls_nodes_by_default_and_reports_policy_counts() -> None:
    subscription = parse_subscription(
        "hysteria2://password@unsafe.example.com:443?sni=unsafe.example.com&insecure=1#unsafe\n"
        "hysteria2://password@safe.example.com:443?sni=safe.example.com#safe"
    )

    selection = select_nodes(subscription, maximum_nodes=2, preferred_names=())

    assert len(selection.nodes) == 1
    assert selection.nodes[0].address == "safe.example.com"
    assert selection.insecure_node_count == 1
    assert selection.rejected_insecure_node_count == 1
    assert selection.enabled_insecure_node_count == 0


def test_allows_insecure_tls_nodes_only_when_explicitly_enabled() -> None:
    subscription = parse_subscription(
        "hysteria2://password@unsafe.example.com:443?sni=unsafe.example.com&allowInsecure=1"
    )

    selection = select_nodes(
        subscription,
        maximum_nodes=1,
        preferred_names=(),
        allow_insecure_tls=True,
    )

    assert len(selection.nodes) == 1
    assert selection.rejected_insecure_node_count == 0
    assert selection.enabled_insecure_node_count == 1


def test_parses_vless_reality_vision_flow() -> None:
    subscription = parse_subscription(
        "vless://00000000-0000-0000-0000-000000000002@198.51.100.10:8443"
        "?encryption=none&flow=xtls-rprx-vision&security=reality"
        "&sni=www.microsoft.com&fp=chrome&pbk=test-public-key"
        "&sid=0123456789abcdef&type=tcp#VLESS-1"
    )

    assert subscription.invalid_node_count == 0
    assert len(subscription.nodes) == 1
    node = subscription.nodes[0]
    assert isinstance(node, VlessNode)
    assert node.flow == "xtls-rprx-vision"
    assert node.reality_public_key == "test-public-key"
    assert node.reality_short_id == "0123456789abcdef"


def test_rotates_selection_window_across_the_full_eligible_pool() -> None:
    subscription = parse_subscription("\n".join(
        f"hysteria2://password@node-{index}.example.com:443#node-{index}"
        for index in range(5)
    ))

    first = select_nodes(
        subscription,
        maximum_nodes=2,
        preferred_names=(),
        selection_offset=0,
    )
    rotated = select_nodes(
        subscription,
        maximum_nodes=2,
        preferred_names=(),
        selection_offset=2,
    )
    wrapped = select_nodes(
        subscription,
        maximum_nodes=2,
        preferred_names=(),
        selection_offset=4,
    )

    assert first.eligible_node_count == 5
    assert [node.address for node in first.nodes] == [
        "node-0.example.com",
        "node-1.example.com",
    ]
    assert [node.address for node in rotated.nodes] == [
        "node-2.example.com",
        "node-3.example.com",
    ]
    assert [node.address for node in wrapped.nodes] == [
        "node-4.example.com",
        "node-0.example.com",
    ]
