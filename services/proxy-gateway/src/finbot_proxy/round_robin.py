from __future__ import annotations

import socket
import socketserver
import threading
from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class NodeAssignment:
    index: int
    port: int


class NodeRotator:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._ports: tuple[int, ...] = ()
        self._next_index = 0

    def replace(self, ports: tuple[int, ...]) -> None:
        if not ports or len(set(ports)) != len(ports):
            raise ValueError("Round-robin node ports must be non-empty and unique")
        if any(port < 1 or port > 65535 for port in ports):
            raise ValueError("Round-robin node port is invalid")
        with self._lock:
            self._ports = ports
            self._next_index = 0

    def next(self) -> NodeAssignment:
        with self._lock:
            if not self._ports:
                raise RuntimeError("Round-robin proxy has no active nodes")
            index = self._next_index
            self._next_index = (index + 1) % len(self._ports)
            return NodeAssignment(index=index, port=self._ports[index])


class RoundRobinTcpProxy:
    def __init__(
        self,
        listen_port: int,
        on_assignment: Callable[[int], None],
        *,
        listen_host: str = "0.0.0.0",
        target_host: str = "127.0.0.1",
    ) -> None:
        self._listen_host = listen_host
        self._listen_port = listen_port
        self._target_host = target_host
        self._on_assignment = on_assignment
        self._rotator = NodeRotator()
        self._server: _RelayServer | None = None
        self._server_thread: threading.Thread | None = None

    def start(self) -> None:
        if self._server is not None:
            return
        server = _RelayServer(
            (self._listen_host, self._listen_port),
            _RelayHandler,
            self._rotator,
            self._target_host,
            self._on_assignment,
        )
        thread = threading.Thread(
            target=server.serve_forever,
            name="proxy-round-robin",
            daemon=True,
        )
        self._server = server
        self._server_thread = thread
        thread.start()

    def update_targets(self, ports: tuple[int, ...]) -> None:
        self._rotator.replace(ports)

    def stop(self) -> None:
        server = self._server
        thread = self._server_thread
        self._server = None
        self._server_thread = None
        if server is None:
            return
        server.shutdown()
        server.server_close()
        if thread is not None:
            thread.join(timeout=5)


class _RelayServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(
        self,
        server_address: tuple[str, int],
        handler_class: type[socketserver.BaseRequestHandler],
        rotator: NodeRotator,
        target_host: str,
        on_assignment: Callable[[int], None],
    ) -> None:
        self.rotator = rotator
        self.target_host = target_host
        self.on_assignment = on_assignment
        super().__init__(server_address, handler_class)


class _RelayHandler(socketserver.BaseRequestHandler):
    server: _RelayServer
    request: socket.socket

    def handle(self) -> None:
        try:
            assignment = self.server.rotator.next()
            upstream = socket.create_connection(
                (self.server.target_host, assignment.port), timeout=5
            )
        except (OSError, RuntimeError):
            return
        self.server.on_assignment(assignment.index)
        with upstream:
            self.request.settimeout(None)
            upstream.settimeout(None)
            downstream = threading.Thread(
                target=_copy_socket,
                args=(upstream, self.request),
                name="proxy-relay-downstream",
                daemon=True,
            )
            downstream.start()
            _copy_socket(self.request, upstream)
            downstream.join(timeout=2)


def _copy_socket(source: socket.socket, destination: socket.socket) -> None:
    try:
        while chunk := source.recv(64 * 1024):
            destination.sendall(chunk)
    except OSError:
        pass
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except OSError:
            pass
