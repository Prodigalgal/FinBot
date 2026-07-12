from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Protocol

from finbot.execution import OmsRepository, OmsService, OrderStatus
from finbot.instruments.models import normalize_alias, stable_id
from finbot.storage.sqlite_store import SQLiteStore


DIRECTIONAL_ACTIONS = frozenset({"BUY", "SELL"})
EXECUTABLE_MARKET_TYPES = frozenset({"linear", "future", "perpetual"})


class PaperExecutionBlocked(RuntimeError):
    pass


@dataclass(frozen=True)
class PaperExecutionPolicy:
    submit_orders: bool = False
    require_human_review: bool = False
    max_orders_per_adapter: int = 1
    max_notional_usdt: float = 100.0
    min_confidence: float = 0.70
    max_workers: int = 2

    def __post_init__(self) -> None:
        if not 1 <= self.max_orders_per_adapter <= 20:
            raise ValueError("max_orders_per_adapter 必须在 1 到 20 之间")
        if not 1.0 <= self.max_notional_usdt <= 100_000.0:
            raise ValueError("max_notional_usdt 必须在 1 到 100000 之间")
        if not 0.0 <= self.min_confidence <= 1.0:
            raise ValueError("min_confidence 必须在 0 到 1 之间")
        if not 1 <= self.max_workers <= 8:
            raise ValueError("max_workers 必须在 1 到 8 之间")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class PreparedPaperOrder:
    adapter_id: str
    provider: str
    environment: str
    product_id: str | None
    instrument_id: str
    symbol: str
    market_type: str
    action: str
    client_order_id: str
    requested_notional: float
    requested_quantity: float
    request: dict[str, Any]
    metadata: dict[str, Any]


@dataclass(frozen=True)
class PaperSubmissionResult:
    status: str
    exchange_order_id: str | None
    response: dict[str, Any]
    filled_quantity: float | None = None
    average_fill_price: float | None = None
    error: str | None = None


class PaperExecutionAdapter(Protocol):
    adapter_id: str
    provider: str
    environment: str
    market_types: frozenset[str]

    def readiness(self) -> dict[str, Any]: ...

    def prepare_order(
        self,
        *,
        instrument: dict[str, Any],
        decision: dict[str, Any],
        max_notional_usdt: float,
    ) -> PreparedPaperOrder: ...

    def submit_order(self, order: PreparedPaperOrder) -> PaperSubmissionResult: ...

    def close(self) -> None: ...


class MultiExchangePaperExecutionEngine:
    def __init__(self, store: SQLiteStore, adapters: tuple[PaperExecutionAdapter, ...]):
        self.store = store
        self.adapters = adapters
        self.store.init_schema()
        self.oms = OmsService(OmsRepository(store))

    def execute(
        self,
        *,
        loop_run_id: str,
        decisions: list[dict[str, Any]],
        portfolio_risk: dict[str, Any] | None,
        ai_governance: dict[str, Any] | None,
        policy: PaperExecutionPolicy,
    ) -> dict[str, Any]:
        created_at = _now()
        execution_run_id = stable_id("paper-execution-run", loop_run_id)
        run = {
            "execution_run_id": execution_run_id,
            "loop_run_id": loop_run_id,
            "status": "running",
            "config": {
                **policy.to_dict(),
                "adapter_ids": [adapter.adapter_id for adapter in self.adapters],
            },
            "summary": {},
            "created_at": created_at,
            "finished_at": None,
        }
        self.store.insert_paper_execution_run(run)

        gate_reasons = _global_gate_reasons(portfolio_risk or {}, ai_governance or {})
        if gate_reasons:
            return self._finish_run(run, "blocked", [], gate_reasons)

        eligible, decision_rejections = _eligible_decisions(
            decisions,
            policy.min_confidence,
            policy.require_human_review,
        )
        instruments = [dict(row) for row in self.store.list_venue_instruments(active_only=True)]
        prepared: list[tuple[PreparedPaperOrder, dict[str, Any], PaperExecutionAdapter, str]] = []
        execution_rows: list[dict[str, Any]] = []

        for adapter in self.adapters:
            adapter_decisions = eligible[: policy.max_orders_per_adapter]
            for decision in adapter_decisions:
                instrument = _match_instrument(instruments, adapter, decision)
                if instrument is None:
                    execution_rows.append(
                        self._record_rejection(
                            run,
                            adapter,
                            decision,
                            "skipped_unmapped",
                            "未找到该交易所的活跃 USDT 永续合约映射",
                        )
                    )
                    continue
                try:
                    order = adapter.prepare_order(
                        instrument=instrument,
                        decision=decision,
                        max_notional_usdt=policy.max_notional_usdt,
                    )
                except (PaperExecutionBlocked, ValueError) as exc:
                    execution_rows.append(
                        self._record_rejection(run, adapter, decision, "skipped_policy", str(exc), instrument)
                    )
                    continue
                execution = _execution_record(run, decision, order, "planned")
                if not self.store.insert_paper_execution_if_absent(execution):
                    existing = self.store.get_paper_execution(str(decision["decision_id"]), adapter.adapter_id)
                    existing_payload = _execution_payload(existing) if existing is not None else execution
                    retryable_statuses = {"dry_run", "blocked_adapter", "failed", "rejected"}
                    if not policy.submit_orders or str(existing_payload.get("status")) not in retryable_statuses:
                        execution_rows.append(existing_payload)
                        continue
                    execution["execution_id"] = existing_payload.get("execution_id") or execution["execution_id"]
                oms_order = self.oms.plan_order(
                    idempotency_key=f"paper-plan:{execution['execution_id']}",
                    client_order_id=order.client_order_id,
                    venue=order.provider,
                    environment=order.environment,
                    symbol=order.symbol,
                    side=order.action,
                    requested_quantity=order.requested_quantity,
                    metadata={
                        "paper_execution_id": execution["execution_id"],
                        "decision_id": execution["decision_id"],
                        "loop_run_id": execution["loop_run_id"],
                    },
                )
                if not policy.submit_orders:
                    self.store.update_paper_execution(
                        execution["execution_id"],
                        status="dry_run",
                        request=order.request,
                        requested_notional=order.requested_notional,
                        requested_quantity=order.requested_quantity,
                        updated_at=_now(),
                    )
                    saved = self.store.get_paper_execution(str(decision["decision_id"]), adapter.adapter_id)
                    execution_rows.append(_execution_payload(saved))
                    continue
                readiness = adapter.readiness()
                if readiness["status"] != "ready":
                    reason = "; ".join(readiness.get("blockers") or ["adapter 未就绪"])
                    self.store.update_paper_execution(
                        execution["execution_id"],
                        status="blocked_adapter",
                        request=order.request,
                        requested_notional=order.requested_notional,
                        requested_quantity=order.requested_quantity,
                        error=reason,
                        updated_at=_now(),
                    )
                    saved = self.store.get_paper_execution(str(decision["decision_id"]), adapter.adapter_id)
                    execution_rows.append(_execution_payload(saved))
                    self.oms.transition(
                        oms_order.order_id,
                        to_status=OrderStatus.REJECTED,
                        idempotency_key=f"paper-adapter-blocked:{execution['execution_id']}",
                        reason=reason,
                    )
                    continue
                submitted = self.oms.transition(
                    oms_order.order_id,
                    to_status=OrderStatus.SUBMITTED,
                    idempotency_key=f"paper-submit:{execution['execution_id']}",
                )
                prepared.append((order, execution, adapter, submitted.order_id))

        if prepared:
            worker_count = min(policy.max_workers, len(prepared), len(self.adapters) or 1)
            with ThreadPoolExecutor(max_workers=worker_count, thread_name_prefix="finbot-paper") as executor:
                future_map = {
                    executor.submit(adapter.submit_order, order): (order, execution, adapter, oms_order_id)
                    for order, execution, adapter, oms_order_id in prepared
                }
                for future in as_completed(future_map):
                    order, execution, adapter, oms_order_id = future_map[future]
                    try:
                        result = future.result()
                    except Exception as exc:
                        result = PaperSubmissionResult(
                            status="failed",
                            exchange_order_id=None,
                            response={},
                            error=f"{type(exc).__name__}: {exc}",
                        )
                    self.store.update_paper_execution(
                        execution["execution_id"],
                        status=result.status,
                        request=order.request,
                        response=result.response,
                        exchange_order_id=result.exchange_order_id,
                        requested_notional=order.requested_notional,
                        requested_quantity=order.requested_quantity,
                        filled_quantity=result.filled_quantity,
                        average_fill_price=result.average_fill_price,
                        error=result.error,
                        updated_at=_now(),
                    )
                    oms_status = _oms_result_status(result.status)
                    if oms_status is not None and oms_status is not OrderStatus.SUBMITTED:
                        self.oms.transition(
                            oms_order_id,
                            to_status=oms_status,
                            idempotency_key=f"paper-result:{execution['execution_id']}:{result.status}",
                            filled_quantity=result.filled_quantity,
                            average_fill_price=result.average_fill_price,
                            exchange_order_id=result.exchange_order_id,
                            reason=result.error,
                            payload={"adapter_status": result.status},
                        )
                    saved = self.store.get_paper_execution(execution["decision_id"], adapter.adapter_id)
                    execution_rows.append(_execution_payload(saved))

        persisted_rows = [
            _execution_payload(row)
            for row in self.store.list_paper_executions(loop_run_id=loop_run_id, limit=500)
        ]
        aggregated_rows = list(
            {
                str(item.get("execution_id")): item
                for item in [*persisted_rows, *execution_rows]
                if item.get("execution_id")
            }.values()
        )
        status = _run_status(aggregated_rows, policy.submit_orders)
        return self._finish_run(run, status, aggregated_rows, decision_rejections)

    def close(self) -> None:
        for adapter in self.adapters:
            adapter.close()

    def _record_rejection(
        self,
        run: dict[str, Any],
        adapter: PaperExecutionAdapter,
        decision: dict[str, Any],
        status: str,
        reason: str,
        instrument: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        symbol = str((instrument or {}).get("symbol") or decision.get("symbol") or "UNKNOWN")
        execution = {
            "execution_id": stable_id("paper-execution", decision["decision_id"], adapter.adapter_id),
            "execution_run_id": run["execution_run_id"],
            "loop_run_id": run["loop_run_id"],
            "decision_id": str(decision["decision_id"]),
            "adapter_id": adapter.adapter_id,
            "provider": adapter.provider,
            "environment": adapter.environment,
            "product_id": (instrument or {}).get("product_id"),
            "instrument_id": (instrument or {}).get("instrument_id"),
            "symbol": symbol,
            "market_type": str((instrument or {}).get("market_type") or "perpetual"),
            "action": str(decision.get("action") or "WATCH").upper(),
            "status": status,
            "client_order_id": stable_id("paper-client-order", decision["decision_id"], adapter.adapter_id)[:28],
            "request": {},
            "response": {},
            "error": reason,
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.store.insert_paper_execution_if_absent(execution)
        existing = self.store.get_paper_execution(str(decision["decision_id"]), adapter.adapter_id)
        return _execution_payload(existing) if existing is not None else execution

    def _finish_run(
        self,
        run: dict[str, Any],
        status: str,
        executions: list[dict[str, Any]],
        reasons: list[str],
    ) -> dict[str, Any]:
        finished_at = _now()
        summary = _execution_summary(executions, reasons)
        completed = {**run, "status": status, "summary": summary, "finished_at": finished_at}
        self.store.insert_paper_execution_run(completed)
        return {
            "status": status,
            "execution_run_id": run["execution_run_id"],
            "loop_run_id": run["loop_run_id"],
            "created_at": run["created_at"],
            "finished_at": finished_at,
            "config": run["config"],
            "summary": summary,
            "executions": executions,
            "policy": {
                "real_trading_allowed": False,
                "mainnet_private_api_allowed": False,
                "simulated_order_api_allowed": True,
                "ai_controls_order_size": False,
            },
        }


def _global_gate_reasons(portfolio_risk: dict[str, Any], ai_governance: dict[str, Any]) -> list[str]:
    reasons: list[str] = []
    risk_status = str((portfolio_risk.get("risk_gate") or {}).get("status") or "").lower()
    governance_status = str((ai_governance.get("summary") or {}).get("governance_status") or "").lower()
    if risk_status not in {"passed", "warning"}:
        reasons.append("Portfolio Risk 门禁未通过")
    if governance_status != "passed":
        reasons.append("AI Governance 门禁未通过")
    return reasons


def _oms_result_status(status: str) -> OrderStatus | None:
    normalized = status.lower()
    if normalized == "filled":
        return OrderStatus.FILLED
    if normalized == "partial":
        return OrderStatus.PARTIAL
    if normalized in {"failed", "rejected", "blocked_adapter", "skipped_existing_position"}:
        return OrderStatus.REJECTED
    if normalized in {"cancelled", "canceled"}:
        return OrderStatus.CANCELLED
    if normalized == "expired":
        return OrderStatus.EXPIRED
    return OrderStatus.SUBMITTED if normalized in {"open", "submitted"} else None


def _eligible_decisions(
    decisions: list[dict[str, Any]],
    min_confidence: float,
    require_human_review: bool,
) -> tuple[list[dict[str, Any]], list[str]]:
    eligible: list[dict[str, Any]] = []
    rejections: list[str] = []
    non_directional_count = 0
    for decision in decisions:
        if not isinstance(decision, dict):
            continue
        decision_id = str(decision.get("decision_id") or "")
        action = str(decision.get("action") or "").upper()
        confidence = _float(decision.get("confidence"), 0.0)
        market_type = str(decision.get("market_type") or "").lower()
        if not decision_id:
            continue
        if action not in DIRECTIONAL_ACTIONS:
            non_directional_count += 1
            continue
        if str(decision.get("status") or "").lower() != "candidate":
            rejections.append(f"{decision_id}: 决策状态不是 candidate")
            continue
        if require_human_review and str(decision.get("human_review_status") or "").lower() != "approved":
            rejections.append(f"{decision_id}: 尚未通过人工复核")
            continue
        if confidence < min_confidence:
            rejections.append(f"{decision_id}: 置信度 {confidence:.2f} 低于 {min_confidence:.2f}")
            continue
        if market_type not in EXECUTABLE_MARKET_TYPES:
            rejections.append(f"{decision_id}: 仅支持永续/Linear 决策")
            continue
        if not _valid_directional_levels(decision, action):
            rejections.append(f"{decision_id}: target/invalidation 与方向不一致")
            continue
        eligible.append(decision)
    if non_directional_count:
        rejections.append(f"{non_directional_count} 条 AI 决策为 WATCH/HOLD，未进入模拟执行")
    if not decisions:
        rejections.append("本轮没有 AI 交易决策，未生成模拟订单")
    eligible.sort(key=lambda item: (-_float(item.get("confidence"), 0.0), -_float(item.get("score"), 0.0), str(item["decision_id"])))
    return eligible, rejections


def _valid_directional_levels(decision: dict[str, Any], action: str) -> bool:
    entry = _positive_float(decision.get("entry_reference"))
    target = _positive_float(decision.get("target_price"))
    invalidation = _positive_float(decision.get("invalidation_price"))
    if entry is None or target is None or invalidation is None:
        return False
    return target > entry > invalidation if action == "BUY" else target < entry < invalidation


def _match_instrument(
    instruments: list[dict[str, Any]],
    adapter: PaperExecutionAdapter,
    decision: dict[str, Any],
) -> dict[str, Any] | None:
    alias = normalize_alias(str(decision.get("normalized_symbol") or decision.get("symbol") or ""))
    matches = [
        instrument
        for instrument in instruments
        if str(instrument.get("provider") or "").lower() == adapter.provider
        and str(instrument.get("market_type") or "").lower() in adapter.market_types
        and normalize_alias(str(instrument.get("normalized_symbol") or instrument.get("symbol") or "")) == alias
        and str(instrument.get("quote_asset") or "").upper() == "USDT"
        and bool(instrument.get("contract"))
    ]
    matches.sort(key=lambda item: (-_float(item.get("turnover_24h"), 0.0), str(item.get("instrument_id") or "")))
    return matches[0] if matches else None


def _execution_record(
    run: dict[str, Any],
    decision: dict[str, Any],
    order: PreparedPaperOrder,
    status: str,
) -> dict[str, Any]:
    now = _now()
    return {
        "execution_id": stable_id("paper-execution", decision["decision_id"], order.adapter_id),
        "execution_run_id": run["execution_run_id"],
        "loop_run_id": run["loop_run_id"],
        "decision_id": str(decision["decision_id"]),
        "adapter_id": order.adapter_id,
        "provider": order.provider,
        "environment": order.environment,
        "product_id": order.product_id,
        "instrument_id": order.instrument_id,
        "symbol": order.symbol,
        "market_type": order.market_type,
        "action": order.action,
        "status": status,
        "client_order_id": order.client_order_id,
        "requested_notional": order.requested_notional,
        "requested_quantity": order.requested_quantity,
        "request": order.request,
        "response": {},
        "error": None,
        "created_at": now,
        "updated_at": now,
    }


def _execution_payload(row: Any) -> dict[str, Any]:
    if row is None:
        return {}
    payload = dict(row)
    payload["request"] = _loads(payload.pop("request_json", "{}"), {})
    payload["response"] = _loads(payload.pop("response_json", "{}"), {})
    return payload


def _execution_summary(executions: list[dict[str, Any]], reasons: list[str]) -> dict[str, Any]:
    status_counts: dict[str, int] = {}
    adapter_counts: dict[str, int] = {}
    for execution in executions:
        status = str(execution.get("status") or "unknown")
        adapter = str(execution.get("adapter_id") or "unknown")
        status_counts[status] = status_counts.get(status, 0) + 1
        adapter_counts[adapter] = adapter_counts.get(adapter, 0) + 1
    return {
        "execution_count": len(executions),
        "status_counts": status_counts,
        "adapter_counts": adapter_counts,
        "reason_count": len(reasons),
        "reasons": reasons[:20],
    }


def _run_status(executions: list[dict[str, Any]], submit_orders: bool) -> str:
    if not executions:
        return "passed"
    statuses = {str(item.get("status") or "") for item in executions}
    if not submit_orders:
        return "passed"
    successes = {"submitted", "open", "filled"}
    failures = {"failed", "rejected", "blocked_adapter"}
    if statuses & successes and statuses & failures:
        return "partial"
    if statuses & successes:
        return "passed"
    if statuses <= {"skipped_existing_position", "skipped_unmapped", "skipped_policy"}:
        return "passed"
    return "blocked"


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _positive_float(value: Any) -> float | None:
    numeric = _float(value, 0.0)
    return numeric if numeric > 0 else None


def _float(value: Any, default: float) -> float:
    try:
        return float(value) if value is not None else default
    except (TypeError, ValueError):
        return default


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
