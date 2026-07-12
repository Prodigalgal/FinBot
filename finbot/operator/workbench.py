from __future__ import annotations

import asyncio
import hashlib
import math
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

from finbot.advisory.engine import AdvisoryConfig, AdvisoryEngine, config_to_dict
from finbot.market.public_exchanges import MarketCandle, MarketDataProviderBlocked, PublicExchangeMarketDataClient, rate_limit_policy_snapshot, supported_providers
from finbot.network.proxy_router import ProxyRouteBlocked
from finbot.operator.paper_ledger import PaperLedgerConfig, PaperLedgerPlanner, paper_config_to_dict
from finbot.storage.sqlite_store import SQLiteStore


MIN_PRIMARY_CANDLES = 2


@dataclass(frozen=True)
class ProviderSpec:
    provider: str
    market_type: str = "spot"


@dataclass(frozen=True)
class InstrumentSelection:
    provider: str
    market_type: str
    symbol: str
    instrument_id: str | None = None
    normalized_symbol: str | None = None
    base_asset: str | None = None


@dataclass(frozen=True)
class OperatorWorkbenchConfig:
    symbols: tuple[str, ...] = ("BTCUSDT",)
    providers: tuple[ProviderSpec, ...] = (
        ProviderSpec("gate", "spot"),
    )
    instruments: tuple[InstrumentSelection, ...] = ()
    data_source: str = "live_public"
    execution_mode: str = "advisory_only"
    intervals: tuple[str, ...] = ("1h", "4h", "1d")
    candle_limit: int = 60
    timeout_seconds: float = 20.0
    persist: bool = True
    include_research_context: bool = True
    paper_ledger: PaperLedgerConfig = PaperLedgerConfig()
    advisory: AdvisoryConfig = AdvisoryConfig()

    def to_dict(self) -> dict[str, Any]:
        return {
            "symbols": list(self.symbols),
            "providers": [provider.__dict__ for provider in self.providers],
            "instruments": [instrument.__dict__ for instrument in self.instruments],
            "data_source": self.data_source,
            "execution_mode": self.execution_mode,
            "intervals": list(self.intervals),
            "candle_limit": self.candle_limit,
            "timeout_seconds": self.timeout_seconds,
            "persist": self.persist,
            "include_research_context": self.include_research_context,
            "paper_ledger": paper_config_to_dict(self.paper_ledger),
            "advisory": config_to_dict(self.advisory),
        }


class OperatorWorkbenchBuilder:
    def __init__(
        self,
        store: SQLiteStore,
        market_client: PublicExchangeMarketDataClient | None = None,
        advisory_engine: AdvisoryEngine | None = None,
        paper_ledger: PaperLedgerPlanner | None = None,
    ):
        self.store = store
        self.market_client = market_client or PublicExchangeMarketDataClient()
        self.advisory_engine = advisory_engine or AdvisoryEngine()
        self.paper_ledger = paper_ledger or PaperLedgerPlanner()

    async def build(self, config: OperatorWorkbenchConfig) -> dict[str, Any]:
        self.store.init_schema()
        generated_at = _now()
        research_context = self._latest_research_context() if config.include_research_context else {}
        targets = (
            [
                (instrument.symbol, ProviderSpec(instrument.provider, instrument.market_type), instrument.instrument_id)
                for instrument in config.instruments
            ]
            if config.instruments
            else [
                (symbol, provider_spec, None)
                for symbol in config.symbols
                for provider_spec in config.providers
            ]
        )
        tasks = [
            self._build_symbol_provider(symbol, provider_spec, config, research_context, instrument_id)
            for symbol, provider_spec, instrument_id in targets
        ]
        results = await asyncio.gather(*tasks)
        ok_items = [result for result in results if result["status"] == "ok"]
        failed_items = [result for result in results if result["status"] != "ok"]
        advice_items = [result["advice"] for result in ok_items if result.get("advice")]
        report_id = self._report_id(config, generated_at, results)
        paper_proposals = self._build_paper_proposals(report_id, advice_items, config)
        report = {
            "report_id": report_id,
            "version": "phase6-operator-workbench-v1",
            "profile": config.advisory.profile,
            "status": "partial" if failed_items and ok_items else "failed" if failed_items else "ok",
            "generated_at": generated_at,
            "config": config.to_dict(),
            "summary": self._summary(advice_items, failed_items, paper_proposals, config),
            "research_context": research_context,
            "items": results,
            "paper_proposals": paper_proposals,
            "rate_limit": {
                "policies": rate_limit_policy_snapshot(),
                "observations": _request_observations(self.market_client),
            },
            "policy": {
                "real_market_data": True,
                "market_data_source": config.data_source,
                "execution_mode": config.execution_mode,
                "advisory_output": True,
                "paper_trading_output": True,
                "execution_allowed": False,
                "private_exchange_api_allowed": False,
                "order_endpoints_allowed": False,
                "supported_public_providers": list(supported_providers()),
            },
        }
        if config.persist:
            self.store.insert_advisory_report(report)
            for proposal in paper_proposals:
                self.store.insert_paper_order_proposal(proposal)
        return report

    async def _build_symbol_provider(
        self,
        symbol: str,
        provider_spec: ProviderSpec,
        config: OperatorWorkbenchConfig,
        research_context: dict[str, Any],
        instrument_id: str | None = None,
    ) -> dict[str, Any]:
        try:
            quote = await self.market_client.fetch_quote(
                provider=provider_spec.provider,
                symbol=symbol,
                market_type=provider_spec.market_type,
            )
            candle_groups = {}
            for interval in config.intervals:
                candle_groups[interval] = await self.market_client.fetch_candles(
                    provider=provider_spec.provider,
                    symbol=symbol,
                    market_type=provider_spec.market_type,
                    interval=interval,
                    limit=config.candle_limit,
                )
            if config.persist:
                self.store.insert_market_quote(_quote_record(quote.to_dict()))
                for candles in candle_groups.values():
                    for candle in candles:
                        self.store.insert_market_candle(_candle_record(candle))
            symbol_research_context = _research_context_for_symbol(symbol, research_context)
            primary_interval = config.advisory.primary_interval if config.advisory.primary_interval in candle_groups else next(iter(candle_groups), "")
            primary_candles = candle_groups.get(primary_interval, [])
            data_quality = _market_data_quality(quote, primary_interval, primary_candles)
            if not data_quality["decision_ready"]:
                return {
                    "status": "insufficient-data",
                    "provider": provider_spec.provider,
                    "market_type": provider_spec.market_type,
                    "symbol": symbol,
                    "instrument_id": instrument_id,
                    "data_source": config.data_source,
                    "execution_mode": config.execution_mode,
                    "quote": quote.to_dict(),
                    "candle_count": sum(len(candles) for candles in candle_groups.values()),
                    "candle_counts_by_interval": {interval: len(candles) for interval, candles in candle_groups.items()},
                    "data_quality": data_quality,
                    "error": "行情数据不足，未生成产品建议。",
                    "error_category": "insufficient-data",
                }
            advice = self.advisory_engine.build_advice(
                quote=quote,
                candles=primary_candles,
                candle_groups=candle_groups,
                config=config.advisory,
                research_context=symbol_research_context,
            )
            return {
                "status": "ok",
                "provider": provider_spec.provider,
                "market_type": provider_spec.market_type,
                "symbol": symbol,
                "instrument_id": instrument_id,
                "data_source": config.data_source,
                "execution_mode": config.execution_mode,
                "quote": quote.to_dict(),
                "candle_count": sum(len(candles) for candles in candle_groups.values()),
                "candle_counts_by_interval": {interval: len(candles) for interval, candles in candle_groups.items()},
                "data_quality": data_quality,
                "advice": advice,
            }
        except Exception as exc:
            status = _failure_status(exc)
            return {
                "status": status,
                "provider": provider_spec.provider,
                "market_type": provider_spec.market_type,
                "symbol": symbol,
                "instrument_id": instrument_id,
                "data_source": config.data_source,
                "execution_mode": config.execution_mode,
                "error": f"{type(exc).__name__}: {exc}",
                "error_category": status,
            }

    def _summary(
        self,
        advice_items: list[dict[str, Any]],
        failed_items: list[dict[str, Any]],
        paper_proposals: list[dict[str, Any]],
        config: OperatorWorkbenchConfig,
    ) -> dict[str, Any]:
        actions: dict[str, int] = {}
        for advice in advice_items:
            action = str(advice.get("action") or "UNKNOWN")
            actions[action] = actions.get(action, 0) + 1
        return {
            "advice_count": len(advice_items),
            "failed_count": len(failed_items),
            "paper_proposal_count": len(paper_proposals),
            "actions": actions,
            "data_source": config.data_source,
            "execution_mode": config.execution_mode,
            "execution_allowed": False,
        }

    def _build_paper_proposals(
        self,
        report_id: str,
        advice_items: list[dict[str, Any]],
        config: OperatorWorkbenchConfig,
    ) -> list[dict[str, Any]]:
        proposals = []
        for advice in advice_items:
            proposal = self.paper_ledger.build_proposal(report_id, advice, config.paper_ledger)
            if proposal:
                proposals.append(proposal)
        return proposals

    def _latest_research_context(self) -> dict[str, Any]:
        rows = self.store.list_research_councils(limit=1)
        if not rows:
            return {"status": "empty", "detail": "No Phase 4.1 council context available."}
        payload = _loads(rows[0]["payload_json"], {})
        brief_payload = self._latest_brief_payload(payload.get("pipeline_run_id"))
        brief_item_map = _brief_item_map(brief_payload)
        return {
            "source": "phase4.1-research-council",
            "council_id": payload.get("council_id"),
            "pipeline_run_id": payload.get("pipeline_run_id"),
            "brief_id": payload.get("brief_id"),
            "created_at": payload.get("created_at"),
            "status": payload.get("status"),
            "summary": payload.get("summary", {}),
            "policy_gate": payload.get("policy_gate", {}),
            "items": _compact_council_items(payload.get("items", []), brief_item_map),
        }

    def _latest_brief_payload(self, pipeline_run_id: str | None) -> dict[str, Any]:
        rows = self.store.list_research_briefs(limit=1, pipeline_run_id=pipeline_run_id) if pipeline_run_id else []
        if not rows:
            rows = self.store.list_research_briefs(limit=1)
        if not rows:
            return {}
        return _loads(rows[0]["payload_json"], {})

    def _report_id(self, config: OperatorWorkbenchConfig, generated_at: str, results: list[dict[str, Any]]) -> str:
        value = f"{config.to_dict()}:{generated_at}:{[(item.get('provider'), item.get('symbol'), item.get('status')) for item in results]}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _market_data_quality(
    quote: Any,
    primary_interval: str,
    primary_candles: list[Any],
) -> dict[str, Any]:
    reasons: list[str] = []
    last_price = _positive_number(getattr(quote, "last_price", None))
    valid_primary_candles = [
        candle
        for candle in primary_candles
        if _positive_number(getattr(candle, "close", None)) is not None
    ]
    if last_price is None:
        reasons.append("missing_last_price")
    if len(valid_primary_candles) < MIN_PRIMARY_CANDLES:
        reasons.append("insufficient_primary_candles")
    return {
        "decision_ready": not reasons,
        "status": "ready" if not reasons else "insufficient-data",
        "reasons": reasons,
        "primary_interval": primary_interval,
        "primary_candle_count": len(valid_primary_candles),
        "minimum_primary_candles": MIN_PRIMARY_CANDLES,
        "last_price": last_price,
    }


def _positive_number(value: Any) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0 else None


def parse_provider_specs(values: list[str] | tuple[str, ...] | None) -> tuple[ProviderSpec, ...]:
    if not values:
        return OperatorWorkbenchConfig.providers
    specs = []
    for raw_value in values:
        parts = [part.strip().lower() for part in raw_value.split(":", 1)]
        provider = parts[0]
        market_type = parts[1] if len(parts) > 1 and parts[1] else "spot"
        if provider not in supported_providers():
            raise ValueError(f"Unsupported provider: {raw_value}")
        specs.append(ProviderSpec(provider=provider, market_type=market_type))
    return tuple(specs)


def parse_intervals(values: list[str] | tuple[str, ...] | None) -> tuple[str, ...]:
    if not values:
        return OperatorWorkbenchConfig.intervals
    intervals = []
    for raw_value in values:
        for interval in raw_value.split(","):
            clean = interval.strip()
            if clean:
                intervals.append(clean)
    return tuple(dict.fromkeys(intervals)) or OperatorWorkbenchConfig.intervals


def _failure_status(exc: Exception) -> str:
    if isinstance(exc, ProxyRouteBlocked):
        return "blocked-by-proxy"
    if isinstance(exc, MarketDataProviderBlocked):
        return exc.category
    return "failed"


def _quote_record(quote: dict[str, Any]) -> dict[str, Any]:
    quote_id = hashlib.sha256(
        f"{quote['provider']}:{quote['market_type']}:{quote['normalized_symbol']}:{quote['captured_at']}".encode("utf-8")
    ).hexdigest()
    return {"quote_id": quote_id, **quote}


def _candle_record(candle: MarketCandle) -> dict[str, Any]:
    payload = candle.to_dict()
    candle_id = hashlib.sha256(
        f"{payload['provider']}:{payload['market_type']}:{payload['normalized_symbol']}:{payload['interval']}:{payload['open_time']}".encode("utf-8")
    ).hexdigest()
    return {"candle_id": candle_id, "captured_at": _now(), **payload}


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _loads(value: str | None, default: Any) -> Any:
    if not value:
        return default
    try:
        loaded = json.loads(value)
        return default if loaded is None else loaded
    except Exception:
        return default


def _compact_council_items(items: list[dict[str, Any]], brief_item_map: dict[str, dict[str, Any]]) -> list[dict[str, Any]]:
    compact = []
    for item in items:
        watch_item = item.get("watch_item", {})
        consensus = item.get("consensus", {})
        brief_item = _matching_brief_item(watch_item, brief_item_map)
        impact_channels = brief_item.get("impact_channels") or watch_item.get("impact_channels") or []
        research_risks = brief_item.get("research_risks") or watch_item.get("research_risks") or []
        compact.append(
            {
                "event_key": watch_item.get("event_key"),
                "headline": watch_item.get("headline"),
                "priority": watch_item.get("priority"),
                "status": consensus.get("status") or watch_item.get("decision"),
                "confidence": consensus.get("confidence"),
                "impact_assets": [
                    channel.get("asset")
                    for channel in impact_channels
                    if channel.get("asset")
                ],
                "research_risks": research_risks,
                "next_action": consensus.get("next_action") or watch_item.get("next_action"),
            }
        )
    return compact


def _brief_item_map(brief_payload: dict[str, Any]) -> dict[str, dict[str, Any]]:
    mapping = {}
    for item in brief_payload.get("watch_items", []):
        for key in (item.get("event_key"), item.get("event_id"), item.get("card_id"), item.get("watch_item_id")):
            if key:
                mapping[str(key)] = item
    return mapping


def _matching_brief_item(watch_item: dict[str, Any], brief_item_map: dict[str, dict[str, Any]]) -> dict[str, Any]:
    for key in (watch_item.get("event_key"), watch_item.get("event_id"), watch_item.get("card_id"), watch_item.get("watch_item_id")):
        if key and str(key) in brief_item_map:
            return brief_item_map[str(key)]
    return {}


def _research_context_for_symbol(symbol: str, research_context: dict[str, Any]) -> dict[str, Any]:
    compact_symbol = symbol.replace("_", "").replace("-", "").upper()
    matched_items = []
    for item in research_context.get("items", []):
        assets = [str(asset).replace("_", "").replace("-", "").upper() for asset in item.get("impact_assets", [])]
        if compact_symbol in assets:
            matched_items.append(item)
    return {
        "source": research_context.get("source"),
        "council_id": research_context.get("council_id"),
        "pipeline_run_id": research_context.get("pipeline_run_id"),
        "status": _symbol_research_status(matched_items),
        "overall_status": research_context.get("status"),
        "summary": research_context.get("summary", {}),
        "policy_gate": research_context.get("policy_gate", {}),
        "matched_items": matched_items,
    }


def _symbol_research_status(matched_items: list[dict[str, Any]]) -> str:
    if not matched_items:
        return "unconfirmed"
    statuses = {str(item.get("status") or "").lower() for item in matched_items}
    if statuses and statuses <= {"passed", "confirmed", "ready", "approved", "watch-approved", "active-watch"}:
        return "confirmed"
    if statuses & {"blocked", "blocked-by-policy", "failed"}:
        return "blocked"
    if "manual-review" in statuses:
        return "manual-review"
    return "needs-followup"


def _request_observations(market_client: Any) -> list[dict[str, Any]]:
    observations = getattr(market_client, "request_observations", None)
    if callable(observations):
        return observations()
    return []
