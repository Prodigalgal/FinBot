from __future__ import annotations

from fastapi.testclient import TestClient

from finbot_browser_worker.models import ChallengeSolveRequest, ChallengeSolveResponse
from finbot_browser_worker.service import create_app


class StubSolver:
    ready = True

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
