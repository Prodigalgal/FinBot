from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import yaml
from httpx import ASGITransport, AsyncClient

from finbot_quant.models import (
    AcceptedUpdate,
    CompletedUpdate,
    MetricUnit,
    ProgressUpdate,
    QuantMetric,
    ResearchJob,
    ResearchStage,
    ResearchUpdate,
)
from finbot_quant.service import create_app

SERVICE_TOKEN = "quant-contract-test-token"
REPOSITORY_ROOT = Path(__file__).resolve().parents[3]


class FakeResearchEngine:
    def stream(
        self,
        job: ResearchJob,
        cancellation: asyncio.Event,
    ) -> AsyncIterator[ResearchUpdate]:
        del job, cancellation
        return self._events()

    async def _events(self) -> AsyncIterator[ResearchUpdate]:
        yield AcceptedUpdate("fake-engine/1", "input-fingerprint")
        yield ProgressUpdate(ResearchStage.COMPUTING, 5_000, "Computing deterministic backtest")
        yield CompletedUpdate(
            metrics=(QuantMetric("sharpe_ratio", 1.25, MetricUnit.RATIO),),
            artifacts=(),
            observation_count=1_000,
            result_fingerprint="result-fingerprint",
        )


@pytest.mark.asyncio
async def test_http_endpoint_streams_typed_sse_events() -> None:
    app = create_app(FakeResearchEngine(), service_token=SERVICE_TOKEN)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://quant.test") as client:
        response = await client.post(
            "/internal/v1/research-runs:stream",
            headers={
                "Authorization": f"Bearer {SERVICE_TOKEN}",
                "Idempotency-Key": "quant-run-01j0000000001",
            },
            json=_request(),
        )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    events = _parse_sse(response.text)
    assert [event["id"] for event in events] == ["1", "2", "3"]
    assert [event["event"] for event in events] == [
        "research.accepted",
        "research.progress",
        "research.completed",
    ]
    assert '"progressBasisPoints":5000' in events[1]["data"]
    assert '"name":"sharpe_ratio"' in events[2]["data"]


@pytest.mark.asyncio
async def test_internal_stream_requires_service_token() -> None:
    app = create_app(FakeResearchEngine(), service_token=SERVICE_TOKEN)
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://quant.test"
    ) as client:
        response = await client.post(
            "/internal/v1/research-runs:stream",
            headers={"Idempotency-Key": "quant-run-01j0000000001"},
            json=_request(),
        )

    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/problem+json")
    assert "token" in response.json()["detail"]


def test_openapi_contract_declares_the_runtime_path_and_event_union() -> None:
    contract_path = REPOSITORY_ROOT / "contracts" / "quant-research.openapi.yaml"
    contract = yaml.safe_load(contract_path.read_text(encoding="utf-8"))

    assert contract["openapi"] == "3.1.0"
    assert "/internal/v1/research-runs:stream" in contract["paths"]
    assert len(contract["components"]["schemas"]["ResearchEvent"]["oneOf"]) == 5


def _request() -> dict[str, object]:
    return {
        "researchRunId": "research_01j0000000001",
        "workflowRunId": "run_01j0000000001",
        "requestedAt": "2026-07-13T12:00:00Z",
        "specification": {
            "kind": "BACKTEST",
            "instruments": [
                {
                    "exchange": "GATE",
                    "symbol": "BTC_USDT",
                    "marketType": "PERPETUAL",
                    "quoteCurrency": "USDT",
                }
            ],
            "timeRange": {
                "startInclusive": "2026-01-01T00:00:00Z",
                "endExclusive": "2026-02-01T00:00:00Z",
            },
            "marketData": {
                "kind": "INPUT_MARKET_DATA",
                "uri": "s3://finbot-research/input.parquet",
                "sha256Hex": "a" * 64,
                "mediaType": "application/vnd.apache.parquet",
                "byteSize": 1_024,
            },
            "strategyId": "breakout",
            "strategyVersion": "1.0.0",
            "parameters": [],
            "deterministicSeed": 42,
        },
    }


def _parse_sse(payload: str) -> list[dict[str, str]]:
    events: list[dict[str, str]] = []
    for block in payload.strip().split("\n\n"):
        fields: dict[str, str] = {}
        for line in block.splitlines():
            name, value = line.split(":", 1)
            fields[name] = value.strip()
        events.append(fields)
    return events
