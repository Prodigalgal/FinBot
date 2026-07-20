from __future__ import annotations

import os
import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Protocol

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status

from finbot_browser_worker.models import (
    BrowserWorkerRuntimeStatus,
    ChallengeSolveRequest,
    ChallengeSolveResponse,
    HealthResponse,
)
from finbot_browser_worker.solver import BrowserChallengeSolver, BrowserWorkerCapacityError


class BrowserSolver(Protocol):
    @property
    def ready(self) -> bool: ...

    @property
    def status(self) -> BrowserWorkerRuntimeStatus: ...

    async def start(self) -> None: ...

    async def stop(self) -> None: ...

    async def solve(self, request: ChallengeSolveRequest) -> ChallengeSolveResponse: ...


def create_app(solver: BrowserSolver | None = None, service_token: str | None = None) -> FastAPI:
    token = (service_token if service_token is not None else os.getenv("FINBOT_BROWSER_WORKER_TOKEN", "")).strip()
    if not token:
        raise RuntimeError("FINBOT_BROWSER_WORKER_TOKEN is required")
    engine = solver or BrowserChallengeSolver()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await engine.start()
        try:
            yield
        finally:
            await engine.stop()

    app = FastAPI(title="FinBot Browser Worker", docs_url=None, redoc_url=None, lifespan=lifespan)

    def require_token(authorization: str | None = Header(default=None)) -> None:
        if authorization is None or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer token")
        provided = authorization.removeprefix("Bearer ").strip()
        if not secrets.compare_digest(provided, token):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid bearer token")

    @app.get("/internal/v1/health", response_model=HealthResponse)
    async def health(response: Response) -> HealthResponse:
        if not engine.ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthResponse(
            status="ok" if engine.ready else "starting",
            engine="playwright-chromium",
            ready=engine.ready,
            detail=engine.status,
        )

    @app.post(
        "/internal/v1/challenge/solve",
        response_model=ChallengeSolveResponse,
        dependencies=[Depends(require_token)],
    )
    async def solve(request: ChallengeSolveRequest) -> ChallengeSolveResponse:
        try:
            return await engine.solve(request)
        except BrowserWorkerCapacityError as error:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="browser solve capacity exhausted",
                headers={"Retry-After": "1"},
            ) from error
        except Exception as error:  # noqa: BLE001 - map all browser failures to 502
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=f"browser solve failed: {error.__class__.__name__}",
            ) from error

    return app
