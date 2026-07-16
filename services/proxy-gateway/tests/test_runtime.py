from pathlib import Path

import pytest

from finbot_proxy.runtime import GatewayState, ProxyGateway, RuntimeConfiguration


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
    assert snapshot["refreshAttempt"] == 1


def test_dynamic_configuration_is_atomic_and_does_not_return_secrets(tmp_path: Path) -> None:
    executable = tmp_path / "sing-box"
    executable.write_text("test", encoding="utf-8")
    configuration = RuntimeConfiguration(
        subscription_url="https://bootstrap.example/sub",
        inline_nodes=None,
        preferred_names=(),
        maximum_nodes=16,
        refresh_seconds=1800,
        fetch_timeout_seconds=20,
        proxy_port=8080,
        node_port_start=10000,
        health_port=8081,
        sing_box_path=executable,
        runtime_directory=tmp_path,
        allow_insecure_tls=False,
    )
    gateway = ProxyGateway(configuration, GatewayState())

    result = gateway.reconfigure(
        {
            "subscriptionUrl": None,
            "inlineNodes": "hysteria2://secret@example.com:443",
            "preferredNames": ["JP"],
            "maximumNodes": 8,
            "refreshSeconds": 300,
            "allowInsecureTls": False,
        }
    )

    assert result["status"] == "reload-accepted"
    assert result["reloadRequired"] is True
    assert result["inlineNodesConfigured"] is True
    assert "secret" not in str(result)


def test_unchanged_ready_configuration_does_not_force_early_refresh(
    tmp_path: Path,
) -> None:
    executable = tmp_path / "sing-box"
    executable.write_text("test", encoding="utf-8")
    configuration = RuntimeConfiguration(
        subscription_url="https://bootstrap.example/sub",
        inline_nodes=None,
        preferred_names=("JP",),
        maximum_nodes=16,
        refresh_seconds=1800,
        fetch_timeout_seconds=20,
        proxy_port=8080,
        node_port_start=10000,
        health_port=8081,
        sing_box_path=executable,
        runtime_directory=tmp_path,
        allow_insecure_tls=False,
    )
    state = GatewayState()
    state.successful_refresh(
        node_count=1,
        invalid_node_count=0,
        insecure_node_count=0,
        rejected_insecure_node_count=0,
        enabled_insecure_node_count=0,
        allow_insecure_tls=False,
    )
    gateway = ProxyGateway(configuration, state)

    result = gateway.reconfigure(
        {
            "subscriptionUrl": "https://bootstrap.example/sub",
            "inlineNodes": None,
            "preferredNames": ["JP"],
            "maximumNodes": 16,
            "refreshSeconds": 1800,
            "allowInsecureTls": False,
        }
    )

    assert result["status"] == "configuration-unchanged"
    assert result["reloadRequired"] is False
