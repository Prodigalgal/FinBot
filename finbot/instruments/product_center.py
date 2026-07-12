from __future__ import annotations

import json
import math
import sqlite3
from datetime import datetime, timezone
from typing import Any

from finbot.instruments.models import stable_id
from finbot.storage.sqlite_store import SQLiteStore


LOCAL_OWNER_ID = "local"
DEFAULT_WATCHLIST_ID = "watchlist-local-default"
WATCHLIST_RESEARCH_MODES = frozenset({"monitor", "research", "pinned"})


class ProductCatalogService:
    def __init__(self, store: SQLiteStore, owner_id: str = LOCAL_OWNER_ID):
        self.store = store
        self.owner_id = owner_id
        self.store.init_schema()

    def list_products(
        self,
        *,
        search: str | None = None,
        provider: str | None = None,
        market_type: str | None = None,
        product_type: str | None = None,
        active_only: bool = True,
        watchlist_id: str | None = None,
        watched_only: bool = False,
        page: int = 1,
        page_size: int = 25,
    ) -> dict[str, Any]:
        if page < 1:
            raise ValueError("page 必须大于或等于 1")
        if not 1 <= page_size <= 100:
            raise ValueError("page_size 必须在 1 到 100 之间")
        watchlist = self._resolve_watchlist(watchlist_id)
        rows, total = self.store.list_catalog_instruments_page(
            watchlist_id=str(watchlist["watchlist_id"]),
            search=_optional_text(search, 120),
            provider=_optional_lower(provider, 40),
            market_type=_optional_lower(market_type, 40),
            product_type=_optional_lower(product_type, 40),
            active_only=active_only,
            watched_only=watched_only,
            limit=page_size,
            offset=(page - 1) * page_size,
        )
        return {
            "status": "ok",
            "page": page,
            "page_size": page_size,
            "total": total,
            "total_pages": math.ceil(total / page_size) if total else 0,
            "watchlist": _watchlist_payload(watchlist),
            "items": [_instrument_payload(row) for row in rows],
        }

    def get_product(self, product_id: str, watchlist_id: str | None = None) -> dict[str, Any]:
        product = self.store.get_canonical_product(product_id)
        if product is None:
            raise LookupError(f"未找到产品：{product_id}")
        watchlist = self._resolve_watchlist(watchlist_id)
        instruments = self.store.list_product_instruments(product_id, str(watchlist["watchlist_id"]))
        watchlist_item = next(
            (_watchlist_annotation(row) for row in instruments if row["watchlist_item_id"]),
            None,
        )
        return {
            "status": "ok",
            "product": {
                "product_id": product["product_id"],
                "asset_class": product["asset_class"],
                "product_type": product["product_type"],
                "base_asset": product["base_asset"],
                "quote_asset": product["quote_asset"],
                "display_name": product["display_name"],
                "status": product["status"],
                "metadata": _loads(product["metadata_json"], {}),
                "updated_at": product["updated_at"],
            },
            "watchlist": _watchlist_payload(watchlist),
            "watchlist_item": watchlist_item,
            "instruments": [_instrument_payload(row) for row in instruments],
        }

    def list_watchlists(self) -> dict[str, Any]:
        rows = self.store.list_watchlists(self.owner_id)
        return {
            "status": "ok",
            "count": len(rows),
            "watchlists": [_watchlist_payload(row) for row in rows],
        }

    def get_watchlist(self, watchlist_id: str) -> dict[str, Any]:
        watchlist = self._require_watchlist(watchlist_id)
        items = [_watchlist_item_payload(row) for row in self.store.list_watchlist_items(watchlist_id)]
        return {"status": "ok", "watchlist": _watchlist_payload(watchlist), "items": items}

    def create_watchlist(self, *, name: str, description: str = "") -> dict[str, Any]:
        normalized_name = _required_text(name, "name", 80)
        normalized_description = _optional_text(description, 500) or ""
        created_at = _now()
        record = {
            "watchlist_id": stable_id("watchlist", self.owner_id, normalized_name, created_at),
            "owner_id": self.owner_id,
            "name": normalized_name,
            "description": normalized_description,
            "is_default": False,
            "created_at": created_at,
            "updated_at": created_at,
        }
        try:
            self.store.insert_watchlist(record)
        except sqlite3.IntegrityError as exc:
            raise ValueError(f"关注列表名称已存在：{normalized_name}") from exc
        return self.get_watchlist(str(record["watchlist_id"]))

    def update_watchlist(self, watchlist_id: str, changes: dict[str, Any]) -> dict[str, Any]:
        current = self._require_watchlist(watchlist_id)
        name = _required_text(changes["name"], "name", 80) if "name" in changes else str(current["name"])
        description = (
            _optional_text(changes["description"], 500) or ""
            if "description" in changes
            else str(current["description"])
        )
        try:
            self.store.update_watchlist(watchlist_id, self.owner_id, name, description, _now())
        except sqlite3.IntegrityError as exc:
            raise ValueError(f"关注列表名称已存在：{name}") from exc
        return self.get_watchlist(watchlist_id)

    def delete_watchlist(self, watchlist_id: str) -> dict[str, Any]:
        watchlist = self._require_watchlist(watchlist_id)
        if bool(watchlist["is_default"]):
            raise ValueError("默认关注列表不可删除")
        self.store.delete_watchlist(watchlist_id, self.owner_id)
        return {"status": "deleted", "watchlist_id": watchlist_id}

    def upsert_watchlist_item(
        self,
        watchlist_id: str,
        product_id: str,
        *,
        preferred_instrument_id: str | None,
        research_mode: str,
        notes: str = "",
        tags: list[str] | None = None,
        alert_policy: dict[str, Any] | None = None,
        sort_order: int = 0,
    ) -> dict[str, Any]:
        self._require_watchlist(watchlist_id)
        product = self.store.get_canonical_product(product_id)
        if product is None:
            raise LookupError(f"未找到产品：{product_id}")
        mode = str(research_mode).strip().lower()
        if mode not in WATCHLIST_RESEARCH_MODES:
            allowed = ", ".join(sorted(WATCHLIST_RESEARCH_MODES))
            raise ValueError(f"research_mode 仅支持：{allowed}")
        preferred_id = _optional_text(preferred_instrument_id, 80)
        if preferred_id:
            instrument = self.store.get_venue_instrument(preferred_id)
            if instrument is None:
                raise LookupError(f"未找到场所合约：{preferred_id}")
            if str(instrument["product_id"]) != product_id:
                raise ValueError("preferred_instrument_id 不属于当前产品")
        normalized_notes = _optional_text(notes, 2000) or ""
        normalized_tags = _normalize_tags(tags or [])
        normalized_alert_policy = alert_policy or {}
        if not isinstance(normalized_alert_policy, dict):
            raise ValueError("alert_policy 必须是对象")
        if not -100_000 <= sort_order <= 100_000:
            raise ValueError("sort_order 超出允许范围")
        now = _now()
        existing = self.store.get_watchlist_item(watchlist_id, product_id)
        record = {
            "watchlist_item_id": (
                str(existing["watchlist_item_id"])
                if existing is not None
                else stable_id("watchlist-item", watchlist_id, product_id)
            ),
            "watchlist_id": watchlist_id,
            "product_id": product_id,
            "preferred_instrument_id": preferred_id,
            "research_mode": mode,
            "notes": normalized_notes,
            "tags": normalized_tags,
            "alert_policy": normalized_alert_policy,
            "sort_order": sort_order,
            "created_at": str(existing["created_at"]) if existing is not None else now,
            "updated_at": now,
        }
        self.store.upsert_watchlist_item(record)
        saved = self.store.get_watchlist_item(watchlist_id, product_id)
        return {
            "status": "saved",
            "watchlist_id": watchlist_id,
            "item": _watchlist_item_payload(saved),
        }

    def delete_watchlist_item(self, watchlist_id: str, product_id: str) -> dict[str, Any]:
        self._require_watchlist(watchlist_id)
        if self.store.get_canonical_product(product_id) is None:
            raise LookupError(f"未找到产品：{product_id}")
        deleted = self.store.delete_watchlist_item(watchlist_id, product_id)
        return {
            "status": "deleted" if deleted else "not_found",
            "watchlist_id": watchlist_id,
            "product_id": product_id,
        }

    def universe_instrument_ids(self) -> dict[str, tuple[str, ...]]:
        rows = self.store.list_watchlist_universe_items(self.owner_id)
        pinned = tuple(
            dict.fromkeys(
                str(row["instrument_id"])
                for row in rows
                if row["research_mode"] == "pinned" and row["instrument_id"]
            )
        )
        pinned_set = set(pinned)
        research = tuple(
            dict.fromkeys(
                str(row["instrument_id"])
                for row in rows
                if row["research_mode"] == "research"
                and row["instrument_id"]
                and row["instrument_id"] not in pinned_set
            )
        )
        return {"research": research, "pinned": pinned}

    def _resolve_watchlist(self, watchlist_id: str | None) -> Any:
        if watchlist_id:
            return self._require_watchlist(watchlist_id)
        default = self.store.get_default_watchlist(self.owner_id)
        if default is None:
            raise RuntimeError("默认关注列表初始化失败")
        return default

    def _require_watchlist(self, watchlist_id: str) -> Any:
        watchlist = self.store.get_watchlist(watchlist_id, self.owner_id)
        if watchlist is None:
            raise LookupError(f"未找到关注列表：{watchlist_id}")
        return watchlist


def _instrument_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["active"] = bool(payload["active"])
    payload["contract"] = bool(payload["contract"])
    payload["linear"] = _optional_bool(payload.get("linear"))
    payload["inverse"] = _optional_bool(payload.get("inverse"))
    payload["leverage"] = _loads(payload.pop("leverage_json", "{}"), {})
    payload["product_metadata"] = _loads(payload.pop("product_metadata_json", "{}"), {})
    payload["market_snapshot"] = {
        "last_price": payload.pop("last_price", None),
        "bid": payload.pop("bid", None),
        "ask": payload.pop("ask", None),
        "volume_24h": payload.pop("volume_24h", None),
        "turnover_24h": payload.pop("turnover_24h", None),
        "price_change_pct_24h": payload.pop("price_change_pct_24h", None),
        "captured_at": payload.pop("snapshot_captured_at", None),
    }
    payload["watchlist_item"] = _watchlist_annotation(row)
    for key in (
        "payload_json",
        "watchlist_item_id",
        "watchlist_id",
        "preferred_instrument_id",
        "research_mode",
        "watchlist_notes",
        "watchlist_tags_json",
        "watchlist_alert_policy_json",
        "watchlist_sort_order",
        "watchlist_updated_at",
    ):
        payload.pop(key, None)
    return payload


def _watchlist_annotation(row: Any) -> dict[str, Any] | None:
    if not row["watchlist_item_id"]:
        return None
    return {
        "watchlist_item_id": row["watchlist_item_id"],
        "watchlist_id": row["watchlist_id"],
        "preferred_instrument_id": row["preferred_instrument_id"],
        "research_mode": row["research_mode"],
        "notes": row["watchlist_notes"],
        "tags": _loads(row["watchlist_tags_json"], []),
        "alert_policy": _loads(row["watchlist_alert_policy_json"], {}),
        "sort_order": int(row["watchlist_sort_order"] or 0),
        "updated_at": row["watchlist_updated_at"],
        "is_preferred": row["preferred_instrument_id"] == row["instrument_id"],
    }


def _watchlist_payload(row: Any) -> dict[str, Any]:
    payload = {
        "watchlist_id": row["watchlist_id"],
        "name": row["name"],
        "description": row["description"],
        "is_default": bool(row["is_default"]),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }
    keys = set(row.keys())
    for key in ("item_count", "monitor_count", "research_count", "pinned_count"):
        if key in keys:
            payload[key] = int(row[key] or 0)
    return payload


def _watchlist_item_payload(row: Any) -> dict[str, Any]:
    payload = dict(row)
    payload["tags"] = _loads(payload.pop("tags_json", "[]"), [])
    payload["alert_policy"] = _loads(payload.pop("alert_policy_json", "{}"), {})
    if "preferred_active" in payload:
        payload["preferred_active"] = _optional_bool(payload["preferred_active"])
    return payload


def _normalize_tags(tags: list[str]) -> list[str]:
    if len(tags) > 20:
        raise ValueError("tags 最多包含 20 项")
    normalized: list[str] = []
    for tag in tags:
        value = _required_text(tag, "tag", 40)
        if value not in normalized:
            normalized.append(value)
    return normalized


def _required_text(value: Any, field_name: str, max_length: int) -> str:
    normalized = str(value or "").strip()
    if not normalized:
        raise ValueError(f"{field_name} 不能为空")
    if len(normalized) > max_length:
        raise ValueError(f"{field_name} 长度不能超过 {max_length}")
    return normalized


def _optional_text(value: Any, max_length: int) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    if len(normalized) > max_length:
        raise ValueError(f"文本长度不能超过 {max_length}")
    return normalized or None


def _optional_lower(value: Any, max_length: int) -> str | None:
    normalized = _optional_text(value, max_length)
    return normalized.lower() if normalized else None


def _loads(raw: Any, fallback: Any) -> Any:
    try:
        return json.loads(raw) if isinstance(raw, str) and raw else fallback
    except (TypeError, ValueError):
        return fallback


def _optional_bool(value: Any) -> bool | None:
    if value is None:
        return None
    return bool(value)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()

