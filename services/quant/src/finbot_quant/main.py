from __future__ import annotations

import os

import uvicorn
from fastapi import FastAPI

from finbot_quant.engine import DefaultResearchEngine
from finbot_quant.market_data import HttpArtifactLoader
from finbot_quant.service import create_app


def application() -> FastAPI:
    service_token = _required("FINBOT_QUANT_SERVICE_TOKEN")
    java_service_token = _required("FINBOT_JAVA_SERVICE_TOKEN")
    allowed_hosts = frozenset(
        value.strip()
        for value in os.getenv(
            "FINBOT_ARTIFACT_ALLOWED_HOSTS",
            "finbot-backend,finbot-backend.finbot.svc,finbot-backend.finbot.svc.cluster.local",
        ).split(",")
        if value.strip()
    )
    loader = HttpArtifactLoader(
        service_token=java_service_token,
        allowed_http_hosts=allowed_hosts,
    )
    return create_app(DefaultResearchEngine(loader), service_token=service_token)


def run() -> None:
    uvicorn.run(
        "finbot_quant.main:application",
        factory=True,
        host="0.0.0.0",
        port=int(os.getenv("PORT", "8081")),
        proxy_headers=False,
        server_header=False,
    )


def _required(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise RuntimeError(f"{name} is required")
    return value.strip()


if __name__ == "__main__":
    run()
