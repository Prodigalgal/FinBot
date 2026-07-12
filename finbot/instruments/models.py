from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass(frozen=True)
class InstrumentMarket:
    provider: str
    market_type: str = "spot"

    @classmethod
    def parse(cls, value: str) -> "InstrumentMarket":
        provider, separator, market_type = value.strip().lower().partition(":")
        if not provider:
            raise ValueError("instrument provider is required")
        return cls(provider=provider, market_type=market_type if separator and market_type else "spot")

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class CatalogInstrument:
    provider: str
    market_type: str
    symbol: str
    base_asset: str
    quote_asset: str | None
    captured_at: str
    active: bool = True
    settle_asset: str | None = None
    contract: bool = False
    linear: bool | None = None
    inverse: bool | None = None
    contract_size: float | None = None
    expiry: str | None = None
    tick_size: float | None = None
    amount_step: float | None = None
    min_amount: float | None = None
    min_notional: float | None = None
    leverage: dict[str, Any] = field(default_factory=dict)
    source_url: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)
    market_snapshot: dict[str, Any] | None = None

    @property
    def normalized_symbol(self) -> str:
        return normalize_alias(self.symbol)

    @property
    def product_type(self) -> str:
        if self.contract:
            return "perpetual" if not self.expiry else "future"
        return "spot"

    @property
    def product_id(self) -> str:
        return stable_id(
            "product",
            "crypto",
            self.product_type,
            self.base_asset,
            self.quote_asset,
            self.settle_asset if self.contract else None,
        )

    @property
    def instrument_id(self) -> str:
        return stable_id("instrument", self.provider, self.market_type, self.symbol)

    def to_record(self) -> dict[str, Any]:
        base_asset = self.base_asset.upper()
        quote_asset = self.quote_asset.upper() if self.quote_asset else None
        settle_asset = self.settle_asset.upper() if self.settle_asset else None
        record = {
            "instrument_id": self.instrument_id,
            "provider": self.provider,
            "market_type": self.market_type,
            "symbol": self.symbol,
            "normalized_symbol": self.normalized_symbol,
            "base_asset": base_asset,
            "quote_asset": quote_asset,
            "settle_asset": settle_asset,
            "active": self.active,
            "contract": self.contract,
            "linear": self.linear,
            "inverse": self.inverse,
            "contract_size": self.contract_size,
            "expiry": self.expiry,
            "tick_size": self.tick_size,
            "amount_step": self.amount_step,
            "min_amount": self.min_amount,
            "min_notional": self.min_notional,
            "leverage": self.leverage,
            "source_url": self.source_url,
            "captured_at": self.captured_at,
            "raw": self.raw,
            "canonical_product": {
                "product_id": self.product_id,
                "asset_class": "crypto",
                "product_type": self.product_type,
                "base_asset": base_asset,
                "quote_asset": quote_asset,
                "display_name": f"{base_asset}/{quote_asset}" if quote_asset else base_asset,
                "status": "active" if self.active else "inactive",
                "metadata": {"settle_asset": settle_asset},
            },
            "aliases": self._aliases(),
        }
        if self.market_snapshot:
            snapshot = dict(self.market_snapshot)
            snapshot["snapshot_id"] = stable_id(
                "instrument-snapshot",
                self.instrument_id,
                self.captured_at,
                snapshot,
            )
            record["market_snapshot"] = snapshot
        return record

    def _aliases(self) -> list[dict[str, Any]]:
        aliases = {
            normalize_alias(self.symbol): ("venue_symbol", 100),
            self.normalized_symbol: ("normalized_symbol", 95),
            normalize_alias(f"{self.base_asset}/{self.quote_asset or ''}"): ("pair", 90),
            normalize_alias(self.base_asset): ("base_asset", 50),
        }
        return [
            {"alias_key": key, "alias_type": alias_type, "priority": priority}
            for key, (alias_type, priority) in aliases.items()
            if key
        ]


def normalize_alias(value: str) -> str:
    return "".join(character for character in str(value).upper() if character.isalnum())


def stable_id(*parts: Any) -> str:
    payload = json.dumps(parts, ensure_ascii=True, sort_keys=True, default=str)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:32]
