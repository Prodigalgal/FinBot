from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Protocol


class ResearchKind(StrEnum):
    BACKTEST = "BACKTEST"
    PARAMETER_SEARCH = "PARAMETER_SEARCH"
    PORTFOLIO_OPTIMIZATION = "PORTFOLIO_OPTIMIZATION"
    STATISTICAL_ANALYSIS = "STATISTICAL_ANALYSIS"
    SIGNAL_EVALUATION = "SIGNAL_EVALUATION"


class Exchange(StrEnum):
    GATE = "GATE"
    BYBIT = "BYBIT"


class ExchangeEnvironment(StrEnum):
    LIVE = "LIVE"
    TESTNET = "TESTNET"
    DEMO = "DEMO"


class ResearchDataPlane(StrEnum):
    LIVE = "LIVE"
    PAPER = "PAPER"


class MarketType(StrEnum):
    SPOT = "SPOT"
    PERPETUAL = "PERPETUAL"
    FUTURE = "FUTURE"
    OPTION = "OPTION"


class ResearchStage(StrEnum):
    VALIDATING = "VALIDATING"
    LOADING_DATA = "LOADING_DATA"
    COMPUTING = "COMPUTING"
    EVALUATING = "EVALUATING"
    WRITING_ARTIFACTS = "WRITING_ARTIFACTS"


class ArtifactKind(StrEnum):
    INPUT_MARKET_DATA = "INPUT_MARKET_DATA"
    EQUITY_CURVE = "EQUITY_CURVE"
    TRADE_LEDGER = "TRADE_LEDGER"
    PARAMETER_SURFACE = "PARAMETER_SURFACE"
    RESEARCH_REPORT = "RESEARCH_REPORT"


class MetricUnit(StrEnum):
    RATIO = "RATIO"
    PERCENT = "PERCENT"
    CURRENCY = "CURRENCY"
    COUNT = "COUNT"
    DURATION_SECONDS = "DURATION_SECONDS"


class ResearchErrorCode(StrEnum):
    INVALID_REQUEST = "INVALID_REQUEST"
    INPUT_ARTIFACT_UNAVAILABLE = "INPUT_ARTIFACT_UNAVAILABLE"
    INPUT_CHECKSUM_MISMATCH = "INPUT_CHECKSUM_MISMATCH"
    INSUFFICIENT_DATA = "INSUFFICIENT_DATA"
    COMPUTATION_FAILED = "COMPUTATION_FAILED"
    TIMEOUT = "TIMEOUT"
    CANCELLED = "CANCELLED"
    INTERNAL = "INTERNAL"


type ParameterScalar = bool | int | float | Decimal | str


@dataclass(frozen=True, slots=True)
class Instrument:
    exchange: Exchange
    environment: ExchangeEnvironment
    symbol: str
    market_type: MarketType
    quote_currency: str


@dataclass(frozen=True, slots=True)
class ArtifactReference:
    kind: ArtifactKind
    uri: str
    sha256_hex: str
    media_type: str
    byte_size: int


@dataclass(frozen=True, slots=True)
class ResearchParameter:
    name: str
    value: ParameterScalar


@dataclass(frozen=True, slots=True)
class ResearchJob:
    research_run_id: str
    workflow_run_id: str
    idempotency_key: str
    kind: ResearchKind
    instruments: tuple[Instrument, ...]
    start_inclusive: datetime
    end_exclusive: datetime
    market_data: ArtifactReference
    strategy_id: str
    strategy_version: str
    parameters: tuple[ResearchParameter, ...]
    deterministic_seed: int
    requested_at: datetime


@dataclass(frozen=True, slots=True)
class AcceptedUpdate:
    engine_version: str
    input_fingerprint: str


@dataclass(frozen=True, slots=True)
class ProgressUpdate:
    stage: ResearchStage
    progress_basis_points: int
    safe_summary: str

    def __post_init__(self) -> None:
        if not 0 <= self.progress_basis_points <= 10_000:
            raise ValueError("progress_basis_points must be between 0 and 10000")


@dataclass(frozen=True, slots=True)
class ArtifactUpdate:
    artifact: ArtifactReference


@dataclass(frozen=True, slots=True)
class QuantMetric:
    name: str
    value: float
    unit: MetricUnit


@dataclass(frozen=True, slots=True)
class CompletedUpdate:
    metrics: tuple[QuantMetric, ...]
    artifacts: tuple[ArtifactReference, ...]
    observation_count: int
    result_fingerprint: str


@dataclass(frozen=True, slots=True)
class FailedUpdate:
    code: ResearchErrorCode
    safe_message: str
    retryable: bool


type ResearchUpdate = (
    AcceptedUpdate | ProgressUpdate | ArtifactUpdate | CompletedUpdate | FailedUpdate
)


class ResearchEngine(Protocol):
    def stream(
        self,
        job: ResearchJob,
        cancellation: asyncio.Event,
    ) -> AsyncIterator[ResearchUpdate]: ...
