from __future__ import annotations

import hashlib
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

from finbot_proxy.models import Subscription
from finbot_proxy.singbox import build_configuration
from finbot_proxy.subscription import load_subscription, parse_subscription, select_nodes


@dataclass(frozen=True, slots=True)
class RuntimeConfiguration:
    subscription_url: str | None
    inline_nodes: str | None
    preferred_names: tuple[str, ...]
    maximum_nodes: int
    refresh_seconds: int
    fetch_timeout_seconds: float
    proxy_port: int
    health_port: int
    sing_box_path: Path
    runtime_directory: Path
    allow_insecure_tls: bool

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
        if proxy_port == health_port:
            raise ValueError("PROXY_PORT and PROXY_HEALTH_PORT must differ")
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
            health_port=health_port,
            sing_box_path=Path(os.getenv("SING_BOX_PATH", "/usr/local/bin/sing-box")),
            runtime_directory=Path(os.getenv("PROXY_RUNTIME_DIRECTORY", "/tmp/finbot-proxy")),
            allow_insecure_tls=_boolean("PROXY_ALLOW_INSECURE_TLS", False),
        )


class GatewayState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._ready = False
        self._node_count = 0
        self._invalid_node_count = 0
        self._insecure_node_count = 0
        self._rejected_insecure_node_count = 0
        self._enabled_insecure_node_count = 0
        self._allow_insecure_tls = False
        self._generation = 0
        self._last_refresh_epoch_seconds: float | None = None
        self._last_error: str | None = None

    def successful_refresh(
        self,
        *,
        node_count: int,
        invalid_node_count: int,
        insecure_node_count: int,
        rejected_insecure_node_count: int,
        enabled_insecure_node_count: int,
        allow_insecure_tls: bool,
    ) -> None:
        with self._lock:
            self._ready = True
            self._node_count = node_count
            self._invalid_node_count = invalid_node_count
            self._insecure_node_count = insecure_node_count
            self._rejected_insecure_node_count = rejected_insecure_node_count
            self._enabled_insecure_node_count = enabled_insecure_node_count
            self._allow_insecure_tls = allow_insecure_tls
            self._generation += 1
            self._last_refresh_epoch_seconds = time.time()
            self._last_error = None

    def failed_refresh(self, error: Exception, *, process_alive: bool) -> None:
        with self._lock:
            self._ready = process_alive
            self._last_refresh_epoch_seconds = time.time()
            self._last_error = _safe_error(error)

    def process_stopped(self) -> None:
        with self._lock:
            self._ready = False

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                "status": "ready" if self._ready else "not-ready",
                "ready": self._ready,
                "nodeCount": self._node_count,
                "invalidNodeCount": self._invalid_node_count,
                "insecureNodeCount": self._insecure_node_count,
                "rejectedInsecureNodeCount": self._rejected_insecure_node_count,
                "enabledInsecureNodeCount": self._enabled_insecure_node_count,
                "allowInsecureTls": self._allow_insecure_tls,
                "generation": self._generation,
                "lastRefreshEpochSeconds": self._last_refresh_epoch_seconds,
                "lastError": self._last_error,
            }


class ProxyGateway:
    def __init__(self, configuration: RuntimeConfiguration, state: GatewayState) -> None:
        self._configuration = configuration
        self._state = state
        self._stop = threading.Event()
        self._process: subprocess.Popen[bytes] | None = None
        self._configuration_hash: str | None = None

    def run(self) -> None:
        self._validate_runtime()
        while not self._stop.is_set():
            try:
                self._refresh()
            except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
                self._state.failed_refresh(error, process_alive=self._process_alive())
            self._stop.wait(self._configuration.refresh_seconds)
        self._stop_process()

    def stop(self) -> None:
        self._stop.set()

    def _refresh(self) -> None:
        subscription = self._load_nodes()
        selection = select_nodes(
            subscription,
            maximum_nodes=self._configuration.maximum_nodes,
            preferred_names=self._configuration.preferred_names,
            allow_insecure_tls=self._configuration.allow_insecure_tls,
        )
        if not selection.nodes:
            raise RuntimeError("Proxy subscription contains no secure supported nodes")
        generated = build_configuration(
            selection.nodes,
            listen_port=self._configuration.proxy_port,
        )
        generated_hash = hashlib.sha256(generated.encode("utf-8")).hexdigest()
        if generated_hash != self._configuration_hash or not self._process_alive():
            self._replace_process(generated)
            self._configuration_hash = generated_hash
        self._state.successful_refresh(
            node_count=len(selection.nodes),
            invalid_node_count=subscription.invalid_node_count,
            insecure_node_count=selection.insecure_node_count,
            rejected_insecure_node_count=selection.rejected_insecure_node_count,
            enabled_insecure_node_count=selection.enabled_insecure_node_count,
            allow_insecure_tls=self._configuration.allow_insecure_tls,
        )

    def _load_nodes(self) -> Subscription:
        subscriptions: list[Subscription] = []
        if self._configuration.subscription_url is not None:
            subscriptions.append(
                load_subscription(
                    self._configuration.subscription_url,
                    timeout_seconds=self._configuration.fetch_timeout_seconds,
                )
            )
        if self._configuration.inline_nodes is not None:
            subscriptions.append(
                parse_subscription(self._configuration.inline_nodes.replace(";", "\n"))
            )
        nodes = tuple(node for subscription in subscriptions for node in subscription.nodes)
        unique_nodes = tuple(dict.fromkeys(nodes))
        return Subscription(
            nodes=unique_nodes,
            invalid_node_count=sum(item.invalid_node_count for item in subscriptions),
        )

    def _replace_process(self, generated: str) -> None:
        runtime = self._configuration.runtime_directory
        runtime.mkdir(parents=True, exist_ok=True)
        candidate = runtime / "sing-box.candidate.json"
        active = runtime / "sing-box.json"
        candidate.write_text(generated, encoding="utf-8")
        candidate.chmod(0o600)
        checked = subprocess.run(
            [str(self._configuration.sing_box_path), "check", "-c", str(candidate)],
            capture_output=True,
            timeout=20,
            check=False,
        )
        if checked.returncode != 0:
            raise RuntimeError("sing-box rejected generated configuration")
        candidate.replace(active)
        self._stop_process()
        self._process = subprocess.Popen(
            [str(self._configuration.sing_box_path), "run", "-c", str(active)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if self._process.poll() is not None:
                raise RuntimeError("sing-box exited during startup")
            if _tcp_connectable("127.0.0.1", self._configuration.proxy_port):
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

    def _validate_runtime(self) -> None:
        if not self._configuration.sing_box_path.is_file():
            raise FileNotFoundError("sing-box executable is unavailable")
        if self._configuration.fetch_timeout_seconds <= 0:
            raise ValueError("PROXY_FETCH_TIMEOUT_SECONDS must be positive")


def serve_health(state: GatewayState, port: int) -> ThreadingHTTPServer:
    class HealthHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            snapshot = state.snapshot()
            if self.path == "/health/live":
                self._reply(HTTPStatus.OK, {"status": "live"})
            elif self.path == "/health/ready":
                status = HTTPStatus.OK if snapshot["ready"] else HTTPStatus.SERVICE_UNAVAILABLE
                self._reply(status, snapshot)
            elif self.path == "/health":
                self._reply(HTTPStatus.OK, snapshot)
            else:
                self._reply(HTTPStatus.NOT_FOUND, {"status": "not-found"})

        def log_message(self, format_string: str, *arguments: object) -> None:
            del format_string, arguments

        def _reply(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

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


def _safe_error(error: Exception) -> str:
    message = type(error).__name__
    if isinstance(error, (ValueError, RuntimeError)) and error.args:
        detail = str(error.args[0])
        if "http" not in detail.casefold():
            message += ": " + detail
    return message[:240]
