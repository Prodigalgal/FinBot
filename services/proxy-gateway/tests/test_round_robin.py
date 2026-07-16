import socket
import threading

from finbot_proxy.round_robin import NodeRotator, RoundRobinTcpProxy


def test_rotates_every_new_connection_across_all_nodes() -> None:
    rotator = NodeRotator()
    rotator.replace((10000, 10001, 10002))

    assignments = [rotator.next() for _ in range(7)]

    assert [assignment.index for assignment in assignments] == [0, 1, 2, 0, 1, 2, 0]
    assert [assignment.port for assignment in assignments] == [
        10000,
        10001,
        10002,
        10000,
        10001,
        10002,
        10000,
    ]


def test_replacing_nodes_restarts_rotation_from_first_healthy_node() -> None:
    rotator = NodeRotator()
    rotator.replace((10000, 10001))
    assert rotator.next().index == 0

    rotator.replace((11000, 11001, 11002))

    assert rotator.next().port == 11000


def test_tcp_proxy_assigns_consecutive_connections_to_different_targets() -> None:
    first_port, first_thread = _single_response_server(b"first")
    second_port, second_thread = _single_response_server(b"second")
    proxy_port = _available_port()
    assignments: list[int] = []
    proxy = RoundRobinTcpProxy(
        proxy_port,
        assignments.append,
        listen_host="127.0.0.1",
    )
    proxy.update_targets((first_port, second_port))
    proxy.start()
    try:
        assert _request(proxy_port) == b"first:ping"
        assert _request(proxy_port) == b"second:ping"
        assert assignments == [0, 1]
    finally:
        proxy.stop()
        first_thread.join(timeout=2)
        second_thread.join(timeout=2)


def _single_response_server(prefix: bytes) -> tuple[int, threading.Thread]:
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    port = int(listener.getsockname()[1])

    def serve() -> None:
        with listener:
            connection, _address = listener.accept()
            with connection:
                connection.sendall(prefix + b":" + connection.recv(32))

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    return port, thread


def _available_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def _request(port: int) -> bytes:
    with socket.create_connection(("127.0.0.1", port), timeout=2) as connection:
        connection.sendall(b"ping")
        connection.shutdown(socket.SHUT_WR)
        return connection.recv(32)
