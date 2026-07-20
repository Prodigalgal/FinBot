from __future__ import annotations

import os

from fastapi import FastAPI
import uvicorn

from finbot_browser_worker.service import create_app


def application() -> FastAPI:
    return create_app()


def run() -> None:
    uvicorn.run(
        "finbot_browser_worker.main:application",
        factory=True,
        host="0.0.0.0",
        port=int(os.getenv("PORT", "8082")),
        proxy_headers=False,
        server_header=False,
    )


if __name__ == "__main__":
    run()
