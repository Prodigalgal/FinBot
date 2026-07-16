from __future__ import annotations

import os

from finbot_proxy.runtime import (
    GatewayState,
    ProxyGateway,
    RuntimeConfiguration,
    install_signal_handlers,
    serve_health,
)


def run() -> None:
    configuration = RuntimeConfiguration.from_environment()
    state = GatewayState()
    gateway = ProxyGateway(configuration, state)
    health_server = serve_health(
        state,
        gateway,
        configuration.health_port,
        os.getenv("PROXY_CONTROL_TOKEN", "").strip() or None,
    )
    install_signal_handlers(gateway)
    try:
        gateway.run()
    finally:
        health_server.shutdown()
        health_server.server_close()


if __name__ == "__main__":
    run()
