from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class PnlAttributionRecord:
    venue: str
    product_id: str
    strategy_id: str
    gross_pnl_usdt: float
    fee_usdt: float = 0.0
    funding_usdt: float = 0.0

    @property
    def net_pnl_usdt(self) -> float:
        return self.gross_pnl_usdt - self.fee_usdt + self.funding_usdt


def attribute_pnl(records: list[PnlAttributionRecord]) -> dict[str, Any]:
    return {
        "status": "available" if records else "unavailable",
        "record_count": len(records),
        "totals": _totals(records),
        "by_venue": _group(records, "venue"),
        "by_product": _group(records, "product_id"),
        "by_strategy": _group(records, "strategy_id"),
        "methodology": {
            "net_pnl": "gross_pnl_minus_fees_plus_funding",
            "allocation": "direct_trade_level_attribution",
            "unallocated_pnl": 0.0,
        },
    }


def _group(records: list[PnlAttributionRecord], field: str) -> list[dict[str, Any]]:
    groups: dict[str, list[PnlAttributionRecord]] = {}
    for record in records:
        groups.setdefault(str(getattr(record, field)), []).append(record)
    return [{"dimension": key, **_totals(rows)} for key, rows in sorted(groups.items())]


def _totals(records: list[PnlAttributionRecord]) -> dict[str, float]:
    return {
        "gross_pnl_usdt": _round(sum(record.gross_pnl_usdt for record in records)),
        "fee_usdt": _round(sum(record.fee_usdt for record in records)),
        "funding_usdt": _round(sum(record.funding_usdt for record in records)),
        "net_pnl_usdt": _round(sum(record.net_pnl_usdt for record in records)),
    }


def _round(value: float) -> float:
    return round(float(value), 8)
