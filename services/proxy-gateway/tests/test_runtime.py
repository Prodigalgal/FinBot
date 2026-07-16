from __future__ import annotations

import pytest

from finbot_proxy.runtime import GatewayState, RuntimeConfiguration


def test_runtime_rejects_insecure_tls_by_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PROXY_NODES", "hysteria2://password@example.com:443")
    monkeypatch.delenv("PROXY_ALLOW_INSECURE_TLS", raising=False)

    configuration = RuntimeConfiguration.from_environment()

    assert configuration.allow_insecure_tls is False


def test_health_snapshot_exposes_insecure_tls_policy() -> None:
    state = GatewayState()

    state.successful_refresh(
        node_count=2,
        invalid_node_count=3,
        insecure_node_count=4,
        rejected_insecure_node_count=3,
        enabled_insecure_node_count=1,
        allow_insecure_tls=True,
    )

    snapshot = state.snapshot()
    assert snapshot["insecureNodeCount"] == 4
    assert snapshot["rejectedInsecureNodeCount"] == 3
    assert snapshot["enabledInsecureNodeCount"] == 1
    assert snapshot["allowInsecureTls"] is True
