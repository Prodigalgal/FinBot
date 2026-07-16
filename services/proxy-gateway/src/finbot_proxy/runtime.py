from __future__ import annotations

import hashlib
import hmac
import json
import os
import signal
import subprocess
import threading
import time
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

from finbot_proxy.models import Subscription
from finbot_proxy.round_robin import NodeAssignment, RoundRobinTcpProxy
from finbot_proxy.singbox import build_configuration
from finbot_proxy.subscription import load_subscription, parse_subscription, select_nodes
from finbot_proxy.target_probe import TargetProbeConfiguration, probe_targets


@dataclass(frozen=True, slots=True)
class RuntimeConfiguration:
    subscription_url: str | None
    inline_nodes: str | None
    preferred_names: tuple[str, ...]
    maximum_nodes: int
    refresh_seconds: int
    fetch_timeout_seconds: float
    proxy_port: int
    node_port_start: int
    health_port: int
    sing_box_path: Path
    runtime_directory: Path
    allow_insecure_tls: bool
    target_probe: TargetProbeConfiguration | None

    @classmethod
    def from_environment(cls) -> RuntimeConfiguration:
        subscription_url = os.getenv("PROXY_SUBSCRIPTION_URL", "").strip() or None
        inline_nodes = os.getenv("PROXY_NODES", "").strip() or None
        if subscription_url is None and inline_nodes is None:
            raise RuntimeError("PROXY_SUBSCRIPTION_URL or PROXY_NODES is required")
        maximum_nodes = _integer("PROXY_MAXIMUM_NODES", 32, minimum=1, maximum=128)
        refresh_seconds = _integer(
            "PROXY_SUBSCRIPTION_REFRESH_SECONDS", 1800, minimum=60, maximum=86400
        )
        proxy_port = _integer("PROXY_PORT", 8080, minimum=1024, maximum=65535)
        health_port = _integer("PROXY_HEALTH_PORT", 8081, minimum=1024, maximum=65535)
        node_port_start = _integer(
            "PROXY_NODE_PORT_START", 10000, minimum=1024, maximum=65407
        )
        if proxy_port == health_port:
            raise ValueError("PROXY_PORT and PROXY_HEALTH_PORT must differ")
        _validate_port_ranges(proxy_port, health_port, node_port_start, maximum_nodes)
        return cls(
            subscription_url=subscription_url,
            inline_nodes=inline_nodes,
            preferred_names=tuple(
                item.strip()
                for item in os.getenv("PROXY_PREFERRED_NAMES", "").split(",")
                if item.strip()
            ),
            maximum_nodes=maximum_nodes,
            refresh_seconds=refresh_seconds,
            fetch_timeout_seconds=float(os.getenv("PROXY_FETCH_TIMEOUT_SECONDS", "20")),
            proxy_port=proxy_port,
            node_port_start=node_port_start,
            health_port=health_port,
            sing_box_path=Path(os.getenv("SING_BOX_PATH", "/usr/local/bin/sing-box")),
            runtime_directory=Path(os.getenv("PROXY_RUNTIME_DIRECTORY", "/tmp/finbot-proxy")),
            allow_insecure_tls=_boolean("PROXY_ALLOW_INSECURE_TLS", False),
            target_probe=_target_probe_from_environment(),
        )

    def with_dynamic_payload(self, payload: dict[str, Any]) -> RuntimeConfiguration:
        subscription_url = _optional_payload_string(payload, "subscriptionUrl")
        inline_nodes = _optional_payload_string(payload, "inlineNodes")
        if subscription_url is None and inline_nodes is None:
            raise ValueError("subscriptionUrl or inlineNodes is required")
        preferred_names = _string_list(payload, "preferredNames", maximum_items=32)
        maximum_nodes = _payload_integer(payload, "maximumNodes", minimum=1, maximum=128)
        refresh_seconds = _payload_integer(
            payload, "refreshSeconds", minimum=60, maximum=86400
        )
        allow_insecure_tls = payload.get("allowInsecureTls")
        if not isinstance(allow_insecure_tls, bool):
            raise ValueError("allowInsecureTls must be a boolean")
        return RuntimeConfiguration(
            subscription_url=subscription_url,
            inline_nodes=inline_nodes,
            preferred_names=preferred_names,
            maximum_nodes=maximum_nodes,
            refresh_seconds=refresh_seconds,
            fetch_timeout_seconds=self.fetch_timeout_seconds,
            proxy_port=self.proxy_port,
            node_port_start=self.node_port_start,
            health_port=self.health_port,
            sing_box_path=self.sing_box_path,
            runtime_directory=self.runtime_directory,
            allow_insecure_tls=allow_insecure_tls,
            target_probe=self.target_probe,
        )


class GatewayState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._refresh_condition = threading.Condition(self._lock)
        self._ready = False
        self._service_ready = False
        self._node_count = 0
        self._healthy_node_count = 0
        self._unhealthy_node_count = 0
        self._healthy_node_indices: tuple[int, ...] = ()
        self._probe_failure_counts: dict[str, int] = {}
        self._validation_enabled = False
        self._validation_target: str | None = None
        self._last_validation_epoch_seconds: float | None = None
        self._invalid_node_count = 0
        self._insecure_node_count = 0
        self._rejected_insecure_node_count = 0
        self._enabled_insecure_node_count = 0
        self._allow_insecure_tls = False
        self._generation = 0
        self._refresh_attempt = 0
        self._last_refresh_epoch_seconds: float | None = None
        self._last_error: str | None = None
        self._assigned_connection_count = 0
        self._last_assigned_node_index: int | None = None

    def successful_refresh(
        self,
        *,
        node_count: int,
        invalid_node_count: int,
        insecure_node_count: int,
        rejected_insecure_node_count: int,
        enabled_insecure_node_count: int,
        allow_insecure_tls: bool,
        healthy_node_indices: tuple[int, ...],
        probe_failure_counts: dict[str, int],
        validation_target: str | None,
    ) -> None:
        with self._lock:
            self._service_ready = True
            self._ready = bool(healthy_node_indices)
            self._node_count = node_count
            self._healthy_node_count = len(healthy_node_indices)
            self._unhealthy_node_count = node_count - len(healthy_node_indices)
            self._healthy_node_indices = healthy_node_indices
            self._probe_failure_counts = dict(probe_failure_counts)
            self._validation_enabled = validation_target is not None
            self._validation_target = validation_target
            self._last_validation_epoch_seconds = time.time()
            self._invalid_node_count = invalid_node_count
            self._insecure_node_count = insecure_node_count
            self._rejected_insecure_node_count = rejected_insecure_node_count
            self._enabled_insecure_node_count = enabled_insecure_node_count
            self._allow_insecure_tls = allow_insecure_tls
            self._generation += 1
            self._refresh_attempt += 1
            self._last_refresh_epoch_seconds = time.time()
            self._last_error = (
                None if self._ready else "Proxy target validation found no healthy nodes"
            )
            self._refresh_condition.notify_all()

    def failed_refresh(self, error: Exception, *, process_alive: bool) -> None:
        with self._lock:
            self._service_ready = process_alive
            self._ready = self._ready and process_alive
            self._refresh_attempt += 1
            self._last_refresh_epoch_seconds = time.time()
            self._last_error = _safe_error(error)
            self._refresh_condition.notify_all()

    def process_stopped(self) -> None:
        with self._lock:
            self._ready = False
            self._service_ready = False

    def assigned_node(self, node_index: int) -> None:
        with self._lock:
            self._assigned_connection_count += 1
            self._last_assigned_node_index = node_index

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return self._snapshot()

    def requires_refresh(self) -> bool:
        with self._lock:
            return not self._ready or self._last_error is not None

    def wait_for_refresh(
        self, previous_attempt: int, timeout_seconds: float
    ) -> dict[str, Any]:
        with self._refresh_condition:
            self._refresh_condition.wait_for(
                lambda: self._refresh_attempt > previous_attempt,
                timeout=timeout_seconds,
            )
            return self._snapshot()

    def _snapshot(self) -> dict[str, Any]:
        return {
            "status": "ready" if self._ready else "not-ready",
            "ready": self._ready,
            "serviceReady": self._service_ready,
            "nodeCount": self._node_count,
            "healthyNodeCount": self._healthy_node_count,
            "unhealthyNodeCount": self._unhealthy_node_count,
            "healthyNodeIndices": list(self._healthy_node_indices),
            "probeFailureCounts": dict(self._probe_failure_counts),
            "validationEnabled": self._validation_enabled,
            "validationTarget": self._validation_target,
            "lastValidationEpochSeconds": self._last_validation_epoch_seconds,
            "invalidNodeCount": self._invalid_node_count,
            "insecureNodeCount": self._insecure_node_count,
            "rejectedInsecureNodeCount": self._rejected_insecure_node_count,
            "enabledInsecureNodeCount": self._enabled_insecure_node_count,
            "allowInsecureTls": self._allow_insecure_tls,
            "generation": self._generation,
            "refreshAttempt": self._refresh_attempt,
            "lastRefreshEpochSeconds": self._last_refresh_epoch_seconds,
            "lastError": self._last_error,
            "rotationMode": "round-robin-per-connection",
            "assignedConnectionCount": self._assigned_connection_count,
            "lastAssignedNodeIndex": self._last_assigned_node_index,
        }


class ProxyGateway:
    def __init__(self, configuration: RuntimeConfiguration, state: GatewayState) -> None:
        self._configuration = configuration
        self._state = state
        self._stop = threading.Event()
        self._reload_requested = threading.Event()
        self._configuration_lock = threading.Lock()
        self._refresh_lock = threading.Lock()
        self._process: subprocess.Popen[bytes] | None = None
        self._configuration_hash: str | None = None
        self._round_robin = RoundRobinTcpProxy(
            configuration.proxy_port,
            state.assigned_node,
        )

    def run(self) -> None:
        self._validate_runtime(self._configuration)
        self._round_robin.start()
        try:
            while not self._stop.is_set():
                with self._refresh_lock:
                    configuration = self._current_configuration()
                    try:
                        self._refresh(configuration)
                    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
                        self._state.failed_refresh(error, process_alive=self._process_alive())
                self._reload_requested.wait(configuration.refresh_seconds)
                self._reload_requested.clear()
        finally:
            self._round_robin.stop()
            self._stop_process()

    def stop(self) -> None:
        self._stop.set()
        self._reload_requested.set()

    def reconfigure(
        self,
        payload: dict[str, Any],
        *,
        force_refresh: bool = False,
    ) -> dict[str, Any]:
        with self._refresh_lock:
            with self._configuration_lock:
                configuration = self._configuration.with_dynamic_payload(payload)
                self._validate_runtime(configuration)
                reload_required = (
                    force_refresh
                    or configuration != self._configuration
                    or self._state.requires_refresh()
                )
                previous_refresh_attempt = int(
                    self._state.snapshot()["refreshAttempt"]
                )
                self._configuration = configuration
        if reload_required:
            self._reload_requested.set()
        return {
            "status": "reload-accepted" if reload_required else "configuration-unchanged",
            "reloadRequired": reload_required,
            "subscriptionConfigured": configuration.subscription_url is not None,
            "inlineNodesConfigured": configuration.inline_nodes is not None,
            "preferredNameCount": len(configuration.preferred_names),
            "maximumNodes": configuration.maximum_nodes,
            "refreshSeconds": configuration.refresh_seconds,
            "allowInsecureTls": configuration.allow_insecure_tls,
            "_previousRefreshAttempt": previous_refresh_attempt,
        }

    def _current_configuration(self) -> RuntimeConfiguration:
        with self._configuration_lock:
            return self._configuration

    def _refresh(self, configuration: RuntimeConfiguration) -> None:
        subscription = self._load_nodes(configuration)
        selection = select_nodes(
            subscription,
            maximum_nodes=configuration.maximum_nodes,
            preferred_names=configuration.preferred_names,
            allow_insecure_tls=configuration.allow_insecure_tls,
        )
        if not selection.nodes:
            raise RuntimeError("Proxy subscription contains no secure supported nodes")
        generated = build_configuration(
            selection.nodes,
            listen_port_start=configuration.node_port_start,
        )
        generated_hash = hashlib.sha256(generated.encode("utf-8")).hexdigest()
        if generated_hash != self._configuration_hash or not self._process_alive():
            self._replace_process(generated, configuration, len(selection.nodes))
            self._configuration_hash = generated_hash
        assignments = tuple(
            NodeAssignment(index=index, port=configuration.node_port_start + index)
            for index in range(len(selection.nodes))
        )
        if configuration.target_probe is None:
            healthy_assignments = assignments
            probe_failure_counts: dict[str, int] = {}
            validation_target = None
        else:
            probe_summary = probe_targets(assignments, configuration.target_probe)
            healthy_assignments = probe_summary.healthy_assignments
            probe_failure_counts = probe_summary.failure_counts
            validation_target = configuration.target_probe.target
        if healthy_assignments:
            self._round_robin.update_assignments(healthy_assignments)
        else:
            self._round_robin.clear_targets()
        self._state.successful_refresh(
            node_count=len(selection.nodes),
            invalid_node_count=subscription.invalid_node_count,
            insecure_node_count=selection.insecure_node_count,
            rejected_insecure_node_count=selection.rejected_insecure_node_count,
            enabled_insecure_node_count=selection.enabled_insecure_node_count,
            allow_insecure_tls=configuration.allow_insecure_tls,
            healthy_node_indices=tuple(item.index for item in healthy_assignments),
            probe_failure_counts=probe_failure_counts,
            validation_target=validation_target,
        )

    def _load_nodes(self, configuration: RuntimeConfiguration) -> Subscription:
        subscriptions: list[Subscription] = []
        if configuration.subscription_url is not None:
            subscriptions.append(
                load_subscription(
                    configuration.subscription_url,
                    timeout_seconds=configuration.fetch_timeout_seconds,
                )
            )
        if configuration.inline_nodes is not None:
            subscriptions.append(
                parse_subscription(configuration.inline_nodes.replace(";", "\n"))
            )
        nodes = tuple(node for subscription in subscriptions for node in subscription.nodes)
        unique_nodes = tuple(dict.fromkeys(nodes))
        return Subscription(
            nodes=unique_nodes,
            invalid_node_count=sum(item.invalid_node_count for item in subscriptions),
        )

    def _replace_process(
        self,
        generated: str,
        configuration: RuntimeConfiguration,
        node_count: int,
    ) -> None:
        runtime = configuration.runtime_directory
        runtime.mkdir(parents=True, exist_ok=True)
        candidate = runtime / "sing-box.candidate.json"
        active = runtime / "sing-box.json"
        candidate.write_text(generated, encoding="utf-8")
        candidate.chmod(0o600)
        checked = subprocess.run(
            [str(configuration.sing_box_path), "check", "-c", str(candidate)],
            capture_output=True,
            timeout=20,
            check=False,
        )
        if checked.returncode != 0:
            raise RuntimeError("sing-box rejected generated configuration")
        candidate.replace(active)
        self._round_robin.clear_targets()
        self._stop_process()
        self._process = subprocess.Popen(
            [str(configuration.sing_box_path), "run", "-c", str(active)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.monotonic() + 10
        node_ports = tuple(
            configuration.node_port_start + index for index in range(node_count)
        )
        while time.monotonic() < deadline:
            if self._process.poll() is not None:
                raise RuntimeError("sing-box exited during startup")
            if all(_tcp_connectable("127.0.0.1", port) for port in node_ports):
                return
            time.sleep(0.2)
        self._stop_process()
        raise RuntimeError("sing-box proxy port did not become ready")

    def _stop_process(self) -> None:
        process = self._process
        self._process = None
        if process is None or process.poll() is not None:
            self._state.process_stopped()
            return
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        self._state.process_stopped()

    def _process_alive(self) -> bool:
        return self._process is not None and self._process.poll() is None

    def _validate_runtime(self, configuration: RuntimeConfiguration) -> None:
        if not configuration.sing_box_path.is_file():
            raise FileNotFoundError("sing-box executable is unavailable")
        if configuration.fetch_timeout_seconds <= 0:
            raise ValueError("PROXY_FETCH_TIMEOUT_SECONDS must be positive")
        _validate_port_ranges(
            configuration.proxy_port,
            configuration.health_port,
            configuration.node_port_start,
            configuration.maximum_nodes,
        )


def serve_health(
    state: GatewayState,
    gateway: ProxyGateway,
    port: int,
    control_token: str | None,
) -> ThreadingHTTPServer:
    class HealthHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            snapshot = state.snapshot()
            if self.path == "/health/live":
                self._reply(HTTPStatus.OK, {"status": "live"})
            elif self.path == "/health/ready":
                status = (
                    HTTPStatus.OK
                    if snapshot["serviceReady"]
                    else HTTPStatus.SERVICE_UNAVAILABLE
                )
                self._reply(status, snapshot)
            elif self.path == "/health/egress":
                status = HTTPStatus.OK if snapshot["ready"] else HTTPStatus.SERVICE_UNAVAILABLE
                self._reply(status, snapshot)
            elif self.path == "/health":
                self._reply(HTTPStatus.OK, snapshot)
            elif self.path == "/control/status" and self._authorized():
                self._reply(HTTPStatus.OK, snapshot)
            else:
                self._reply(HTTPStatus.NOT_FOUND, {"status": "not-found"})

        def do_PUT(self) -> None:
            request_uri = urlsplit(self.path)
            if request_uri.path != "/control/config":
                self._reply(HTTPStatus.NOT_FOUND, {"status": "not-found"})
                return
            if not self._authorized():
                self._reply(HTTPStatus.UNAUTHORIZED, {"status": "unauthorized"})
                return
            try:
                payload = self._json_body()
                force_refresh = parse_qs(request_uri.query).get("force", []) == ["true"]
                response = gateway.reconfigure(payload, force_refresh=force_refresh)
                previous_attempt = int(response.pop("_previousRefreshAttempt"))
            except (json.JSONDecodeError, UnicodeError, ValueError) as error:
                self._reply(
                    HTTPStatus.BAD_REQUEST,
                    {"status": "invalid-configuration", "error": type(error).__name__},
                )
                return
            if response["reloadRequired"]:
                snapshot = state.wait_for_refresh(previous_attempt, timeout_seconds=45)
                if int(snapshot["refreshAttempt"]) <= previous_attempt:
                    self._reply(
                        HTTPStatus.GATEWAY_TIMEOUT,
                        {"status": "reload-timeout"},
                    )
                    return
            else:
                snapshot = state.snapshot()
            if not snapshot["ready"] or snapshot["lastError"] is not None:
                self._reply(
                    HTTPStatus.BAD_GATEWAY,
                    {
                        "status": "reload-failed",
                        "error": snapshot["lastError"] or "proxy-not-ready",
                    },
                )
                return
            self._reply(
                HTTPStatus.OK,
                {
                    **response,
                    "status": "ready",
                    "generation": snapshot["generation"],
                    "nodeCount": snapshot["nodeCount"],
                },
            )

        def log_message(self, format_string: str, *arguments: object) -> None:
            del format_string, arguments

        def _reply(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            if not control_token:
                return False
            supplied = self.headers.get("Authorization", "")
            return hmac.compare_digest(supplied, "Bearer " + control_token)

        def _json_body(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 6_000_000:
                raise ValueError("Control payload size is invalid")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(payload, dict):
                raise ValueError("Control payload must be an object")
            return payload

    server = ThreadingHTTPServer(("0.0.0.0", port), HealthHandler)
    threading.Thread(target=server.serve_forever, name="proxy-health", daemon=True).start()
    return server


def install_signal_handlers(gateway: ProxyGateway) -> None:
    def stop(_signal_number: int, _frame: object) -> None:
        gateway.stop()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)


def _tcp_connectable(host: str, port: int) -> bool:
    import socket

    try:
        with socket.create_connection((host, port), timeout=0.25):
            return True
    except OSError:
        return False


def _integer(name: str, default: int, *, minimum: int, maximum: int) -> int:
    value = int(os.getenv(name, str(default)))
    if value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _boolean(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    normalized = raw.strip().casefold()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"{name} must be a boolean")


def _target_probe_from_environment() -> TargetProbeConfiguration | None:
    url = os.getenv("PROXY_PROBE_URL", "").strip()
    if not url:
        return None
    method = os.getenv("PROXY_PROBE_METHOD", "GET").strip().upper()
    raw_body = os.getenv("PROXY_PROBE_BODY")
    body = raw_body.strip() if raw_body is not None and raw_body.strip() else None
    raw_expected_body = os.getenv("PROXY_PROBE_EXPECTED_BODY")
    expected_body = (
        raw_expected_body
        if raw_expected_body is not None and raw_expected_body
        else None
    )
    return TargetProbeConfiguration(
        url=url,
        method=method,
        body=body,
        expected_status=_integer(
            "PROXY_PROBE_EXPECTED_STATUS", 200, minimum=100, maximum=599
        ),
        expected_body_substring=expected_body,
        timeout_seconds=float(os.getenv("PROXY_PROBE_TIMEOUT_SECONDS", "15")),
        concurrency=_integer("PROXY_PROBE_CONCURRENCY", 4, minimum=1, maximum=16),
    )


def _validate_port_ranges(
    proxy_port: int,
    health_port: int,
    node_port_start: int,
    maximum_nodes: int,
) -> None:
    node_ports = range(node_port_start, node_port_start + maximum_nodes)
    if node_port_start + maximum_nodes - 1 > 65535:
        raise ValueError("Proxy node port range exceeds 65535")
    if proxy_port in node_ports or health_port in node_ports:
        raise ValueError("Proxy node port range overlaps a public listener")


def _optional_payload_string(payload: dict[str, Any], name: str) -> str | None:
    value = payload.get(name)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"{name} must be a string or null")
    normalized = value.strip()
    return normalized or None


def _payload_integer(
    payload: dict[str, Any], name: str, *, minimum: int, maximum: int
) -> int:
    value = payload.get(name)
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError(f"{name} must be an integer")
    if value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _string_list(
    payload: dict[str, Any], name: str, *, maximum_items: int
) -> tuple[str, ...]:
    value = payload.get(name)
    if not isinstance(value, list) or len(value) > maximum_items:
        raise ValueError(f"{name} must be a bounded string list")
    result: list[str] = []
    for item in value:
        if not isinstance(item, str) or not item.strip() or len(item.strip()) > 80:
            raise ValueError(f"{name} contains an invalid value")
        result.append(item.strip())
    return tuple(result)


def _safe_error(error: Exception) -> str:
    message = type(error).__name__
    if isinstance(error, (ValueError, RuntimeError)) and error.args:
        detail = str(error.args[0])
        if "http" not in detail.casefold():
            message += ": " + detail
    return message[:240]
