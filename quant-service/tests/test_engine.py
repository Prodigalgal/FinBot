from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import UTC, datetime, timedelta

import pytest

from finbot_quant.engine import DefaultResearchEngine
from finbot_quant.models import (
    ArtifactKind,
    ArtifactReference,
    CompletedUpdate,
    Exchange,
    FailedUpdate,
    Instrument,
    MarketType,
    ResearchJob,
    ResearchKind,
)


class InMemoryArtifactLoader:
    def __init__(self, payload: bytes) -> None:
        self._payload = payload

    async def load(self, artifact: ArtifactReference) -> bytes:
        assert artifact.sha256_hex == hashlib.sha256(self._payload).hexdigest()
        assert artifact.byte_size == len(self._payload)
        return self._payload


@pytest.mark.asyncio
async def test_default_engine_runs_cost_aware_signal_backtest() -> None:
    payload = _market_data_payload()
    engine = DefaultResearchEngine(InMemoryArtifactLoader(payload))
    events = [event async for event in engine.stream(_job(payload), asyncio.Event())]

    assert len(events) == 6
    completed = events[-1]
    assert isinstance(completed, CompletedUpdate)
    metrics = {metric.name: metric.value for metric in completed.metrics}
    assert metrics["evaluated_periods"] > 50
    assert 0 <= metrics["maximum_drawdown"] <= 1
    assert 0 <= metrics["win_rate"] <= 1
    assert completed.observation_count == 180
    assert len(completed.result_fingerprint) == 64


@pytest.mark.asyncio
async def test_default_engine_honors_pre_cancelled_run() -> None:
    payload = _market_data_payload()
    cancellation = asyncio.Event()
    cancellation.set()
    events = [event async for event in DefaultResearchEngine(
        InMemoryArtifactLoader(payload)
    ).stream(_job(payload), cancellation)]

    assert isinstance(events[-1], FailedUpdate)
    assert events[-1].code.value == "CANCELLED"


def _job(payload: bytes) -> ResearchJob:
    artifact = ArtifactReference(
        kind=ArtifactKind.INPUT_MARKET_DATA,
        uri="https://finbot-backend/internal/v1/quant-artifacts/artifact_test",
        sha256_hex=hashlib.sha256(payload).hexdigest(),
        media_type="application/json",
        byte_size=len(payload),
    )
    return ResearchJob(
        research_run_id="research_engine_test",
        workflow_run_id="run_engine_test",
        idempotency_key="engine-test-idempotency",
        kind=ResearchKind.BACKTEST,
        instruments=(Instrument(Exchange.GATE, "BTC_USDT", MarketType.PERPETUAL, "USDT"),),
        start_inclusive=datetime(2026, 1, 1, tzinfo=UTC),
        end_exclusive=datetime(2026, 2, 1, tzinfo=UTC),
        market_data=artifact,
        strategy_id="moving_average_crossover",
        strategy_version="1.0.0",
        parameters=(),
        deterministic_seed=42,
        requested_at=datetime(2026, 7, 14, tzinfo=UTC),
    )


def _market_data_payload() -> bytes:
    start = datetime(2026, 1, 1, tzinfo=UTC)
    candles: list[dict[str, object]] = []
    price = 90_000.0
    for index in range(180):
        drift = 25.0 if (index // 30) % 2 == 0 else -18.0
        open_price = price
        close = max(1.0, open_price + drift + (index % 7 - 3) * 4.0)
        candles.append(
            {
                "timestamp": (start + timedelta(hours=index)).isoformat(),
                "open": open_price,
                "high": max(open_price, close) + 20.0,
                "low": min(open_price, close) - 20.0,
                "close": close,
                "volume": 100.0 + index,
                "fundingRate": 0.0001,
            }
        )
        price = close
    return json.dumps(
        {
            "schemaVersion": 1,
            "instruments": [
                {
                    "exchange": "GATE",
                    "symbol": "BTC_USDT",
                    "marketType": "PERPETUAL",
                    "quoteCurrency": "USDT",
                    "candles": candles,
                }
            ],
        },
        separators=(",", ":"),
    ).encode()
