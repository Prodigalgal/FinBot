from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictFloat,
    StrictInt,
    field_validator,
    model_validator,
)
from pydantic.alias_generators import to_camel

from finbot_quant.models import (
    ArtifactKind,
    ArtifactReference,
    Exchange,
    ExchangeEnvironment,
    Instrument,
    MarketType,
    ResearchJob,
    ResearchKind,
    ResearchParameter,
)


class ContractModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
    )


class InstrumentRequest(ContractModel):
    exchange: Exchange
    environment: ExchangeEnvironment
    symbol: str = Field(pattern=r"^[A-Z0-9_]{2,32}$")
    market_type: MarketType
    quote_currency: str = Field(pattern=r"^[A-Z0-9]{2,12}$")


class TimeRangeRequest(ContractModel):
    start_inclusive: datetime
    end_exclusive: datetime

    @model_validator(mode="after")
    def validate_order(self) -> TimeRangeRequest:
        if self.start_inclusive.tzinfo is None or self.end_exclusive.tzinfo is None:
            raise ValueError("time range timestamps must include a timezone")
        if self.start_inclusive >= self.end_exclusive:
            raise ValueError("startInclusive must be before endExclusive")
        return self


class ArtifactReferenceModel(ContractModel):
    kind: ArtifactKind
    uri: str = Field(min_length=1, max_length=1_000)
    sha256_hex: str = Field(pattern=r"^[a-f0-9]{64}$")
    media_type: str = Field(min_length=1, max_length=120)
    byte_size: int = Field(ge=0)

    def to_domain(self) -> ArtifactReference:
        return ArtifactReference(
            self.kind,
            self.uri,
            self.sha256_hex,
            self.media_type,
            self.byte_size,
        )


class ParameterBase(ContractModel):
    name: str = Field(min_length=1, max_length=120, pattern=r"^[a-z][a-z0-9_.-]*$")


class BooleanParameter(ParameterBase):
    value_type: Literal["BOOLEAN"]
    value: StrictBool


class IntegerParameter(ParameterBase):
    value_type: Literal["INTEGER"]
    value: StrictInt


class FloatingParameter(ParameterBase):
    value_type: Literal["FLOATING"]
    value: StrictFloat


class DecimalParameter(ParameterBase):
    value_type: Literal["DECIMAL"]
    value: Decimal

    @field_validator("value", mode="before")
    @classmethod
    def require_decimal_string(cls, value: object) -> object:
        if not isinstance(value, str):
            raise ValueError("decimal parameter value must be a JSON string")
        return value


class TextParameter(ParameterBase):
    value_type: Literal["TEXT"]
    value: str = Field(max_length=2_000)


type ParameterRequest = Annotated[
    BooleanParameter | IntegerParameter | FloatingParameter | DecimalParameter | TextParameter,
    Field(discriminator="value_type"),
]


class ResearchSpecificationRequest(ContractModel):
    kind: ResearchKind
    instruments: tuple[InstrumentRequest, ...] = Field(min_length=1, max_length=100)
    time_range: TimeRangeRequest
    market_data: ArtifactReferenceModel
    strategy_id: str = Field(min_length=1, max_length=120)
    strategy_version: str = Field(min_length=1, max_length=80)
    parameters: tuple[ParameterRequest, ...] = Field(max_length=200)
    deterministic_seed: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_market_data(self) -> ResearchSpecificationRequest:
        if self.market_data.kind is not ArtifactKind.INPUT_MARKET_DATA:
            raise ValueError("marketData.kind must be INPUT_MARKET_DATA")
        return self


class StartResearchRequest(ContractModel):
    research_run_id: str = Field(pattern=r"^research_[a-z0-9_-]{4,70}$")
    workflow_run_id: str = Field(pattern=r"^run_[a-z0-9_-]{4,75}$")
    specification: ResearchSpecificationRequest
    requested_at: datetime

    @model_validator(mode="after")
    def validate_requested_at(self) -> StartResearchRequest:
        if self.requested_at.tzinfo is None:
            raise ValueError("requestedAt must include a timezone")
        return self

    def to_domain(self, idempotency_key: str) -> ResearchJob:
        specification = self.specification
        return ResearchJob(
            research_run_id=self.research_run_id,
            workflow_run_id=self.workflow_run_id,
            idempotency_key=idempotency_key,
            kind=specification.kind,
            instruments=tuple(
                Instrument(
                    value.exchange,
                    value.environment,
                    value.symbol,
                    value.market_type,
                    value.quote_currency,
                )
                for value in specification.instruments
            ),
            start_inclusive=specification.time_range.start_inclusive,
            end_exclusive=specification.time_range.end_exclusive,
            market_data=specification.market_data.to_domain(),
            strategy_id=specification.strategy_id,
            strategy_version=specification.strategy_version,
            parameters=tuple(
                ResearchParameter(value.name, value.value) for value in specification.parameters
            ),
            deterministic_seed=specification.deterministic_seed,
            requested_at=self.requested_at,
        )


class HealthResponse(ContractModel):
    status: Literal["UP"] = "UP"
    contract_version: Literal["1.0.0"] = "1.0.0"


class CapabilityDescriptor(ContractModel):
    capability_id: str
    description: str


class QuantCapabilitiesResponse(ContractModel):
    contract_version: Literal["1.0.0"] = "1.0.0"
    strategies: tuple[CapabilityDescriptor, ...]
    indicators: tuple[CapabilityDescriptor, ...]
