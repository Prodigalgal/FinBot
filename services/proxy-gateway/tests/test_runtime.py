import json
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from finbot_proxy.runtime import GatewayState, ProxyGateway, RuntimeConfiguration, serve_health
from finbot_proxy.target_probe import TargetProbeConfiguration


def test_runtime_rejects_insecure_tls_by_default(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PROXY_NODES", "hysteria2://password@example.com:443")
    monkeypatch.delenv("PROXY_ALLOW_INSECURE_TLS", raising=False)

    configuration = RuntimeConfiguration.from_environment()

    assert configuration.allow_insecure_tls is False


def test_disabled_runtime_bootstraps_without_nodes(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PROXY_ENABLED", "false")
    monkeypatch.delenv("PROXY_NODES", raising=False)
    monkeypatch.delenv("PROXY_SUBSCRIPTION_URL", raising=False)

    configuration = RuntimeConfiguration.from_environment()

    assert configuration.enabled is False
    assert configuration.inline_nodes is None
    assert configuration.subscription_url is None


def test_disabled_runtime_is_service_ready_without_egress(tmp_path: Path) -> None:
    executable = tmp_path / "sing-box"
    executable.write_text("test", encoding="utf-8")
    configuration = RuntimeConfiguration(
        subscription_url=None,
        inline_nodes=None,
        preferred_names=(),
        maximum_nodes=4,
        refresh_seconds=1800,
        fetch_timeout_seconds=20,
        proxy_port=8080,
        node_port_start=10000,
        health_port=8081,
        sing_box_path=executable,
        runtime_directory=tmp_path,
        allow_insecure_tls=False,
        target_probe=None,
        enabled=False,
    )
    state = GatewayState()
    gateway = ProxyGateway(configuration, state)

    gateway._refresh(configuration)

    snapshot = state.snapshot()
    assert snapshot["enabled"] is False
    assert snapshot["serviceReady"] is True
    assert snapshot["ready"] is False
    assert snapshot["nodeCount"] == 0
    assert snapshot["lastError"] is None


def test_health_snapshot_exposes_insecure_tls_policy() -> None:
    state = GatewayState()

    state.successful_refresh(
        node_count=2,
        invalid_node_count=3,
        insecure_node_count=4,
        rejected_insecure_node_count=3,
        enabled_insecure_node_count=1,
        allow_insecure_tls=True,
        healthy_node_indices=(0,),
        probe_failure_counts={"HTTP_403": 1},
        validation_target="api.example.com",
    )

    snapshot = state.snapshot()
    assert snapshot["insecureNodeCount"] == 4
    assert snapshot["rejectedInsecureNodeCount"] == 3
    assert snapshot["enabledInsecureNodeCount"] == 1
    assert snapshot["allowInsecureTls"] is True
    assert snapshot["refreshAttempt"] == 1
    assert snapshot["serviceReady"] is True
    assert snapshot["ready"] is True
    assert snapshot["healthyNodeCount"] == 1
    assert snapshot["unhealthyNodeCount"] == 1
    assert snapshot["healthyNodeIndices"] == [0]
    assert snapshot["probeFailureCounts"] == {"HTTP_403": 1}
    assert snapshot["validationTarget"] == "api.example.com"


def test_health_snapshot_distinguishes_service_readiness_from_egress_readiness() -> None:
    state = GatewayState()

    state.successful_refresh(
        node_count=4,
        invalid_node_count=0,
        insecure_node_count=0,
        rejected_insecure_node_count=0,
        enabled_insecure_node_count=0,
        allow_insecure_tls=False,
        healthy_node_indices=(),
        probe_failure_counts={"CONNECTION_ERROR": 4},
        validation_target="api.example.com",
    )

    snapshot = state.snapshot()
    assert snapshot["serviceReady"] is True
    assert snapshot["ready"] is False
    assert snapshot["status"] == "not-ready"
    assert snapshot["healthyNodeCount"] == 0
    assert snapshot["unhealthyNodeCount"] == 4
    assert snapshot["lastError"] == "Proxy target validation found no healthy nodes"


def test_http_readiness_keeps_control_plane_available_when_egress_is_degraded(
    tmp_path: Path,
) -> None:
    executable = tmp_path / "sing-box"
    executable.write_text("test", encoding="utf-8")
    configuration = RuntimeConfiguration(
        subscription_url="https://bootstrap.example/sub",
        inline_nodes=None,
        preferred_names=(),
        maximum_nodes=4,
        refresh_seconds=1800,
        fetch_timeout_seconds=20,
        proxy_port=8080,
        node_port_start=10000,
        health_port=8081,
        sing_box_path=executable,
        runtime_directory=tmp_path,
        allow_insecure_tls=False,
        target_probe=None,
    )
    state = GatewayState()
    state.successful_refresh(
        node_count=4,
        invalid_node_count=0,
        insecure_node_count=0,
        rejected_insecure_node_count=0,
        enabled_insecure_node_count=0,
        allow_insecure_tls=False,
        healthy_node_indices=(),
        probe_failure_counts={"CONNECTION_ERROR": 4},
        validation_target="api.example.com",
    )
    server = serve_health(state, ProxyGateway(configuration, state), 0, None)
    base_url = f"http://127.0.0.1:{server.server_port}"
    try:
        with urllib.request.urlopen(f"{base_url}/health/ready", timeout=2) as response:
            payload = json.load(response)
            assert response.status == 200
            assert payload["serviceReady"] is True
            assert payload["ready"] is False
        with pytest.raises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(f"{base_url}/health/egress", timeout=2)
        assert error.value.code == 503
    finally:
        server.shutdown()
        server.server_close()


def test_target_probe_configuration_is_loaded_from_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PROXY_NODES", "hysteria2://password@example.com:443")
    monkeypatch.setenv("PROXY_PROBE_URL", "https://api.example.com/health")
    monkeypatch.setenv("PROXY_PROBE_METHOD", "POST")
    monkeypatch.setenv("PROXY_PROBE_BODY", '{"probe":true}')
    monkeypatch.setenv("PROXY_PROBE_EXPECTED_BODY", '"ready":true')

    configuration = RuntimeConfiguration.from_environment()

    assert configuration.target_probe == TargetProbeConfiguration(
        url="https://api.example.com/health",
        method="POST",
        body='{"probe":true}',
        expected_status=200,
        expected_body_substring='"ready":true',
        timeout_seconds=15,
        concurrency=4,
    )


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
        target_probe=None,
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
        target_probe=None,
    )
    state = GatewayState()
    state.successful_refresh(
        node_count=1,
        invalid_node_count=0,
        insecure_node_count=0,
        rejected_insecure_node_count=0,
        enabled_insecure_node_count=0,
        allow_insecure_tls=False,
        healthy_node_indices=(0,),
        probe_failure_counts={},
        validation_target=None,
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


def test_unchanged_degraded_configuration_waits_for_normal_refresh(
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
        target_probe=None,
    )
    state = GatewayState()
    state.successful_refresh(
        node_count=1,
        invalid_node_count=0,
        insecure_node_count=0,
        rejected_insecure_node_count=0,
        enabled_insecure_node_count=0,
        allow_insecure_tls=False,
        healthy_node_indices=(),
        probe_failure_counts={"HTTP_429": 1},
        validation_target="api.example.com",
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
    assert state.snapshot()["refreshAttempt"] == 1


def test_http_reconciliation_reports_degraded_egress_without_forcing_probe(
    tmp_path: Path,
) -> None:
    executable = tmp_path / "sing-box"
    executable.write_text("test", encoding="utf-8")
    configuration = RuntimeConfiguration(
        subscription_url="https://bootstrap.example/sub",
        inline_nodes=None,
        preferred_names=(),
        maximum_nodes=4,
        refresh_seconds=1800,
        fetch_timeout_seconds=20,
        proxy_port=8080,
        node_port_start=10000,
        health_port=8081,
        sing_box_path=executable,
        runtime_directory=tmp_path,
        allow_insecure_tls=False,
        target_probe=None,
    )
    state = GatewayState()
    state.successful_refresh(
        node_count=4,
        invalid_node_count=0,
        insecure_node_count=0,
        rejected_insecure_node_count=0,
        enabled_insecure_node_count=0,
        allow_insecure_tls=False,
        healthy_node_indices=(),
        probe_failure_counts={"CONNECTION_ERROR": 4},
        validation_target="api.example.com",
    )
    server = serve_health(
        state,
        ProxyGateway(configuration, state),
        0,
        "control-token",
    )
    request = urllib.request.Request(
        f"http://127.0.0.1:{server.server_port}/control/config",
        data=json.dumps(
            {
                "subscriptionUrl": "https://bootstrap.example/sub",
                "inlineNodes": None,
                "preferredNames": [],
                "maximumNodes": 4,
                "refreshSeconds": 1800,
                "allowInsecureTls": False,
            }
        ).encode(),
        headers={
            "Authorization": "Bearer control-token",
            "Content-Type": "application/json",
        },
        method="PUT",
    )
    try:
        with urllib.request.urlopen(request, timeout=2) as response:
            payload = json.load(response)
        assert response.status == 200
        assert payload["status"] == "configuration-unchanged"
        assert payload["reloadRequired"] is False
        assert payload["egressReady"] is False
        assert payload["generation"] == 1
        assert state.snapshot()["refreshAttempt"] == 1
    finally:
        server.shutdown()
        server.server_close()


def test_explicit_reload_forces_target_revalidation(tmp_path: Path) -> None:
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
        target_probe=None,
    )
    state = GatewayState()
    state.successful_refresh(
        node_count=1,
        invalid_node_count=0,
        insecure_node_count=0,
        rejected_insecure_node_count=0,
        enabled_insecure_node_count=0,
        allow_insecure_tls=False,
        healthy_node_indices=(0,),
        probe_failure_counts={},
        validation_target=None,
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
        },
        force_refresh=True,
    )

    assert result["status"] == "reload-accepted"
    assert result["reloadRequired"] is True
