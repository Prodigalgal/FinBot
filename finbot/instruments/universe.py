from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any

from finbot.instruments.models import InstrumentMarket, normalize_alias, stable_id
from finbot.storage.sqlite_store import SQLiteStore


@dataclass(frozen=True)
class UniverseConfig:
    mode: str = "hybrid"
    watchlist: tuple[str, ...] = ("BTCUSDT",)
    watchlist_instrument_ids: tuple[str, ...] = ()
    pinned_instrument_ids: tuple[str, ...] = ()
    markets: tuple[InstrumentMarket, ...] = (InstrumentMarket("gate", "spot"),)
    quote_assets: tuple[str, ...] = ("USDT",)
    max_instruments: int = 12
    max_spread_pct: float = 2.0
    min_turnover_24h: float = 0.0
    include_research_assets: bool = True
    include_market_rank: bool = True

    def __post_init__(self) -> None:
        if self.mode not in {"fixed", "hybrid"}:
            raise ValueError(f"Unsupported universe mode: {self.mode}")
        if not 1 <= self.max_instruments <= 200:
            raise ValueError("max_instruments must be between 1 and 200")

    def to_dict(self) -> dict[str, Any]:
        payload = asdict(self)
        payload["watchlist"] = list(self.watchlist)
        payload["watchlist_instrument_ids"] = list(self.watchlist_instrument_ids)
        payload["pinned_instrument_ids"] = list(self.pinned_instrument_ids)
        payload["markets"] = [market.to_dict() for market in self.markets]
        payload["quote_assets"] = list(self.quote_assets)
        return payload


class HybridUniverseBuilder:
    def __init__(self, store: SQLiteStore):
        self.store = store

    def build(
        self,
        config: UniverseConfig,
        loop_run_id: str | None = None,
        research_assets: tuple[str, ...] = (),
    ) -> dict[str, Any]:
        self.store.init_schema()
        created_at = _now()
        providers = tuple(dict.fromkeys(market.provider for market in config.markets))
        market_types = tuple(dict.fromkeys(market.market_type for market in config.markets))
        allowed_markets = {(market.provider, market.market_type) for market in config.markets}
        rows = [
            dict(row)
            for row in self.store.list_venue_instruments(
                active_only=True,
                providers=providers,
                market_types=market_types,
            )
            if (row["provider"], row["market_type"]) in allowed_markets
        ]
        eligible = {
            row["instrument_id"]: row
            for row in rows
            if self._eligible(row, config)
        }
        ranked: dict[str, dict[str, Any]] = {}
        unresolved_pinned_instruments = self._add_instrument_sources(
            ranked,
            eligible,
            config.pinned_instrument_ids,
            "user_pinned",
            30_000.0,
        )
        unresolved_watchlist_instruments = self._add_instrument_sources(
            ranked,
            eligible,
            config.watchlist_instrument_ids,
            "user_watchlist",
            20_000.0,
        )
        unresolved_watchlist = self._add_alias_sources(
            ranked,
            eligible,
            config.watchlist,
            "watchlist",
            10_000.0,
            providers,
            market_types,
            allowed_markets,
        )
        unresolved_research: list[str] = []
        if config.mode == "hybrid" and config.include_research_assets:
            unresolved_research = self._add_alias_sources(
                ranked,
                eligible,
                research_assets,
                "research",
                5_000.0,
                providers,
                market_types,
                allowed_markets,
            )
        if config.mode == "hybrid" and config.include_market_rank:
            for row in eligible.values():
                turnover = _float(row.get("turnover_24h"), 0.0)
                if turnover < config.min_turnover_24h:
                    continue
                market_score = max(0.0, math.log10(max(1.0, turnover)) * 100.0)
                self._add_ranked(ranked, row, "market_rank", market_score, f"24h turnover={turnover:g}")

        selected = sorted(
            ranked.values(),
            key=lambda item: (-item["score"], -_float(item.get("turnover_24h"), 0.0), item["instrument_id"]),
        )[: config.max_instruments]
        for rank_index, item in enumerate(selected, start=1):
            item["rank"] = rank_index
        status = "passed" if selected else "empty"
        universe_run_id = stable_id(
            "universe",
            loop_run_id,
            created_at,
            [item["instrument_id"] for item in selected],
        )
        summary = {
            "catalog_eligible_count": len(eligible),
            "selected_count": len(selected),
            "unresolved_pinned_instruments": unresolved_pinned_instruments,
            "unresolved_watchlist_instruments": unresolved_watchlist_instruments,
            "unresolved_watchlist": unresolved_watchlist,
            "unresolved_research_assets": unresolved_research,
            "source_counts": _source_counts(selected),
        }
        self.store.insert_universe_run(
            {
                "universe_run_id": universe_run_id,
                "loop_run_id": loop_run_id,
                "mode": config.mode,
                "status": status,
                "config": config.to_dict(),
                "summary": summary,
                "created_at": created_at,
            },
            selected,
        )
        return {
            "status": status,
            "universe_run_id": universe_run_id,
            "loop_run_id": loop_run_id,
            "mode": config.mode,
            "created_at": created_at,
            "summary": summary,
            "instruments": selected,
        }

    def _add_instrument_sources(
        self,
        ranked: dict[str, dict[str, Any]],
        eligible: dict[str, dict[str, Any]],
        instrument_ids: tuple[str, ...],
        source: str,
        score: float,
    ) -> list[str]:
        unresolved: list[str] = []
        for instrument_id in dict.fromkeys(value.strip() for value in instrument_ids if value.strip()):
            row = eligible.get(instrument_id)
            if row is None:
                unresolved.append(instrument_id)
                continue
            self._add_ranked(ranked, row, source, score, f"{source} instrument_id={instrument_id}")
        return unresolved

    def _add_alias_sources(
        self,
        ranked: dict[str, dict[str, Any]],
        eligible: dict[str, dict[str, Any]],
        aliases: tuple[str, ...],
        source: str,
        score: float,
        providers: tuple[str, ...],
        market_types: tuple[str, ...],
        allowed_markets: set[tuple[str, str]],
    ) -> list[str]:
        unresolved: list[str] = []
        for alias in dict.fromkeys(value.strip() for value in aliases if value.strip()):
            matches = self.store.find_instruments_by_alias(
                normalize_alias(alias),
                providers=providers,
                market_types=market_types,
            )
            eligible_matches: list[dict[str, Any]] = []
            for match in matches:
                row = eligible.get(match["instrument_id"])
                if row is None or (row["provider"], row["market_type"]) not in allowed_markets:
                    continue
                eligible_matches.append(row)
            selected_matches = _select_alias_representatives(alias, eligible_matches)
            for row in selected_matches:
                self._add_ranked(ranked, row, source, score, f"{source} alias={alias}")
            if not selected_matches:
                unresolved.append(alias)
        return unresolved

    @staticmethod
    def _add_ranked(
        ranked: dict[str, dict[str, Any]],
        row: dict[str, Any],
        source: str,
        score: float,
        reason: str,
    ) -> None:
        item = ranked.setdefault(
            row["instrument_id"],
            {
                "instrument_id": row["instrument_id"],
                "product_id": row["product_id"],
                "provider": row["provider"],
                "market_type": row["market_type"],
                "symbol": row["symbol"],
                "normalized_symbol": row["normalized_symbol"],
                "base_asset": row["base_asset"],
                "quote_asset": row["quote_asset"],
                "settle_asset": row["settle_asset"],
                "contract": bool(row["contract"]),
                "linear": _optional_bool(row["linear"]),
                "inverse": _optional_bool(row["inverse"]),
                "contract_size": row["contract_size"],
                "expiry": row["expiry"],
                "tick_size": row["tick_size"],
                "amount_step": row["amount_step"],
                "min_amount": row["min_amount"],
                "min_notional": row["min_notional"],
                "last_price": row.get("last_price"),
                "bid": row.get("bid"),
                "ask": row.get("ask"),
                "turnover_24h": row.get("turnover_24h"),
                "price_change_pct_24h": row.get("price_change_pct_24h"),
                "sources": [],
                "reasons": [],
                "score": 0.0,
            },
        )
        if source not in item["sources"]:
            item["sources"].append(source)
            item["score"] += score
        item["reasons"].append(reason)

    @staticmethod
    def _eligible(row: dict[str, Any], config: UniverseConfig) -> bool:
        quote_assets = {quote.upper() for quote in config.quote_assets}
        if quote_assets and str(row.get("quote_asset") or "").upper() not in quote_assets:
            return False
        turnover = _float(row.get("turnover_24h"), 0.0)
        if turnover < config.min_turnover_24h:
            return False
        bid = _float(row.get("bid"), 0.0)
        ask = _float(row.get("ask"), 0.0)
        if bid > 0 and ask > 0:
            mid = (bid + ask) / 2
            spread_pct = (ask - bid) / mid * 100 if mid else 0.0
            if spread_pct > config.max_spread_pct:
                return False
        return True


def _source_counts(items: list[dict[str, Any]]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for item in items:
        for source in item.get("sources", []):
            counts[source] = counts.get(source, 0) + 1
    return counts


def _select_alias_representatives(alias: str, rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized_alias = normalize_alias(alias)
    exact_matches = [
        row
        for row in rows
        if normalized_alias
        in {
            normalize_alias(str(row.get("symbol") or "")),
            normalize_alias(str(row.get("normalized_symbol") or "")),
        }
    ]
    if exact_matches:
        return _best_per_market(exact_matches, allow_expiring=True)
    return _best_per_market(rows, allow_expiring=False)


def _best_per_market(rows: list[dict[str, Any]], allow_expiring: bool) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, str, str, str], list[dict[str, Any]]] = {}
    for row in rows:
        if not allow_expiring and row.get("expiry"):
            continue
        key = (
            str(row.get("provider") or ""),
            str(row.get("market_type") or ""),
            str(row.get("base_asset") or ""),
            str(row.get("quote_asset") or ""),
        )
        grouped.setdefault(key, []).append(row)
    selected = []
    for key in sorted(grouped):
        candidates = grouped[key]
        candidates.sort(
            key=lambda row: (
                -int(_float(row.get("last_price"), 0.0) > 0),
                -_float(row.get("turnover_24h"), 0.0),
                len(str(row.get("normalized_symbol") or row.get("symbol") or "")),
                str(row.get("symbol") or ""),
                str(row.get("instrument_id") or ""),
            )
        )
        selected.append(candidates[0])
    return selected


def _optional_bool(value: Any) -> bool | None:
    if value is None:
        return None
    return bool(value)


def _float(value: Any, default: float) -> float:
    try:
        return float(value) if value is not None else default
    except (TypeError, ValueError):
        return default


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
