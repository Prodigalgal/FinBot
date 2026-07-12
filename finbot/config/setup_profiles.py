from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from typing import Any

from finbot.config.runtime_config import (
    CONFIG_FIELD_MAP,
    CONFIG_FIELD_SPECS,
    PROXY_POLICY_KEYS,
    RuntimeConfigStore,
)


SETUP_PROFILE_VERSION = 1


@dataclass(frozen=True)
class SetupProfile:
    profile_id: str
    display_name: str
    description: str
    recommended: bool
    highlights: tuple[str, ...]
    overrides: dict[str, Any]

    def values(self) -> dict[str, Any]:
        values = {
            spec.key: deepcopy(spec.default)
            for spec in CONFIG_FIELD_SPECS
            if spec.default is not None
            and not spec.sensitive
            and spec.settings_field is None
            and spec.key not in PROXY_POLICY_KEYS
        }
        values.update(deepcopy(self.overrides))
        return values


SETUP_PROFILES = (
    SetupProfile(
        profile_id="recommended",
        display_name="推荐配置",
        description="完整闭环、每小时运行，兼顾覆盖度与成本。",
        recommended=True,
        highlights=("60 分钟循环", "12 个 Universe 场所合约", "3 轮 Council", "每轮 $0.50 成本门禁"),
        overrides={
            "research.dry_run": False,
            "research.run_ingestion": True,
            "research.run_ai_compression": True,
            "research.ai_compression_dry_run": False,
            "research.run_followups": True,
            "research.followups_dry_run": False,
            "operator.candle_limit": 60,
            "autonomous.enabled": True,
            "autonomous.interval_minutes": 60,
            "autonomous.max_initial_jobs": 20,
            "autonomous.max_events": 10,
            "autonomous.universe_max_instruments": 12,
            "autonomous.ai_debate_max_candidates": 3,
            "autonomous.max_recommendations": 10,
            "autonomous.ai_budget_max_total_tokens_per_loop": 500_000,
            "autonomous.ai_budget_max_cost_usd_per_loop": 0.50,
        },
    ),
    SetupProfile(
        profile_id="economy",
        display_name="省成本",
        description="降低采集、候选和 AI 调用规模，适合持续低成本观察。",
        recommended=False,
        highlights=("180 分钟循环", "8 个 Universe 场所合约", "2 个 AI 候选", "每轮 $0.15 成本门禁"),
        overrides={
            "research.dry_run": False,
            "research.run_ingestion": True,
            "research.run_ai_compression": True,
            "research.ai_compression_dry_run": False,
            "research.run_followups": True,
            "research.followups_dry_run": False,
            "research.max_events": 6,
            "operator.candle_limit": 48,
            "autonomous.enabled": True,
            "autonomous.interval_minutes": 180,
            "autonomous.max_initial_jobs": 10,
            "autonomous.max_events": 6,
            "autonomous.universe_max_instruments": 8,
            "autonomous.ai_debate_max_candidates": 2,
            "autonomous.candle_limit": 48,
            "autonomous.max_recommendations": 5,
            "autonomous.ai_budget_max_total_tokens_per_loop": 250_000,
            "autonomous.ai_budget_max_cost_usd_per_loop": 0.15,
        },
    ),
    SetupProfile(
        profile_id="deep_research",
        display_name="深度研究",
        description="扩大证据、产品和候选覆盖，适合人工重点复核。",
        recommended=False,
        highlights=("60 分钟循环", "24 个 Universe 场所合约", "6 个 AI 候选", "每轮 $2.00 成本门禁"),
        overrides={
            "research.dry_run": False,
            "research.run_ingestion": True,
            "research.run_ai_compression": True,
            "research.ai_compression_dry_run": False,
            "research.run_followups": True,
            "research.followups_dry_run": False,
            "research.max_events": 25,
            "operator.candle_limit": 120,
            "autonomous.enabled": True,
            "autonomous.interval_minutes": 60,
            "autonomous.max_initial_jobs": 40,
            "autonomous.max_events": 25,
            "autonomous.universe_max_instruments": 24,
            "autonomous.ai_debate_max_candidates": 6,
            "autonomous.candle_limit": 120,
            "autonomous.max_recommendations": 12,
            "autonomous.portfolio_min_correlation_samples": 40,
            "autonomous.ai_budget_max_total_tokens_per_loop": 1_000_000,
            "autonomous.ai_budget_max_cost_usd_per_loop": 2.00,
        },
    ),
)


def setup_profile_catalog(config_store: RuntimeConfigStore) -> list[dict[str, Any]]:
    configured = config_store.values()
    catalog = []
    for profile in SETUP_PROFILES:
        values = profile.values()
        catalog.append(
            {
                "profile_id": profile.profile_id,
                "display_name": profile.display_name,
                "description": profile.description,
                "recommended": profile.recommended,
                "highlights": list(profile.highlights),
                "value_count": len(values),
                "missing_value_count": sum(key not in configured for key in values),
                "configured_value_count": sum(key in configured for key in values),
            }
        )
    return catalog


def apply_setup_profile(
    config_store: RuntimeConfigStore,
    *,
    profile_id: str,
    preserve_existing: bool = True,
) -> dict[str, Any]:
    profile = next((item for item in SETUP_PROFILES if item.profile_id == profile_id), None)
    if profile is None:
        raise ValueError(f"未知默认配置档案：{profile_id}")
    configured = config_store.values()
    updates: dict[str, Any] = {}
    preserved_keys: list[str] = []
    skipped_keys: list[str] = []
    for key, value in profile.values().items():
        spec = CONFIG_FIELD_MAP.get(key)
        if spec is None or spec.sensitive or key in PROXY_POLICY_KEYS:
            skipped_keys.append(key)
            continue
        if preserve_existing and key in configured:
            preserved_keys.append(key)
            continue
        updates[key] = value
    config_store.update(updates)
    return {
        "status": "applied",
        "profile_id": profile.profile_id,
        "preserve_existing": preserve_existing,
        "applied_keys": sorted(updates),
        "preserved_keys": sorted(preserved_keys),
        "skipped_keys": sorted(skipped_keys),
    }


def setup_readiness(
    config_store: RuntimeConfigStore,
    *,
    ai_payload: dict[str, Any],
    scheduler_snapshot: dict[str, Any],
) -> dict[str, Any]:
    sites = [site for site in ai_payload.get("sites") or [] if site.get("enabled")]
    keyed_sites = [site for site in sites if site.get("api_key_configured")]
    priced_sites = [
        site
        for site in sites
        if site.get("input_cost_per_million_tokens") is not None
        and site.get("output_cost_per_million_tokens") is not None
        and (
            not site.get("pricing_model")
            or site.get("pricing_model")
            in {site.get("default_chat_model"), site.get("default_responses_model")}
        )
    ]
    templates = [template for template in ai_payload.get("council_templates") or [] if template.get("enabled")]
    council_ready = any(
        len([role for role in template.get("roles") or [] if role.get("enabled")]) >= 2
        and len(template.get("phases") or []) >= 3
        and bool(template.get("chair"))
        for template in templates
    )
    providers = _effective_value(config_store, "exchange.enabled_public_providers") or []
    cost_budget = _effective_value(config_store, "autonomous.ai_budget_max_cost_usd_per_loop")
    checks = [
        _check("ai_key", "AI 密钥", bool(keyed_sites), True, f"已配置 {len(keyed_sites)} 个启用站点" if keyed_sites else "至少配置一个启用 AI 站点的 key"),
        _check("ai_pricing", "AI 费率", bool(sites) and len(priced_sites) == len(sites), True, f"{len(priced_sites)}/{len(sites)} 个启用站点型号费率匹配"),
        _check("council", "多 Agent Council", council_ready, True, "角色、三阶段辩论与 Chair 已就绪" if council_ready else "需要至少两个角色、三个阶段和 Chair"),
        _check("public_market", "公共行情", bool(providers), True, f"已启用：{', '.join(str(item) for item in providers)}" if providers else "至少启用一个公共行情 provider"),
        _check("autonomous", "自动循环", bool(_effective_value(config_store, "autonomous.enabled")), True, "已启用" if _effective_value(config_store, "autonomous.enabled") else "尚未启用自动循环"),
        _check("worker", "常驻 Worker", int(scheduler_snapshot.get("active_worker_count") or 0) > 0, True, f"active worker：{int(scheduler_snapshot.get('active_worker_count') or 0)}"),
        _check("cost_budget", "USD 成本门禁", cost_budget is not None, False, f"每轮 ${float(cost_budget):.2f}" if cost_budget is not None else "未设置 USD 硬预算"),
    ]
    required_failures = [item for item in checks if item["required"] and item["status"] != "passed"]
    warnings = [item for item in checks if not item["required"] and item["status"] != "passed"]
    return {
        "status": "blocked" if required_failures else "needs_attention" if warnings else "ready",
        "passed_count": sum(item["status"] == "passed" for item in checks),
        "check_count": len(checks),
        "required_failure_count": len(required_failures),
        "warning_count": len(warnings),
        "checks": checks,
    }


def setup_default_summary(config_store: RuntimeConfigStore, ai_payload: dict[str, Any]) -> dict[str, Any]:
    recommended = next(profile for profile in SETUP_PROFILES if profile.recommended)
    return {
        "runtime_default_value_count": len(recommended.values()),
        "configured_runtime_value_count": len(config_store.values()),
        "ai_site_count": len(ai_payload.get("sites") or []),
        "ai_task_count": len(ai_payload.get("tasks") or []),
        "council_role_preset_count": len(ai_payload.get("role_presets") or []),
        "setup_profile_count": len(SETUP_PROFILES),
    }


def _effective_value(config_store: RuntimeConfigStore, key: str) -> Any:
    spec = CONFIG_FIELD_MAP[key]
    return config_store.value(key, deepcopy(spec.default))


def _check(check_id: str, label: str, passed: bool, required: bool, detail: str) -> dict[str, Any]:
    return {
        "check_id": check_id,
        "label": label,
        "status": "passed" if passed else "failed",
        "required": required,
        "detail": detail,
    }
