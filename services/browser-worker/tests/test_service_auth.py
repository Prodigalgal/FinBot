from __future__ import annotations

from fastapi.testclient import TestClient

from finbot_browser_worker.models import (
    BrowserWorkerRuntimeStatus,
    ChallengeSolveRequest,
    ChallengeSolveResponse,
)
from finbot_browser_worker.service import create_app
from finbot_browser_worker.solver import BrowserWorkerCapacityError


class StubSolver:
    ready = True
    status = BrowserWorkerRuntimeStatus(
        proxy_configured=True,
        proxy_origin="http://proxy.internal:8080",
        maximum_concurrent_solves=2,
        active_solves=0,
        waiting_solves=0,
        rejected_solves=0,
        completed_solves=1,
        failed_solves=0,
    )

    async def start(self) -> None:
        return None

    async def stop(self) -> None:
        return None

    async def solve(self, request: ChallengeSolveRequest) -> ChallengeSolveResponse:
        return ChallengeSolveResponse(
            final_url=str(request.url),
            status_code=200,
            cookies={"cf_clearance": "ok"},
            user_agent="stub-ua",
            title="ok",
            challenge_hints=["cloudflare"],
            detail="stub",
        )


class CapacitySolver(StubSolver):
    async def solve(self, request: ChallengeSolveRequest) -> ChallengeSolveResponse:
        raise BrowserWorkerCapacityError("capacity exhausted")


class StartingSolver(StubSolver):
    ready = False


def test_rejects_missing_token() -> None:
    app = create_app(solver=StubSolver(), service_token="secret-token")
    client = TestClient(app)
    response = client.post(
        "/internal/v1/challenge/solve",
        json={"url": "https://example.com/", "wait_ms": 0, "timeout_ms": 5000},
    )
    assert response.status_code == 401


def test_solves_with_valid_token() -> None:
    app = create_app(solver=StubSolver(), service_token="secret-token")
    client = TestClient(app)
    response = client.post(
        "/internal/v1/challenge/solve",
        headers={"Authorization": "Bearer secret-token"},
        json={"url": "https://example.com/", "wait_ms": 0, "timeout_ms": 5000},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["cookies"]["cf_clearance"] == "ok"
    assert body["detail"] == "stub"


def test_health_exposes_proxy_and_capacity_status() -> None:
    app = create_app(solver=StubSolver(), service_token="secret-token")
    client = TestClient(app)

    response = client.get("/internal/v1/health")

    assert response.status_code == 200
    assert response.json()["detail"] == StubSolver.status.model_dump(mode="json")


def test_health_is_unavailable_until_browser_is_ready() -> None:
    app = create_app(solver=StartingSolver(), service_token="secret-token")
    client = TestClient(app)

    response = client.get("/internal/v1/health")

    assert response.status_code == 503
    assert response.json()["status"] == "starting"
    assert response.json()["ready"] is False


def test_maps_capacity_exhaustion_to_retryable_429() -> None:
    app = create_app(solver=CapacitySolver(), service_token="secret-token")
    client = TestClient(app)

    response = client.post(
        "/internal/v1/challenge/solve",
        headers={"Authorization": "Bearer secret-token"},
        json={"url": "https://example.com/", "wait_ms": 0, "timeout_ms": 5000},
    )

    assert response.status_code == 429
    assert response.headers["retry-after"] == "1"
    assert response.json()["detail"] == "browser solve capacity exhausted"
