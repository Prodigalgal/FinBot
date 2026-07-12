from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any


@dataclass(frozen=True)
class PaperLedgerConfig:
    enabled: bool = True
    default_notional: float = 1000.0
    quote_asset: str = "USDT"


class PaperLedgerPlanner:
    def build_proposal(self, report_id: str, advice: dict[str, Any], config: PaperLedgerConfig | None = None) -> dict[str, Any] | None:
        config = config or PaperLedgerConfig()
        if not config.enabled:
            return None
        action = str(advice.get("action") or "HOLD").upper()
        if action not in {"BUY", "SELL"}:
            return None
        levels = advice.get("levels") or {}
        entry_reference = _float(levels.get("entry_reference"))
        if not entry_reference:
            return None
        created_at = datetime.now(timezone.utc).isoformat()
        side = "paper_buy" if action == "BUY" else "paper_sell"
        quantity = round(config.default_notional / entry_reference, 8)
        payload = {
            "proposal_id": self._proposal_id(report_id, advice, created_at),
            "report_id": report_id,
            "advice_id": advice["advice_id"],
            "provider": advice["provider"],
            "market_type": advice["market_type"],
            "symbol": advice["symbol"],
            "action": action,
            "side": side,
            "status": "proposed",
            "execution_mode": "paper_only",
            "created_at": created_at,
            "notional": config.default_notional,
            "quote_asset": config.quote_asset,
            "quantity": quantity,
            "entry_reference": entry_reference,
            "target_price": levels.get("target_price"),
            "invalidation_price": levels.get("invalidation_price"),
            "policy": {
                "paper_only": True,
                "exchange_order_api_allowed": False,
                "human_confirmation_required": True,
            },
        }
        return payload

    def _proposal_id(self, report_id: str, advice: dict[str, Any], created_at: str) -> str:
        value = f"{report_id}:{advice['advice_id']}:{created_at}"
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


def paper_config_to_dict(config: PaperLedgerConfig) -> dict[str, Any]:
    return {
        "enabled": config.enabled,
        "default_notional": config.default_notional,
        "quote_asset": config.quote_asset,
    }


def _float(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None

