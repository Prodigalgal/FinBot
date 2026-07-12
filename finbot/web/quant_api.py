from __future__ import annotations

from datetime import datetime
from typing import Callable, Literal

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from finbot.backtest import (
    BacktestPosition,
    ExecutionBacktestConfig,
    ExecutionBacktestEngine,
    MarketBar,
    ReturnObservation,
    StrategyValidationEngine,
    ValidationConfig,
)
from finbot.experiments import ExperimentDefinition, ExperimentRegistry, ExperimentRun
from finbot.execution import OmsRepository
from finbot.risk.margin import (
    LinearContractRiskSpec,
    MarginRiskEngine,
    PositionRiskRequest,
)
from finbot.risk import (
    ExecutionRiskPolicy,
    ExecutionRiskSnapshot,
    PnlAttributionRecord,
    attribute_pnl,
    evaluate_execution_risk,
)
from finbot.storage.sqlite_store import SQLiteStore


class ContractRiskSpecPayload(BaseModel):
    venue: str = Field(min_length=1, max_length=40)
    symbol: str = Field(min_length=1, max_length=80)
    contract_multiplier: float = Field(gt=0)
    min_quantity: float = Field(gt=0)
    quantity_step: float = Field(gt=0)
    min_notional_usdt: float = Field(gt=0)
    min_leverage: float = Field(gt=0)
    max_leverage: float = Field(gt=0)
    leverage_step: float = Field(gt=0)
    maintenance_margin_rate: float = Field(ge=0, lt=1)
    taker_fee_rate: float = Field(ge=0, lt=1)

    def domain(self) -> LinearContractRiskSpec:
        return LinearContractRiskSpec(**self.model_dump())


class LeverageRiskPayload(BaseModel):
    side: Literal["BUY", "SELL"]
    entry_price: float = Field(gt=0)
    stop_price: float = Field(gt=0)
    risk_budget_usdt: float = Field(gt=0)
    requested_leverage: float | None = Field(default=None, gt=0)
    slippage_rate: float = Field(default=0.0005, ge=0, lt=1)
    liquidation_safety_buffer_rate: float = Field(default=0.001, ge=0, lt=1)
    environment: Literal["paper", "testnet", "demo", "mainnet", "live"] = "paper"


class LeveragePreviewPayload(BaseModel):
    contract: ContractRiskSpecPayload
    risk: LeverageRiskPayload


class MarketBarPayload(BaseModel):
    timestamp: datetime
    open: float = Field(gt=0)
    high: float = Field(gt=0)
    low: float = Field(gt=0)
    close: float = Field(gt=0)
    mark_price: float | None = Field(default=None, gt=0)
    funding_rate: float = 0.0

    def domain(self) -> MarketBar:
        return MarketBar(**self.model_dump())


class BacktestPositionPayload(BaseModel):
    side: Literal["BUY", "SELL"]
    quantity: float = Field(gt=0)
    leverage: float = Field(gt=0)
    stop_price: float | None = Field(default=None, gt=0)
    take_profit_price: float | None = Field(default=None, gt=0)
    max_holding_bars: int | None = Field(default=None, ge=1, le=100_000)
    environment: Literal["paper", "testnet", "demo", "mainnet", "live"] = "paper"


class ExecutionBacktestPayload(BaseModel):
    contract: ContractRiskSpecPayload
    position: BacktestPositionPayload
    bars: list[MarketBarPayload] = Field(min_length=1, max_length=5_000)
    slippage_rate: float = Field(default=0.0005, ge=0, lt=1)
    conservative_intrabar_collision: bool = True


class ReturnObservationPayload(BaseModel):
    timestamp: datetime
    net_return: float = Field(gt=-1, lt=10)
    benchmark_return: float = Field(default=0, gt=-1, lt=10)
    signal_timestamp: datetime | None = None
    execution_timestamp: datetime | None = None

    def domain(self) -> ReturnObservation:
        return ReturnObservation(**self.model_dump())


class StrategyVariantPayload(BaseModel):
    variant_id: str = Field(min_length=1, max_length=100)
    observations: list[ReturnObservationPayload] = Field(min_length=1, max_length=20_000)


class StrategyValidationPayload(BaseModel):
    control_variant: str = Field(min_length=1, max_length=100)
    variants: list[StrategyVariantPayload] = Field(min_length=1, max_length=50)
    initial_train_size: int = Field(default=30, ge=10, le=10_000)
    test_size: int = Field(default=10, ge=5, le=5_000)
    periods_per_year: int = Field(default=365, ge=1, le=525_600)
    monte_carlo_paths: int = Field(default=500, ge=100, le=10_000)
    random_seed: int = 20260712


class ExperimentDefinitionPayload(BaseModel):
    experiment_id: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    control_variant: str = Field(min_length=1, max_length=100)
    challenger_variants: list[str] = Field(default_factory=list, max_length=50)
    data_version: str = Field(min_length=1, max_length=200)
    workflow_version: str | None = Field(default=None, max_length=200)
    model_version: str | None = Field(default=None, max_length=200)
    status: Literal["draft", "active", "paused", "completed"] = "active"


class ExperimentRunPayload(BaseModel):
    run_id: str = Field(min_length=1, max_length=200)
    variant_id: str = Field(min_length=1, max_length=100)
    input_hash: str = Field(min_length=1, max_length=200)
    data_version: str = Field(min_length=1, max_length=200)
    random_seed: int
    status: Literal["passed", "failed", "blocked", "unavailable"]
    metrics: dict[str, object] = Field(default_factory=dict)
    config: dict[str, object] = Field(default_factory=dict)
    created_at: datetime


class PnlAttributionPayload(BaseModel):
    records: list[dict[str, object]] = Field(default_factory=list, max_length=20_000)


class ExecutionRiskGatePayload(BaseModel):
    current_equity_usdt: float = Field(gt=0)
    peak_equity_usdt: float = Field(gt=0)
    day_start_equity_usdt: float = Field(gt=0)
    realized_pnl_today_usdt: float = 0
    unrealized_pnl_usdt: float = 0
    consecutive_losses: int = Field(default=0, ge=0)
    proposed_max_loss_usdt: float = Field(ge=0)
    proposed_gross_exposure_usdt: float = Field(ge=0)
    liquidation_distance_pct: float = Field(ge=0)
    environment: Literal["paper", "testnet", "demo", "mainnet", "live"] = "paper"
    policy: dict[str, float | int] | None = None


def quant_router(store_provider: Callable[[], SQLiteStore] | None = None) -> APIRouter:
    router = APIRouter(prefix="/api/v1/quant", tags=["quant"])

    @router.post("/risk/leverage-preview")
    async def leverage_preview(payload: LeveragePreviewPayload) -> dict[str, object]:
        try:
            request = PositionRiskRequest(**payload.risk.model_dump())
            plan = MarginRiskEngine().plan(payload.contract.domain(), request)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return plan.to_dict()

    @router.post("/risk/execution-gate")
    async def execution_risk_gate(payload: ExecutionRiskGatePayload) -> dict[str, object]:
        try:
            policy = ExecutionRiskPolicy(**payload.policy) if payload.policy else ExecutionRiskPolicy()
            snapshot = ExecutionRiskSnapshot(**payload.model_dump(exclude={"policy"}))
            return evaluate_execution_risk(snapshot, policy)
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/backtests/execution")
    async def execution_backtest(payload: ExecutionBacktestPayload) -> dict[str, object]:
        try:
            result = ExecutionBacktestEngine().run(
                spec=payload.contract.domain(),
                position=BacktestPosition(**payload.position.model_dump()),
                bars=[bar.domain() for bar in payload.bars],
                config=ExecutionBacktestConfig(
                    slippage_rate=payload.slippage_rate,
                    conservative_intrabar_collision=payload.conservative_intrabar_collision,
                ),
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return result.to_dict()

    @router.post("/backtests/validate")
    async def validate_strategy(payload: StrategyValidationPayload) -> dict[str, object]:
        variant_ids = [variant.variant_id for variant in payload.variants]
        if len(variant_ids) != len(set(variant_ids)):
            raise HTTPException(status_code=400, detail="variant_id 不能重复")
        try:
            return StrategyValidationEngine().validate(
                variants={
                    variant.variant_id: [observation.domain() for observation in variant.observations]
                    for variant in payload.variants
                },
                control_variant=payload.control_variant,
                config=ValidationConfig(
                    initial_train_size=payload.initial_train_size,
                    test_size=payload.test_size,
                    periods_per_year=payload.periods_per_year,
                    monte_carlo_paths=payload.monte_carlo_paths,
                    random_seed=payload.random_seed,
                ),
            )
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @router.post("/pnl/attribution")
    async def pnl_attribution(payload: PnlAttributionPayload) -> dict[str, object]:
        try:
            records = [PnlAttributionRecord(**record) for record in payload.records]
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return attribute_pnl(records)

    if store_provider is not None:
        @router.put("/experiments/{experiment_id}")
        async def save_experiment(experiment_id: str, payload: ExperimentDefinitionPayload) -> dict[str, object]:
            if experiment_id != payload.experiment_id:
                raise HTTPException(status_code=400, detail="path experiment_id 与 payload 不一致")
            try:
                definition = ExperimentDefinition(
                    **{
                        **payload.model_dump(),
                        "challenger_variants": tuple(payload.challenger_variants),
                    }
                )
                ExperimentRegistry(store_provider()).save_definition(definition)
            except ValueError as exc:
                raise HTTPException(status_code=400, detail=str(exc)) from exc
            return {"status": "saved", "experiment": payload.model_dump()}

        @router.post("/experiments/{experiment_id}/runs")
        async def record_experiment_run(experiment_id: str, payload: ExperimentRunPayload) -> dict[str, object]:
            try:
                run = ExperimentRegistry(store_provider()).record_run(
                    ExperimentRun(
                        experiment_id=experiment_id,
                        **{**payload.model_dump(), "created_at": payload.created_at.isoformat()},
                    )
                )
            except KeyError as exc:
                raise HTTPException(status_code=404, detail=str(exc)) from exc
            except ValueError as exc:
                raise HTTPException(status_code=400, detail=str(exc)) from exc
            return {"status": "saved", "run_id": run.run_id}

        @router.get("/experiments/{experiment_id}/comparison")
        async def experiment_comparison(experiment_id: str, input_hash: str) -> dict[str, object]:
            try:
                return ExperimentRegistry(store_provider()).comparison(experiment_id, input_hash)
            except KeyError as exc:
                raise HTTPException(status_code=404, detail=str(exc)) from exc

        @router.get("/oms/orders")
        async def oms_orders(limit: int = 100) -> dict[str, object]:
            repository = OmsRepository(store_provider())
            orders = repository.list_orders(limit=limit)
            return {
                "status": "ok",
                "count": len(orders),
                "orders": [order.to_dict() for order in orders],
                "policy": {"environment": "paper/testnet/demo", "mainnet_allowed": False},
            }

        @router.get("/oms/orders/{order_id}/events")
        async def oms_order_events(order_id: str) -> dict[str, object]:
            repository = OmsRepository(store_provider())
            order = repository.get(order_id)
            if order is None:
                raise HTTPException(status_code=404, detail="OMS order 不存在")
            events = repository.list_events(order_id)
            return {
                "status": "ok",
                "order": order.to_dict(),
                "events": [event.to_dict() for event in events],
            }

    return router
