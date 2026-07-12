from __future__ import annotations

import json
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from finbot.network.hysteria2 import Hysteria2Node
from finbot.network.vless_subscription import VlessNode


SingBoxNode = VlessNode | Hysteria2Node


@dataclass(frozen=True)
class SingBoxBridgeConfig:
    binary_path: str
    work_dir: Path
    bind_host: str = "127.0.0.1"
    max_nodes: int = 1
    startup_timeout_seconds: float = 10.0


class SingBoxBridgeManager:
    def __init__(self, nodes: tuple[SingBoxNode, ...], config: SingBoxBridgeConfig):
        self.nodes = nodes
        self.config = config
        self._processes: list[subprocess.Popen] = []
        self._proxy_urls: list[str] = []
        self._config_paths: list[Path] = []
        self._startup_errors: list[str] = []

    def start(self) -> list[str]:
        if self._proxy_urls:
            return list(self._proxy_urls)
        self._startup_errors.clear()
        binary = Path(self.config.binary_path)
        if not binary.exists():
            raise FileNotFoundError(f"sing-box binary not found: {binary}")
        self.config.work_dir.mkdir(parents=True, exist_ok=True)
        supported = [node for node in self.nodes if _supported(node)]
        for index, node in enumerate(supported[: max(0, self.config.max_nodes)]):
            port = _free_port(self.config.bind_host)
            protocol = "hysteria2" if isinstance(node, Hysteria2Node) else "vless"
            config_path = self.config.work_dir / f"sing-box-{protocol}-{index}-{port}.json"
            process = None
            try:
                config_path.write_text(
                    json.dumps(_sing_box_config(node, self.config.bind_host, port), ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
                self._check_config(binary, config_path)
                process = self._start_process(binary, config_path)
                _wait_port(self.config.bind_host, port, self.config.startup_timeout_seconds)
            except Exception as exc:
                self._startup_errors.append(f"{protocol}:{type(exc).__name__}")
                _stop_process(process)
                config_path.unlink(missing_ok=True)
                continue
            self._processes.append(process)
            self._config_paths.append(config_path)
            self._proxy_urls.append(f"http://{self.config.bind_host}:{port}")
        if supported and not self._proxy_urls:
            errors = ", ".join(self._startup_errors) or "unknown"
            raise RuntimeError(f"No sing-box proxy bridge started ({errors})")
        return list(self._proxy_urls)

    def close(self) -> None:
        for process in self._processes:
            _stop_process(process)
        for config_path in self._config_paths:
            config_path.unlink(missing_ok=True)
        self._processes.clear()
        self._proxy_urls.clear()
        self._config_paths.clear()

    def summary(self) -> dict[str, Any]:
        return {
            "bridge": "sing-box",
            "binary": str(Path(self.config.binary_path)),
            "node_count": len(self.nodes),
            "running_proxy_count": len(self._processes),
            "proxy_urls": ["http://127.0.0.1:<dynamic>" for _ in self._proxy_urls],
            "config_paths": [str(path) for path in self._config_paths],
            "max_nodes": self.config.max_nodes,
            "startup_error_count": len(self._startup_errors),
        }

    def _check_config(self, binary: Path, config_path: Path) -> None:
        result = subprocess.run(
            [str(binary), "check", "-c", str(config_path)],
            capture_output=True,
            text=True,
            timeout=15,
        )
        if result.returncode != 0:
            message = (result.stderr or result.stdout or "sing-box config check failed").strip()
            raise RuntimeError(message[:600])

    def _start_process(self, binary: Path, config_path: Path) -> subprocess.Popen:
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        return subprocess.Popen(
            [str(binary), "run", "-c", str(config_path)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creationflags,
        )


def _sing_box_config(node: SingBoxNode, listen: str, port: int) -> dict[str, Any]:
    outbound = _hysteria2_outbound(node) if isinstance(node, Hysteria2Node) else _vless_outbound(node)
    return {
        "log": {"level": "warn", "timestamp": True},
        "inbounds": [
            {
                "type": "mixed",
                "tag": "mixed-in",
                "listen": listen,
                "listen_port": port,
            }
        ],
        "outbounds": [
            outbound,
            {"type": "direct", "tag": "direct"},
        ],
        "route": {"final": "proxy"},
    }


def _vless_outbound(node: VlessNode) -> dict[str, Any]:
    outbound: dict[str, Any] = {
        "type": "vless",
        "tag": "proxy",
        "server": node.address,
        "server_port": node.port,
        "uuid": node.uuid,
    }
    if node.security == "tls":
        outbound["tls"] = {
            "enabled": True,
            "server_name": node.tls_server_name,
            "utls": {"enabled": True, "fingerprint": "chrome"},
        }
    if node.transport == "ws":
        outbound["transport"] = {
            "type": "ws",
            "path": node.path,
            "headers": {"Host": node.websocket_host_header},
        }
    return outbound


def _hysteria2_outbound(node: Hysteria2Node) -> dict[str, Any]:
    outbound: dict[str, Any] = {
        "type": "hysteria2",
        "tag": "proxy",
        "server": node.address,
        "password": node.password,
        "hop_interval": node.hop_interval,
        "tls": {
            "enabled": True,
            "server_name": node.sni,
            "insecure": node.insecure,
        },
    }
    if len(node.server_ports) == 1 and ":" not in node.server_ports[0]:
        outbound["server_port"] = int(node.server_ports[0])
    else:
        outbound["server_ports"] = list(node.server_ports)
    if node.obfs_type:
        outbound["obfs"] = {
            "type": node.obfs_type,
            "password": node.obfs_password,
        }
    return outbound


def _supported(node: SingBoxNode) -> bool:
    if isinstance(node, Hysteria2Node):
        return True
    return node.transport == "ws" and node.security in {"tls", "none"}


def _stop_process(process: subprocess.Popen | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def _free_port(host: str) -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((host, 0))
        return int(sock.getsockname()[1])


def _wait_port(host: str, port: int, timeout_seconds: float) -> None:
    deadline = time.time() + timeout_seconds
    last_error: Exception | None = None
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return
        except OSError as exc:
            last_error = exc
            time.sleep(0.2)
    raise TimeoutError(f"sing-box local proxy did not become ready on {host}:{port}: {last_error}")
