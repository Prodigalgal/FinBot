from __future__ import annotations

import hashlib
import json
import os
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

from finbot.council.models import CouncilTemplate, SUPPORTED_REASONING_EFFORTS
from finbot.council.builtin_templates import builtin_council_templates
from finbot.config.env_file import read_env_file


AI_SITES_CONFIG_VERSION = 7
AI_SITES_CONFIG_FILENAME = "ai_sites.json"
AI_TASK_ID_COMPRESSION = "ai_compression"
AI_TASK_ID_DEBATE = "ai_debate"
AI_TASK_ID_TRADE_SYNTHESIS = "ai_trade_synthesis"
AI_TASK_ID_EXECUTION_ROBOT = "ai_execution_robot"

DEFAULT_AI_SITE_PRICING: dict[str, dict[str, Any]] = {
    "deepseek": {
        "pricing_model": "deepseek-v4-flash",
        "pricing_currency": "USD",
        "pricing_basis": "cache_miss",
        "pricing_source_url": "https://api-docs.deepseek.com/quick_start/pricing",
        "pricing_checked_at": "2026-07-11",
        "input_cost_per_million_tokens": 0.14,
        "output_cost_per_million_tokens": 0.28,
    },
    "mimo": {
        "pricing_model": "mimo-v2.5-pro",
        "pricing_currency": "USD",
        "pricing_basis": "cache_miss",
        "pricing_source_url": "https://platform.xiaomimimo.com/static/docs/price/pay-as-you-go.md",
        "pricing_checked_at": "2026-07-11",
        "input_cost_per_million_tokens": 0.435,
        "output_cost_per_million_tokens": 0.87,
    },
    "sub2api": {
        "pricing_model": "gpt-5.6-terra",
        "pricing_currency": "USD",
        "pricing_basis": "internal-conservative-estimate",
        "pricing_source_url": None,
        "pricing_checked_at": "2026-07-11",
        "input_cost_per_million_tokens": 5.0,
        "output_cost_per_million_tokens": 20.0,
    },
}

DEFAULT_COMPRESSION_SYSTEM_PROMPT = """你是一个只做研究用途的金融信息压缩器。
只返回合法 JSON 对象。
不要新增事实、预测、交易建议或交易动作。
只能使用输入证据。可用时必须保留 document_id、evidence_id、event_id 和 source_id 引用。
如果证据不足、互相冲突、带推广性质或缺少市场确认，请明确说明。
JSON schema:
{
  "summary": "简短事实摘要",
  "key_points": ["基于证据的要点"],
  "risks": ["解释风险或数据质量风险"],
  "missing_evidence": ["仍需补充或交叉验证的证据"],
  "citations": [{"document_id": "...", "evidence_id": "...", "event_id": "...", "source_id": "..."}]
}
"""

DEFAULT_DEBATE_SYSTEM_PROMPT = """你是 FinBot P8 多 Agent 辩论委员会中的一个独立分析角色。
只返回合法 JSON 对象。
你只能基于输入中的研究证据、P4.1 复核、交易所公共行情和确定性 advisory 指标发言。
不得虚构新闻、价格、成交量、仓位、账户余额或外部事实。
你可以讨论 BUY、SELL、HOLD、WATCH 作为人工参考建议，但不得输出下单、撤单、转账或自动交易指令。
必须显式保留 candidate_id、symbol、provider、market_type 和 evidence_refs。
JSON schema:
{
  "agent_role": "输入中的 agent_role",
  "stance": "bullish|bearish|risk|market|neutral",
  "overall_view": "一句话观点",
  "candidate_assessments": [
    {
      "candidate_id": "...",
      "symbol": "...",
      "provider": "...",
      "market_type": "...",
      "action_bias": "BUY|SELL|HOLD|WATCH",
      "confidence": 0.0,
      "arguments": ["支持理由"],
      "counter_arguments": ["反方理由或不确定性"],
      "risk_flags": ["风险点"],
      "evidence_refs": ["research:...", "market:...", "council:..."],
      "claims": [
        {
          "claim_id": "稳定且唯一的 claim id",
          "text": "可被质询的具体结论",
          "claim_type": "support|risk|counter",
          "confidence": 0.0,
          "evidence_refs": ["支持该 claim 的具体引用"]
        }
      ]
    }
  ],
  "questions_for_other_agents": ["需要其他角色回应的问题"]
}
"""

DEFAULT_TRADE_SYNTHESIS_SYSTEM_PROMPT = """你是 FinBot P8 多 Agent 辩论委员会的 Chair Arbiter 和风险门禁助手。
只返回合法 JSON 对象。
你必须综合输入候选、研究证据、确定性 advisory 指标和各角色辩论消息。
输出只能作为人工参考，execution_allowed 必须为 false。
不得要求或暗示系统自动下单；不得调用交易 API；不得声称已经执行交易。
当 research_context.status 为 needs-followup、行情互相冲突、交易方向与确认方向冲突、或置信度不足时，应输出 HOLD 或 WATCH。
当 research_context.status 为 market-confirmed、market_confirmation.valid 为 true、provider_count 至少为 2 且确认方向与候选方向一致时，视为已经满足 require_research_confirmation；可以基于跨交易所同向行情互证输出 BUY 或 SELL。
此时 fundamental_research_status 为 unconfirmed 或 needs-followup 只表示基本面仍待补充：必须作为独立风险披露，不得声称基本面已经确认，也不得仅因该字段把方向建议降级为 HOLD 或 WATCH。行情冲突、置信度不足、风险收益不成立或价格层级无效仍应降级。
JSON schema:
{
  "council_status": "passed|needs-followup|blocked",
  "debate_summary": ["委员会共识"],
  "major_disagreements": ["仍未解决的主要分歧"],
  "missing_evidence": ["形成可执行判断前必须补充的证据"],
  "decisions": [
    {
      "candidate_id": "...",
      "symbol": "...",
      "provider": "...",
      "market_type": "...",
      "action": "BUY|SELL|HOLD|WATCH",
      "confidence": 0.0,
      "score": 0.0,
      "entry_reference": 0.0,
      "target_price": 0.0,
      "invalidation_price": 0.0,
      "position_sizing": {
        "risk_per_trade_pct": 0.0,
        "max_position_notional_pct": 0.0,
        "sizing_policy": "advisory-only-user-must-confirm"
      },
      "rationale": ["为什么给出该建议"],
      "risk_warnings": ["风险和反证"],
      "evidence_refs": ["research:...", "market:...", "debate:..."],
      "invalidation_conditions": ["建议失效条件"]
    }
  ],
  "policy_gate": {
    "execution_allowed": false,
    "order_api_allowed": false,
    "human_confirmation_required": true
  }
}
"""

DEFAULT_AI_TASKS = {
    AI_TASK_ID_COMPRESSION: {
        "task_id": AI_TASK_ID_COMPRESSION,
        "label": "AI 信息压缩",
        "description": "把候选文档和事件簇压缩为可审计研究摘要。",
        "default_system_prompt": DEFAULT_COMPRESSION_SYSTEM_PROMPT,
        "default_user_prompt_template": "{payload_json}",
    },
    AI_TASK_ID_DEBATE: {
        "task_id": AI_TASK_ID_DEBATE,
        "label": "AI 多 Agent 辩论",
        "description": "让多个可配置 AI 角色围绕候选产品、研究证据和行情指标独立给出观点。",
        "default_system_prompt": DEFAULT_DEBATE_SYSTEM_PROMPT,
        "default_user_prompt_template": "{payload_json}",
    },
    AI_TASK_ID_TRADE_SYNTHESIS: {
        "task_id": AI_TASK_ID_TRADE_SYNTHESIS,
        "label": "AI 交易建议合成",
        "description": "综合多 Agent 辩论、P4.1 研究复核和交易所公共行情，输出 advisory-only 决策。",
        "default_system_prompt": DEFAULT_TRADE_SYNTHESIS_SYSTEM_PROMPT,
        "default_user_prompt_template": "{payload_json}",
    },
    AI_TASK_ID_EXECUTION_ROBOT: {
        "task_id": AI_TASK_ID_EXECUTION_ROBOT,
        "label": "AI 交易执行机器人",
        "description": "在组合风险之后执行初审与反思终审，只筛选允许进入受控模拟执行的原始决策。",
        "default_system_prompt": (
            "你是 FinBot 最终交易执行机器人。你需要先审查，再接受第二阶段反思终审。只返回合法 JSON 对象。"
            "你只能对输入 decision_id 给出 execute=true/false，不得创建新决策、改变方向、"
            "价格、数量、杠杆或绕过风险门禁。证据不足、风险冲突或状态异常时必须拒绝执行。"
        ),
        "default_user_prompt_template": "{payload_json}",
    },
}


DEFAULT_PRODUCT_COUNCIL_TEMPLATE = {
    "template_id": "product_advisory",
    "display_name": "产品研究委员会",
    "enabled": True,
    "quorum_ratio": 0.5,
    "max_roles": 12,
    "roles": [
        {
            "role_id": "bull_researcher",
            "display_name": "看多研究员",
            "stance": "bullish",
            "objective": "寻找支持方向性机会的证据，同时指出成立条件和可证伪条件。",
            "enabled": True,
            "order": 10,
            "site_id": "deepseek",
            "protocol": "chat",
            "model": "deepseek-v4-flash",
            "reasoning_effort": "medium",
            "fallback_site_ids": ["mimo"],
            "system_prompt": "优先寻找支持机会的证据，但必须主动列出反证、证据缺口和失效条件。",
            "user_prompt_template": "{payload_json}",
        },
        {
            "role_id": "bear_researcher",
            "display_name": "看空研究员",
            "stance": "bearish",
            "objective": "寻找反证、下行风险、新闻过期风险和市场冲突。",
            "enabled": True,
            "order": 20,
            "site_id": "mimo",
            "protocol": "chat",
            "model": "mimo-v2.5-pro",
            "reasoning_effort": "high",
            "fallback_site_ids": ["deepseek"],
            "system_prompt": "优先寻找反证、拥挤风险和错误映射，不得为了反对而虚构事实。",
            "user_prompt_template": "{payload_json}",
        },
        {
            "role_id": "market_structure",
            "display_name": "市场结构分析师",
            "stance": "market",
            "objective": "聚焦多周期趋势、动量、波动、成交量、价差和关键价位。",
            "enabled": True,
            "order": 30,
            "site_id": "sub2api",
            "protocol": "responses",
            "model": "gpt-5.6-terra",
            "reasoning_effort": "high",
            "fallback_site_ids": ["mimo"],
            "system_prompt": "只使用输入的确定性行情指标，明确区分短周期和长周期结论。",
            "user_prompt_template": "{payload_json}",
        },
        {
            "role_id": "risk_controller",
            "display_name": "风险控制员",
            "stance": "risk",
            "objective": "从风险约束、失效条件、证据质量和人工确认边界审查候选。",
            "enabled": True,
            "order": 40,
            "site_id": "mimo",
            "protocol": "chat",
            "model": "mimo-v2.5-pro",
            "reasoning_effort": "high",
            "fallback_site_ids": ["deepseek"],
            "system_prompt": "优先识别不可交易、证据不足和风险收益不成立的候选。",
            "user_prompt_template": "{payload_json}",
        },
    ],
    "phases": [
        {
            "phase_id": "independent_analysis",
            "label": "分层初审",
            "message_type": "analysis",
            "scheduling_mode": "parallel",
            "instructions": "按工作流分层分析候选；首层角色独立输出，后续节点审查同轮上游观点并保留 evidence refs。",
        },
        {
            "phase_id": "cross_examination",
            "label": "交叉质询",
            "message_type": "challenge",
            "scheduling_mode": "round_robin",
            "instructions": "阅读前序消息，点名质询具体 claim，说明冲突、证据缺口和需要回应的问题。",
        },
        {
            "phase_id": "position_revision",
            "label": "立场修订",
            "message_type": "rebuttal",
            "scheduling_mode": "parallel",
            "instructions": "回应质询，明确维持或修订立场及置信度，并列出仍未解决的分歧。",
        },
    ],
    "chair": {
        "role_id": "chair_arbiter",
        "display_name": "主席仲裁员",
        "site_id": "sub2api",
        "protocol": "responses",
        "model": "gpt-5.6-terra",
        "reasoning_effort": "high",
        "fallback_site_ids": ["mimo"],
        "system_prompt": DEFAULT_TRADE_SYNTHESIS_SYSTEM_PROMPT,
        "user_prompt_template": "{payload_json}",
    },
    "workflow": {
        "version": 1,
        "nodes": [
            {"node_id": "input_context", "node_type": "input", "role_id": None, "position": {"x": 40, "y": 220}},
            {"node_id": "node_bull_researcher", "node_type": "agent", "role_id": "bull_researcher", "position": {"x": 320, "y": 60}},
            {"node_id": "node_bear_researcher", "node_type": "agent", "role_id": "bear_researcher", "position": {"x": 320, "y": 230}},
            {"node_id": "node_market_structure", "node_type": "agent", "role_id": "market_structure", "position": {"x": 320, "y": 400}},
            {"node_id": "node_risk_controller", "node_type": "agent", "role_id": "risk_controller", "position": {"x": 640, "y": 220}},
            {"node_id": "node_chair_arbiter", "node_type": "chair", "role_id": "chair_arbiter", "position": {"x": 920, "y": 220}},
        ],
        "edges": [
            {"edge_id": "edge_1", "source_node_id": "input_context", "target_node_id": "node_bull_researcher"},
            {"edge_id": "edge_2", "source_node_id": "node_bull_researcher", "target_node_id": "node_risk_controller"},
            {"edge_id": "edge_3", "source_node_id": "input_context", "target_node_id": "node_bear_researcher"},
            {"edge_id": "edge_4", "source_node_id": "node_bear_researcher", "target_node_id": "node_risk_controller"},
            {"edge_id": "edge_5", "source_node_id": "input_context", "target_node_id": "node_market_structure"},
            {"edge_id": "edge_6", "source_node_id": "node_market_structure", "target_node_id": "node_risk_controller"},
            {"edge_id": "edge_7", "source_node_id": "node_risk_controller", "target_node_id": "node_chair_arbiter"},
        ],
    },
}


DEFAULT_COUNCIL_ROLE_PRESETS = (
    {
        "preset_id": "bull_researcher",
        "display_name": "看多研究员",
        "stance": "bullish",
        "objective": "寻找支持方向性机会的证据，同时指出成立条件和可证伪条件。",
        "preferred_site_ids": ["deepseek", "mimo"],
        "system_prompt": "优先寻找支持机会的证据，但必须主动列出反证、证据缺口和失效条件。",
    },
    {
        "preset_id": "bear_researcher",
        "display_name": "看空研究员",
        "stance": "bearish",
        "objective": "寻找反证、下行风险、新闻过期风险和市场冲突。",
        "preferred_site_ids": ["mimo", "deepseek"],
        "system_prompt": "优先寻找反证、拥挤风险和错误映射，不得为了反对而虚构事实。",
    },
    {
        "preset_id": "market_structure",
        "display_name": "市场结构分析师",
        "stance": "market",
        "objective": "聚焦多周期趋势、动量、波动、成交量、价差和关键价位。",
        "preferred_site_ids": ["deepseek", "mimo"],
        "system_prompt": "只使用输入的确定性行情指标，明确区分短周期和长周期结论。",
    },
    {
        "preset_id": "risk_controller",
        "display_name": "风险控制员",
        "stance": "risk",
        "objective": "从风险约束、失效条件、证据质量和人工确认边界审查候选。",
        "preferred_site_ids": ["mimo", "deepseek"],
        "system_prompt": "优先识别不可交易、证据不足和风险收益不成立的候选。",
    },
    {
        "preset_id": "macro_researcher",
        "display_name": "宏观研究员",
        "stance": "macro",
        "objective": "审查宏观事件、政策路径和跨资产传导，不把相关性描述为因果。",
        "preferred_site_ids": ["deepseek", "mimo"],
        "system_prompt": "只引用输入中的宏观事实和官方时间点，明确事件时效与传导假设。",
    },
    {
        "preset_id": "evidence_auditor",
        "display_name": "证据审计员",
        "stance": "evidence",
        "objective": "逐项核对 claim 与 evidence refs，识别循环引用、弱来源和缺失证据。",
        "preferred_site_ids": ["mimo", "deepseek"],
        "system_prompt": "每个关键 claim 必须给出独立 evidence_refs；无引用时明确标记 unsupported。",
    },
    {
        "preset_id": "portfolio_risk",
        "display_name": "组合风险分析师",
        "stance": "portfolio_risk",
        "objective": "识别候选之间的相关性、集中度、共同风险因子和压力情景暴露。",
        "preferred_site_ids": ["deepseek", "mimo"],
        "system_prompt": "不得把多个相似标的当作分散化；区分产品风险、组合风险和数据不足。",
    },
)


@dataclass(frozen=True)
class AISite:
    site_id: str
    display_name: str
    enabled: bool
    base_url: str
    api_key: str | None
    chat_models: tuple[str, ...]
    responses_models: tuple[str, ...]
    default_chat_model: str | None
    default_responses_model: str | None
    timeout_seconds: float = 60.0
    input_cost_per_million_tokens: float | None = None
    output_cost_per_million_tokens: float | None = None
    pricing_model: str | None = None
    pricing_currency: str | None = None
    pricing_basis: str | None = None
    pricing_source_url: str | None = None
    pricing_checked_at: str | None = None

    def model_for(self, protocol: str) -> str | None:
        if protocol == "chat":
            return self.default_chat_model
        if protocol == "responses":
            return self.default_responses_model
        return None


@dataclass(frozen=True)
class AITaskBinding:
    task_id: str
    enabled: bool
    site_id: str | None
    protocol: str
    model: str | None
    reasoning_effort: str
    fallback_site_ids: tuple[str, ...]

    def provider_order(self) -> tuple[str, ...]:
        ordered = []
        if self.site_id:
            ordered.append(self.site_id)
        for site_id in self.fallback_site_ids:
            if site_id and site_id not in ordered:
                ordered.append(site_id)
        return tuple(ordered)


@dataclass(frozen=True)
class AIPromptTemplate:
    task_id: str
    system_prompt: str
    user_prompt_template: str


class AISitesConfigStore:
    def __init__(self, project_root: Path, path: Path | None = None):
        self.project_root = project_root
        self.path = path or project_root / "config" / AI_SITES_CONFIG_FILENAME
        self._lock = threading.Lock()

    def exists(self) -> bool:
        return self.path.exists()

    def payload(self) -> dict[str, Any]:
        if not self.path.exists():
            return self.default_payload()
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            raise ValueError(f"AI 站点配置 JSON 无效：{self.path}: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError(f"AI 站点配置 JSON 无效：{self.path}: 根节点必须是对象")
        source_version = int(payload.get("version") or 1)
        payload["version"] = source_version
        payload.setdefault("updated_at", None)
        payload.setdefault("sites", [])
        payload.setdefault("task_bindings", {})
        payload.setdefault("prompts", {})
        payload.setdefault("council_templates", self.default_payload()["council_templates"])
        payload.setdefault("experiments", [])
        if source_version < 4:
            payload["sites"] = [
                _upgrade_legacy_site_pricing(site)
                for site in _list_payload(payload.get("sites"))
            ]
        if source_version < 7:
            payload["sites"] = [
                _upgrade_v7_site(site)
                for site in _list_payload(payload.get("sites"))
            ]
            payload["version"] = AI_SITES_CONFIG_VERSION
        return payload

    def default_payload(self) -> dict[str, Any]:
        return {
            "version": AI_SITES_CONFIG_VERSION,
            "updated_at": None,
            "sites": [
                {
                    "site_id": "deepseek",
                    "display_name": "DeepSeek",
                    "enabled": True,
                    "base_url": "https://api.deepseek.com",
                    "api_key": None,
                    "chat_models": ["deepseek-v4-flash"],
                    "responses_models": [],
                    "default_chat_model": "deepseek-v4-flash",
                    "default_responses_model": None,
                    "timeout_seconds": 60,
                    **DEFAULT_AI_SITE_PRICING["deepseek"],
                },
                {
                    "site_id": "mimo",
                    "display_name": "MiMo2API 账户池",
                    "enabled": True,
                    "base_url": "https://mimo2api.mnnu.eu.org/v1",
                    "api_key": None,
                    "chat_models": ["mimo-v2.5-pro"],
                    "responses_models": ["mimo-v2.5-pro"],
                    "default_chat_model": "mimo-v2.5-pro",
                    "default_responses_model": "mimo-v2.5-pro",
                    "timeout_seconds": 60,
                    **DEFAULT_AI_SITE_PRICING["mimo"],
                },
                {
                    "site_id": "sub2api",
                    "display_name": "Sub2API GPT 5.6",
                    "enabled": True,
                    "base_url": "http://168.138.40.52:8181/v1",
                    "api_key": None,
                    "chat_models": [],
                    "responses_models": ["gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"],
                    "default_chat_model": None,
                    "default_responses_model": "gpt-5.6-terra",
                    "timeout_seconds": 90,
                    **DEFAULT_AI_SITE_PRICING["sub2api"],
                },
            ],
            "task_bindings": {
                AI_TASK_ID_COMPRESSION: {
                    "enabled": True,
                    "site_id": "mimo",
                    "protocol": "chat",
                    "model": "mimo-v2.5-pro",
                    "reasoning_effort": "high",
                    "fallback_site_ids": ["deepseek"],
                },
                AI_TASK_ID_DEBATE: {
                    "enabled": True,
                    "site_id": "deepseek",
                    "protocol": "chat",
                    "model": "deepseek-v4-flash",
                    "reasoning_effort": "medium",
                    "fallback_site_ids": ["mimo"],
                },
                AI_TASK_ID_TRADE_SYNTHESIS: {
                    "enabled": True,
                    "site_id": "sub2api",
                    "protocol": "responses",
                    "model": "gpt-5.6-terra",
                    "reasoning_effort": "high",
                    "fallback_site_ids": ["mimo"],
                },
                AI_TASK_ID_EXECUTION_ROBOT: {
                    "enabled": True,
                    "site_id": "sub2api",
                    "protocol": "responses",
                    "model": "gpt-5.6-sol",
                    "reasoning_effort": "xhigh",
                    "fallback_site_ids": [],
                }
            },
            "prompts": {
                AI_TASK_ID_COMPRESSION: {
                    "system_prompt": DEFAULT_COMPRESSION_SYSTEM_PROMPT,
                    "user_prompt_template": "{payload_json}",
                },
                AI_TASK_ID_DEBATE: {
                    "system_prompt": DEFAULT_DEBATE_SYSTEM_PROMPT,
                    "user_prompt_template": "{payload_json}",
                },
                AI_TASK_ID_TRADE_SYNTHESIS: {
                    "system_prompt": DEFAULT_TRADE_SYNTHESIS_SYSTEM_PROMPT,
                    "user_prompt_template": "{payload_json}",
                },
                AI_TASK_ID_EXECUTION_ROBOT: {
                    "system_prompt": DEFAULT_AI_TASKS[AI_TASK_ID_EXECUTION_ROBOT]["default_system_prompt"],
                    "user_prompt_template": "{payload_json}",
                }
            },
            "council_templates": [DEFAULT_PRODUCT_COUNCIL_TEMPLATE, *builtin_council_templates()],
            "experiments": [],
        }

    def public_payload(self, keys_file: Path | None = None, env: Mapping[str, str] | None = None) -> dict[str, Any]:
        payload = self.payload()
        sites = [self._public_site(site, keys_file=keys_file, env=env) for site in _list_payload(payload.get("sites"))]
        return {
            "status": "ok",
            "version": AI_SITES_CONFIG_VERSION,
            "config_path": str(self.path),
            "updated_at": payload.get("updated_at"),
            "tasks": list(DEFAULT_AI_TASKS.values()),
            "sites": sites,
            "task_bindings": self._bindings_payload(payload),
            "prompts": self._prompts_payload(payload),
            "council_templates": self._council_templates_payload(payload),
            "role_presets": self._role_presets_payload(sites),
            "experiments": _normalize_experiments(payload.get("experiments")),
        }

    def update(self, payload: dict[str, Any]) -> dict[str, Any]:
        next_payload = self._normalize_update(payload)
        with self._lock:
            self._write_payload(next_payload)
        return next_payload

    def update_models(self, site_id: str, protocol: str, models: list[str]) -> dict[str, Any]:
        if protocol not in {"chat", "responses"}:
            raise ValueError(f"AI 协议不支持：{protocol}")
        clean_models = [model for model in _string_list(models) if model]
        if not clean_models:
            raise ValueError("模型刷新结果为空")
        with self._lock:
            payload = self.payload()
            sites = _list_payload(payload.get("sites"))
            matched = False
            for site in sites:
                if str(site.get("site_id") or "") != site_id:
                    continue
                key = "chat_models" if protocol == "chat" else "responses_models"
                default_key = "default_chat_model" if protocol == "chat" else "default_responses_model"
                site[key] = clean_models
                if not _optional_str(site.get(default_key)) or site.get(default_key) not in clean_models:
                    site[default_key] = clean_models[0]
                matched = True
                break
            if not matched:
                raise ValueError(f"未找到 AI 站点：{site_id}")
            payload["sites"] = sites
            payload["updated_at"] = _now()
            self._write_payload(payload)
            return payload

    def sites(self, keys_file: Path | None = None, env: Mapping[str, str] | None = None) -> dict[str, AISite]:
        payload = self.payload()
        return {
            site.site_id: site
            for site in (self._site_from_payload(item, keys_file=keys_file, env=env) for item in _list_payload(payload.get("sites")))
            if site.site_id and site.enabled
        }

    def task_binding(self, task_id: str) -> AITaskBinding:
        payload = self.payload()
        binding = (payload.get("task_bindings") or {}).get(task_id) or self.default_payload()["task_bindings"].get(task_id) or {}
        return AITaskBinding(
            task_id=task_id,
            enabled=_bool_value(binding.get("enabled"), default=True),
            site_id=_optional_str(binding.get("site_id")),
            protocol=str(binding.get("protocol") or "chat"),
            model=_optional_str(binding.get("model")),
            reasoning_effort=str(binding.get("reasoning_effort") or "provider_default"),
            fallback_site_ids=tuple(_string_list(binding.get("fallback_site_ids"))),
        )

    def prompt(self, task_id: str) -> AIPromptTemplate:
        payload = self.payload()
        defaults = DEFAULT_AI_TASKS[task_id]
        prompt = (payload.get("prompts") or {}).get(task_id) or {}
        return AIPromptTemplate(
            task_id=task_id,
            system_prompt=str(prompt.get("system_prompt") or defaults["default_system_prompt"]),
            user_prompt_template=str(prompt.get("user_prompt_template") or defaults["default_user_prompt_template"]),
        )

    def council_template(self, template_id: str = "product_advisory") -> CouncilTemplate:
        for template in self.council_templates():
            if template.template_id == template_id:
                return template
        raise ValueError(f"未找到 Council 模板：{template_id}")

    def council_templates(self) -> tuple[CouncilTemplate, ...]:
        payload = self.payload()
        templates = _merge_builtin_templates(_list_payload(payload.get("council_templates")))
        return tuple(CouncilTemplate.from_payload(item) for item in templates)

    def experiments(self, task_id: str | None = None) -> tuple[dict[str, Any], ...]:
        experiments = tuple(_normalize_experiments(self.payload().get("experiments")))
        if task_id is None:
            return experiments
        return tuple(item for item in experiments if item["task_id"] == task_id)

    def site_pricing(self, site_id: str, model: str | None = None) -> dict[str, Any]:
        site = _site_by_id(self.payload(), site_id) or {}
        pricing_model = _optional_str(site.get("pricing_model"))
        model_matches = not pricing_model or not model or pricing_model == model
        return {
            "input_cost_per_million_tokens": (
                _optional_non_negative_float(site.get("input_cost_per_million_tokens"))
                if model_matches else None
            ),
            "output_cost_per_million_tokens": (
                _optional_non_negative_float(site.get("output_cost_per_million_tokens"))
                if model_matches else None
            ),
            "pricing_model": pricing_model,
            "pricing_currency": _optional_str(site.get("pricing_currency")),
            "pricing_basis": _optional_str(site.get("pricing_basis")),
            "pricing_source_url": _optional_str(site.get("pricing_source_url")),
            "pricing_checked_at": _optional_str(site.get("pricing_checked_at")),
            "model_matches": model_matches,
        }

    def experiment_variant(self, task_id: str, allocation_key: str) -> dict[str, Any] | None:
        experiment = next((item for item in self.experiments(task_id) if item["enabled"]), None)
        if experiment is None:
            return None
        variants = experiment["variants"]
        total_weight = sum(float(item["weight"]) for item in variants)
        digest = hashlib.sha256(
            f"{experiment['experiment_id']}:{allocation_key}".encode("utf-8")
        ).digest()
        point = int.from_bytes(digest[:8], "big") / float(2**64) * total_weight
        cumulative = 0.0
        selected = variants[-1]
        for variant in variants:
            cumulative += float(variant["weight"])
            if point < cumulative:
                selected = variant
                break
        return {
            "experiment_id": experiment["experiment_id"],
            "experiment_name": experiment["display_name"],
            **selected,
        }

    def _normalize_update(self, payload: dict[str, Any]) -> dict[str, Any]:
        current = self.payload()
        sites = [
            _normalize_site(site, previous=_site_by_id(current, str(site.get("site_id") or "")))
            for site in _list_payload(payload.get("sites"))
        ]
        bindings = {
            task_id: _normalize_binding(task_id, value)
            for task_id, value in (payload.get("task_bindings") or {}).items()
            if task_id in DEFAULT_AI_TASKS
        }
        prompts = {
            task_id: _normalize_prompt(task_id, value)
            for task_id, value in (payload.get("prompts") or {}).items()
            if task_id in DEFAULT_AI_TASKS
        }
        template_source = payload.get("council_templates")
        if not isinstance(template_source, list):
            template_source = current.get("council_templates") or self.default_payload()["council_templates"]
        council_templates = _normalize_council_templates(template_source)
        experiment_source = payload.get("experiments") if "experiments" in payload else current.get("experiments", [])
        return {
            "version": AI_SITES_CONFIG_VERSION,
            "updated_at": _now(),
            "sites": sites,
            "task_bindings": bindings or self.default_payload()["task_bindings"],
            "prompts": prompts or self.default_payload()["prompts"],
            "council_templates": council_templates,
            "experiments": _normalize_experiments(experiment_source),
        }

    def _site_from_payload(self, payload: dict[str, Any], keys_file: Path | None, env: Mapping[str, str] | None) -> AISite:
        site_id = str(payload.get("site_id") or "").strip()
        key = _optional_str(payload.get("api_key")) or _provider_key_from_env(site_id, keys_file=keys_file, env=env)
        chat_models = tuple(_string_list(payload.get("chat_models")))
        responses_models = tuple(_string_list(payload.get("responses_models")))
        return AISite(
            site_id=site_id,
            display_name=str(payload.get("display_name") or site_id),
            enabled=_bool_value(payload.get("enabled"), default=True),
            base_url=str(payload.get("base_url") or "").strip(),
            api_key=key,
            chat_models=chat_models,
            responses_models=responses_models,
            default_chat_model=_optional_str(payload.get("default_chat_model")) or (chat_models[0] if chat_models else None),
            default_responses_model=_optional_str(payload.get("default_responses_model")) or (responses_models[0] if responses_models else None),
            timeout_seconds=_float_value(payload.get("timeout_seconds"), 60.0),
            input_cost_per_million_tokens=_optional_non_negative_float(
                payload.get("input_cost_per_million_tokens")
            ),
            output_cost_per_million_tokens=_optional_non_negative_float(
                payload.get("output_cost_per_million_tokens")
            ),
            pricing_model=_optional_str(payload.get("pricing_model")),
            pricing_currency=_optional_str(payload.get("pricing_currency")),
            pricing_basis=_optional_str(payload.get("pricing_basis")),
            pricing_source_url=_optional_str(payload.get("pricing_source_url")),
            pricing_checked_at=_optional_str(payload.get("pricing_checked_at")),
        )

    def _public_site(self, payload: dict[str, Any], keys_file: Path | None, env: Mapping[str, str] | None) -> dict[str, Any]:
        site = self._site_from_payload(payload, keys_file=keys_file, env=env)
        return {
            "site_id": site.site_id,
            "display_name": site.display_name,
            "enabled": site.enabled,
            "base_url": site.base_url,
            "api_key_configured": bool(site.api_key),
            "chat_models": list(site.chat_models),
            "responses_models": list(site.responses_models),
            "default_chat_model": site.default_chat_model,
            "default_responses_model": site.default_responses_model,
            "timeout_seconds": site.timeout_seconds,
            "input_cost_per_million_tokens": site.input_cost_per_million_tokens,
            "output_cost_per_million_tokens": site.output_cost_per_million_tokens,
            "pricing_model": site.pricing_model,
            "pricing_currency": site.pricing_currency,
            "pricing_basis": site.pricing_basis,
            "pricing_source_url": site.pricing_source_url,
            "pricing_checked_at": site.pricing_checked_at,
        }

    def _prompts_payload(self, payload: dict[str, Any]) -> dict[str, dict[str, str]]:
        prompts = dict(payload.get("prompts") or {})
        result = {}
        for task_id, defaults in DEFAULT_AI_TASKS.items():
            prompt = prompts.get(task_id) or {}
            result[task_id] = {
                "system_prompt": str(prompt.get("system_prompt") or defaults["default_system_prompt"]),
                "user_prompt_template": str(prompt.get("user_prompt_template") or defaults["default_user_prompt_template"]),
            }
        return result

    def _bindings_payload(self, payload: dict[str, Any]) -> dict[str, dict[str, Any]]:
        default_bindings = self.default_payload()["task_bindings"]
        bindings = dict(payload.get("task_bindings") or {})
        result = {}
        for task_id in DEFAULT_AI_TASKS:
            binding = bindings.get(task_id) or default_bindings.get(task_id) or {}
            result[task_id] = _normalize_binding(task_id, binding)
        return result

    def _council_templates_payload(self, payload: dict[str, Any]) -> list[dict[str, Any]]:
        source = payload.get("council_templates")
        if not isinstance(source, list):
            source = self.default_payload()["council_templates"]
        return [CouncilTemplate.from_payload(item).to_dict() for item in _merge_builtin_templates(_list_payload(source))]

    def _role_presets_payload(self, sites: list[dict[str, Any]]) -> list[dict[str, Any]]:
        enabled_sites = [site for site in sites if _bool_value(site.get("enabled"), True)]
        by_id = {str(site.get("site_id") or ""): site for site in enabled_sites}
        result: list[dict[str, Any]] = []
        for index, preset in enumerate(DEFAULT_COUNCIL_ROLE_PRESETS, start=1):
            preferred_ids = _string_list(preset.get("preferred_site_ids"))
            ordered_sites = [by_id[site_id] for site_id in preferred_ids if site_id in by_id]
            ordered_sites.extend(site for site in enabled_sites if site not in ordered_sites)
            primary = ordered_sites[0] if ordered_sites else None
            site_id = str(primary.get("site_id")) if primary else None
            model = _optional_str(primary.get("default_chat_model")) if primary else None
            result.append(
                {
                    "preset_id": preset["preset_id"],
                    "role_id": preset["preset_id"],
                    "display_name": preset["display_name"],
                    "stance": preset["stance"],
                    "objective": preset["objective"],
                    "enabled": True,
                    "order": index * 10,
                    "site_id": site_id,
                    "protocol": "chat",
                    "model": model,
                    "fallback_site_ids": [
                        str(site.get("site_id")) for site in ordered_sites[1:]
                    ],
                    "system_prompt": preset["system_prompt"],
                    "user_prompt_template": "{payload_json}",
                }
            )
        return result

    def _write_payload(self, payload: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.path.with_suffix(self.path.suffix + ".tmp")
        tmp_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        tmp_path.replace(self.path)


def render_prompt_template(template: str, *, payload_json: str, target_type: str, target_id: str) -> str:
    return template.replace("{payload_json}", payload_json).replace("{target_type}", target_type).replace("{target_id}", target_id)


def _normalize_site(site: dict[str, Any], previous: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(site, dict):
        raise ValueError("AI 站点必须是对象")
    site_id = str(site.get("site_id") or "").strip()
    if not site_id:
        raise ValueError("AI 站点缺少 site_id")
    api_key = _optional_str(site.get("api_key"))
    if api_key is None and previous:
        api_key = _optional_str(previous.get("api_key"))
    return {
        "site_id": site_id,
        "display_name": str(site.get("display_name") or site_id).strip(),
        "enabled": _bool_value(site.get("enabled"), default=True),
        "base_url": str(site.get("base_url") or "").strip(),
        "api_key": api_key,
        "chat_models": _string_list(site.get("chat_models")),
        "responses_models": _string_list(site.get("responses_models")),
        "default_chat_model": _optional_str(site.get("default_chat_model")),
        "default_responses_model": _optional_str(site.get("default_responses_model")),
        "timeout_seconds": _float_value(site.get("timeout_seconds"), 60.0),
        "input_cost_per_million_tokens": _optional_non_negative_float(
            site.get("input_cost_per_million_tokens")
        ),
        "output_cost_per_million_tokens": _optional_non_negative_float(
            site.get("output_cost_per_million_tokens")
        ),
        "pricing_model": _optional_str(site.get("pricing_model")),
        "pricing_currency": _optional_str(site.get("pricing_currency")),
        "pricing_basis": _optional_str(site.get("pricing_basis")),
        "pricing_source_url": _optional_str(site.get("pricing_source_url")),
        "pricing_checked_at": _optional_str(site.get("pricing_checked_at")),
    }


def _upgrade_legacy_site_pricing(site: dict[str, Any]) -> dict[str, Any]:
    upgraded = dict(site)
    site_id = str(upgraded.get("site_id") or "").strip()
    defaults = DEFAULT_AI_SITE_PRICING.get(site_id)
    if not defaults:
        return upgraded
    if (
        upgraded.get("input_cost_per_million_tokens") is None
        and upgraded.get("output_cost_per_million_tokens") is None
    ):
        for key, value in defaults.items():
            upgraded.setdefault(key, value)
            if upgraded.get(key) is None:
                upgraded[key] = value
    return upgraded


def _upgrade_v7_site(site: dict[str, Any]) -> dict[str, Any]:
    upgraded = dict(site)
    if str(upgraded.get("site_id") or "").strip() != "sub2api":
        return upgraded
    existing_models = _string_list(upgraded.get("responses_models"))
    upgraded["responses_models"] = list(
        dict.fromkeys(["gpt-5.6-sol", "gpt-5.6-terra", *existing_models])
    )
    if upgraded.get("default_responses_model") in {None, "", "gpt-5.6-luna"}:
        upgraded["default_responses_model"] = "gpt-5.6-terra"
    if upgraded.get("pricing_model") in {None, "", "gpt-5.6-luna"}:
        upgraded["pricing_model"] = "gpt-5.6-terra"
    if upgraded.get("display_name") == "GPT 5.6 Luna（开发网关）":
        upgraded["display_name"] = "Sub2API GPT 5.6"
    return upgraded


def _normalize_binding(task_id: str, binding: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(binding, dict):
        raise ValueError(f"AI 环节绑定必须是对象：{task_id}")
    protocol = str(binding.get("protocol") or "chat").strip()
    if protocol not in {"chat", "responses"}:
        raise ValueError(f"AI 环节协议不支持：{protocol}")
    reasoning_effort = str(binding.get("reasoning_effort") or "provider_default").strip()
    if reasoning_effort not in SUPPORTED_REASONING_EFFORTS:
        raise ValueError(f"AI 环节思考等级不支持：{reasoning_effort}")
    return {
        "enabled": _bool_value(binding.get("enabled"), default=True),
        "site_id": _optional_str(binding.get("site_id")),
        "protocol": protocol,
        "model": _optional_str(binding.get("model")),
        "reasoning_effort": reasoning_effort,
        "fallback_site_ids": _string_list(binding.get("fallback_site_ids")),
    }


def _normalize_prompt(task_id: str, prompt: dict[str, Any]) -> dict[str, str]:
    if not isinstance(prompt, dict):
        raise ValueError(f"AI 提示词配置必须是对象：{task_id}")
    defaults = DEFAULT_AI_TASKS[task_id]
    return {
        "system_prompt": str(prompt.get("system_prompt") or defaults["default_system_prompt"]),
        "user_prompt_template": str(prompt.get("user_prompt_template") or defaults["default_user_prompt_template"]),
    }


def _normalize_council_templates(value: Any) -> list[dict[str, Any]]:
    templates = [CouncilTemplate.from_payload(item) for item in _list_payload(value)]
    if not templates:
        templates = [CouncilTemplate.from_payload(DEFAULT_PRODUCT_COUNCIL_TEMPLATE)]
    ids = [template.template_id for template in templates]
    if len(ids) != len(set(ids)):
        raise ValueError("Council template_id 不能重复")
    return [template.to_dict() for template in templates]


def _merge_builtin_templates(source: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged = list(source)
    known_ids = {str(item.get("template_id") or "") for item in merged}
    for template in builtin_council_templates():
        if str(template.get("template_id") or "") not in known_ids:
            merged.append(template)
    return merged


def _normalize_experiments(value: Any) -> list[dict[str, Any]]:
    if value is None:
        return []
    if not isinstance(value, list):
        raise ValueError("AI experiments 必须是数组")
    experiments: list[dict[str, Any]] = []
    enabled_tasks: set[str] = set()
    experiment_ids: set[str] = set()
    for raw in value:
        if not isinstance(raw, dict):
            raise ValueError("AI experiment 必须是对象")
        experiment_id = _config_identifier(raw.get("experiment_id"), "experiment_id")
        if experiment_id in experiment_ids:
            raise ValueError(f"AI experiment_id 不能重复：{experiment_id}")
        task_id = str(raw.get("task_id") or "").strip()
        if task_id not in DEFAULT_AI_TASKS:
            raise ValueError(f"AI experiment task_id 不支持：{task_id}")
        enabled = _bool_value(raw.get("enabled"), False)
        if enabled and task_id in enabled_tasks:
            raise ValueError(f"同一 AI task 只能启用一个 experiment：{task_id}")
        variants: list[dict[str, Any]] = []
        variant_ids: set[str] = set()
        for variant in _list_payload(raw.get("variants")):
            variant_id = _config_identifier(variant.get("variant_id"), "variant_id")
            if variant_id in variant_ids:
                raise ValueError(f"AI variant_id 不能重复：{experiment_id}/{variant_id}")
            weight = _float_value(variant.get("weight"), 1.0)
            if weight <= 0:
                raise ValueError(f"AI variant weight 必须大于 0：{experiment_id}/{variant_id}")
            protocol = _optional_str(variant.get("protocol"))
            if protocol and protocol not in {"chat", "responses"}:
                raise ValueError(f"AI variant protocol 不支持：{protocol}")
            reasoning_effort = str(variant.get("reasoning_effort") or "provider_default").strip()
            if reasoning_effort not in SUPPORTED_REASONING_EFFORTS:
                raise ValueError(f"AI variant 思考等级不支持：{reasoning_effort}")
            variants.append(
                {
                    "variant_id": variant_id,
                    "display_name": str(variant.get("display_name") or variant_id).strip(),
                    "weight": weight,
                    "site_id": _optional_str(variant.get("site_id")),
                    "protocol": protocol,
                    "model": _optional_str(variant.get("model")),
                    "reasoning_effort": reasoning_effort,
                    "system_prompt_append": _optional_str(variant.get("system_prompt_append")),
                    "user_prompt_template": _optional_str(variant.get("user_prompt_template")),
                }
            )
            variant_ids.add(variant_id)
        if len(variants) < 2:
            raise ValueError(f"AI experiment 至少需要两个 variants：{experiment_id}")
        experiments.append(
            {
                "experiment_id": experiment_id,
                "display_name": str(raw.get("display_name") or experiment_id).strip(),
                "task_id": task_id,
                "enabled": enabled,
                "variants": variants,
            }
        )
        experiment_ids.add(experiment_id)
        if enabled:
            enabled_tasks.add(task_id)
    return experiments


def _config_identifier(value: Any, field_name: str) -> str:
    clean = str(value or "").strip()
    if not clean or len(clean) > 64 or not clean[0].isalpha() or any(
        char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for char in clean
    ):
        raise ValueError(f"AI {field_name} 无效：{clean!r}")
    return clean


def _site_by_id(payload: dict[str, Any], site_id: str) -> dict[str, Any] | None:
    for site in _list_payload(payload.get("sites")):
        if str(site.get("site_id") or "") == site_id:
            return site
    return None


def _provider_key_from_env(site_id: str, keys_file: Path | None, env: Mapping[str, str] | None) -> str | None:
    env_values = dict(env or os.environ)
    file_values = read_env_file(keys_file)
    key = f"{site_id.upper()}_API_KEY"
    value = env_values.get(key) or file_values.get(key)
    return value.strip() if isinstance(value, str) and value.strip() else None


def _list_payload(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _string_list(value: Any) -> list[str]:
    if isinstance(value, str):
        raw_items = value.split(",")
    elif isinstance(value, (list, tuple)):
        raw_items = value
    else:
        raw_items = []
    return [str(item).strip() for item in raw_items if str(item).strip()]


def _optional_str(value: Any) -> str | None:
    if value is None:
        return None
    clean = str(value).strip()
    return clean or None


def _bool_value(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "y", "on", "是", "开启"}
    return default


def _float_value(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _optional_non_negative_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    parsed = _float_value(value, -1.0)
    if parsed < 0:
        raise ValueError("AI token 单价不能为负数")
    return parsed


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
