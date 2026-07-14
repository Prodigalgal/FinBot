from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import Field

from finbot_quant.api_models import ArtifactReferenceModel, ContractModel
from finbot_quant.models import MetricUnit, ResearchErrorCode, ResearchStage


class ResearchEventBase(ContractModel):
    event_id: str = Field(pattern=r"^quant_event_[a-f0-9]{32}$")
    research_run_id: str = Field(pattern=r"^research_[a-z0-9_-]{4,70}$")
    sequence: int = Field(ge=1)
    occurred_at: datetime


class ResearchAcceptedEvent(ResearchEventBase):
    event_type: Literal["research.accepted"] = "research.accepted"
    engine_version: str = Field(min_length=1, max_length=120)
    input_fingerprint: str = Field(min_length=1, max_length=200)


class ResearchProgressEvent(ResearchEventBase):
    event_type: Literal["research.progress"] = "research.progress"
    stage: ResearchStage
    progress_basis_points: int = Field(ge=0, le=10_000)
    safe_summary: str = Field(min_length=1, max_length=2_000)


class ResearchArtifactEvent(ResearchEventBase):
    event_type: Literal["research.artifact"] = "research.artifact"
    artifact: ArtifactReferenceModel


class QuantMetricModel(ContractModel):
    name: str = Field(min_length=1, max_length=120)
    value: float
    unit: MetricUnit


class ResearchCompletedEvent(ResearchEventBase):
    event_type: Literal["research.completed"] = "research.completed"
    metrics: tuple[QuantMetricModel, ...]
    artifacts: tuple[ArtifactReferenceModel, ...]
    observation_count: int = Field(ge=0)
    result_fingerprint: str = Field(min_length=1, max_length=200)


class ResearchFailedEvent(ResearchEventBase):
    event_type: Literal["research.failed"] = "research.failed"
    code: ResearchErrorCode
    safe_message: str = Field(min_length=1, max_length=2_000)
    retryable: bool


type ResearchEvent = (
    ResearchAcceptedEvent
    | ResearchProgressEvent
    | ResearchArtifactEvent
    | ResearchCompletedEvent
    | ResearchFailedEvent
)
