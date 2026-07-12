from __future__ import annotations

from copy import deepcopy
from typing import Any


def builtin_council_templates() -> list[dict[str, Any]]:
    return deepcopy(
        [
            _quick_market_scan(),
            _standard_product_research(),
            _deep_investment_committee(),
            _event_impact_analysis(),
            _position_review(),
        ]
    )


def _quick_market_scan() -> dict[str, Any]:
    roles = [
        _role("technical_analyst", "技术分析员", "market", "检查多周期价格结构、波动和流动性。", 10, "low"),
        _role("news_analyst", "信息分析员", "neutral", "压缩最新可信信息并区分事实与推断。", 20, "low", site_id="mimo"),
        _role("risk_guard", "快速风险检查", "risk", "识别数据缺口、冲突和不适合形成方向建议的条件。", 30, "low"),
    ]
    nodes = [
        _node("input_context", "input", 40, 210),
        _node("node_market_snapshot", "deterministic", 220, 210, operation="market_snapshot"),
        _node("node_technical_analyst", "agent", 430, 110, role_id="technical_analyst"),
        _node("node_news_analyst", "agent", 430, 310, role_id="news_analyst"),
        _node("node_risk_guard", "agent", 660, 210, role_id="risk_guard"),
        _node("node_quick_chair", "chair", 880, 210, role_id="quick_chair"),
    ]
    edges = [
        _edge("edge_snapshot", "input_context", "node_market_snapshot"),
        _edge("edge_technical", "node_market_snapshot", "node_technical_analyst"),
        _edge("edge_news", "node_market_snapshot", "node_news_analyst"),
        _edge("edge_technical_risk", "node_technical_analyst", "node_risk_guard"),
        _edge("edge_news_risk", "node_news_analyst", "node_risk_guard"),
        _edge("edge_quick_chair", "node_risk_guard", "node_quick_chair"),
    ]
    return _template(
        "quick_market_scan",
        "快速市场扫描",
        "用最少调用完成行情、信息和风险三项快速检查。",
        roles,
        [_phase("rapid_scan", "快速扫描", "parallel", "只提取当前问题最关键的事实、风险和失效条件。")],
        _chair("quick_chair", "快速合成员", "low"),
        nodes,
        edges,
        cost_tier="quick",
        default_rounds=1,
        max_rounds=2,
        recommended_for=("定时监控", "即时概览", "低成本筛查"),
    )


def _standard_product_research() -> dict[str, Any]:
    roles = [
        _role("fundamental_researcher", "基本面研究员", "neutral", "检查项目、宏观、资金和事件证据。", 10, "medium"),
        _role("technical_researcher", "市场结构研究员", "market", "检查价格、成交和跨市场确认。", 20, "medium", site_id="mimo"),
        _role("evidence_auditor", "证据审计员", "risk", "逐项检查来源、时效、冲突和引用覆盖。", 30, "medium"),
        _role("bull_advocate", "看多论证员", "bullish", "提出成立条件明确、可被证伪的多方论证。", 40, "medium", site_id="mimo"),
        _role("bear_advocate", "看空论证员", "bearish", "寻找反证、拥挤风险和下行情景。", 50, "medium"),
        _role("standard_risk_controller", "风险控制员", "risk", "综合分歧并执行建议降级门禁。", 60, "high", site_id="mimo"),
    ]
    nodes = [
        _node("input_context", "input", 40, 260),
        _node("node_research_router", "router", 210, 260, operation="research_router"),
        _node("node_fundamental", "agent", 410, 80, role_id="fundamental_researcher"),
        _node("node_technical", "agent", 410, 230, role_id="technical_researcher"),
        _node("node_evidence", "agent", 410, 380, role_id="evidence_auditor"),
        _node("node_bull", "agent", 630, 150, role_id="bull_advocate"),
        _node("node_bear", "agent", 630, 330, role_id="bear_advocate"),
        _node("node_standard_risk", "agent", 840, 240, role_id="standard_risk_controller"),
        _node("node_standard_chair", "chair", 1050, 240, role_id="standard_chair"),
    ]
    edges = [_edge("edge_router", "input_context", "node_research_router")]
    for suffix in ("fundamental", "technical", "evidence"):
        edges.append(_edge(f"edge_router_{suffix}", "node_research_router", f"node_{suffix}"))
    for source in ("node_fundamental", "node_technical", "node_evidence"):
        edges.append(_edge(f"edge_{source[5:]}_bull", source, "node_bull"))
        edges.append(_edge(f"edge_{source[5:]}_bear", source, "node_bear"))
    edges.extend(
        [
            _edge("edge_bull_risk", "node_bull", "node_standard_risk"),
            _edge("edge_bear_risk", "node_bear", "node_standard_risk"),
            _edge("edge_standard_chair", "node_standard_risk", "node_standard_chair"),
        ]
    )
    return _template(
        "standard_product_research",
        "标准产品研究",
        "覆盖基本面、市场结构、证据审计、Bull/Bear 和风险门禁的默认研究流程。",
        roles,
        [
            _phase("independent_analysis", "独立分析", "parallel", "各角色先独立形成可引用观点。"),
            _phase("cross_examination", "交叉质询", "round_robin", "回应上轮具体观点并提出反证。"),
            _phase("position_revision", "立场修订", "moderated", "在风险控制员主持下修订观点。", moderator_role_id="standard_risk_controller"),
        ],
        _chair("standard_chair", "标准研究主席", "high"),
        nodes,
        edges,
        cost_tier="standard",
        default_rounds=3,
        max_rounds=6,
        recommended_for=("产品研究", "Watchlist 深入分析", "常规投资建议"),
    )


def _deep_investment_committee() -> dict[str, Any]:
    roles = [
        _role("deep_evidence_auditor", "深度证据审计员", "risk", "识别关键证据缺口并验证新增证据。", 10, "high"),
        _role("scenario_analyst", "情景分析员", "neutral", "建立基准、上行、下行和尾部情景。", 20, "high", site_id="mimo"),
        _role("deep_bull_advocate", "深度多方委员", "bullish", "提出强论证并明确证伪条件。", 30, "high"),
        _role("deep_bear_advocate", "深度空方委员", "bearish", "提出最强反证与拥挤风险。", 40, "high", site_id="mimo"),
        _role("portfolio_risk_member", "组合风险委员", "risk", "检查相关性、敞口、流动性和极端风险。", 50, "high"),
    ]
    nodes = [
        _node("input_context", "input", 40, 280),
        _node("node_evidence_quality", "deterministic", 190, 280, operation="evidence_quality"),
        _node("node_research_gap", "gate", 350, 280, operation="research_gap_gate"),
        _node("node_evidence_subflow", "subflow", 520, 380, operation="evidence_followup"),
        _node("node_evidence_recheck", "deterministic", 690, 380, operation="evidence_recheck"),
        _node("node_deep_evidence", "agent", 690, 140, role_id="deep_evidence_auditor"),
        _node("node_scenario", "agent", 860, 40, role_id="scenario_analyst"),
        _node("node_deep_bull", "agent", 860, 180, role_id="deep_bull_advocate"),
        _node("node_deep_bear", "agent", 860, 320, role_id="deep_bear_advocate"),
        _node("node_portfolio_risk", "agent", 1040, 180, role_id="portfolio_risk_member"),
        _node("node_investment_review", "human_review", 1210, 180, operation="investment_committee_review"),
        _node("node_deep_chair", "chair", 1390, 180, role_id="deep_chair"),
    ]
    ready_group = {"activation_group": "evidence_ready", "activation_mode": "any"}
    edges = [
        _edge("edge_quality", "input_context", "node_evidence_quality"),
        _edge("edge_gap", "node_evidence_quality", "node_research_gap"),
        _edge("edge_followup", "node_research_gap", "node_evidence_subflow", condition=_condition("current.needs_more", "truthy")),
        _edge("edge_recheck", "node_evidence_subflow", "node_evidence_recheck"),
        _edge("edge_gap_ready", "node_research_gap", "node_deep_evidence", condition=_condition("current.needs_more", "falsy"), **ready_group),
        _edge("edge_recheck_ready", "node_evidence_recheck", "node_deep_evidence", condition=_condition("current.needs_more", "falsy"), **ready_group),
        _edge("edge_evidence_scenario", "node_deep_evidence", "node_scenario"),
        _edge("edge_evidence_bull", "node_deep_evidence", "node_deep_bull"),
        _edge("edge_evidence_bear", "node_deep_evidence", "node_deep_bear"),
        _edge("edge_scenario_risk", "node_scenario", "node_portfolio_risk"),
        _edge("edge_bull_portfolio", "node_deep_bull", "node_portfolio_risk"),
        _edge("edge_bear_portfolio", "node_deep_bear", "node_portfolio_risk"),
        _edge("edge_human_review", "node_portfolio_risk", "node_investment_review"),
        _edge("edge_deep_chair", "node_investment_review", "node_deep_chair"),
        _edge(
            "edge_research_loop",
            "node_evidence_recheck",
            "node_research_gap",
            loop=True,
            max_traversals=2,
            condition=_condition("current.needs_more", "truthy"),
        ),
    ]
    return _template(
        "deep_investment_committee",
        "深度投委会",
        "允许有限补证据循环、情景分析、组合风险和人工投委会门禁。",
        roles,
        [
            _phase("evidence_review", "证据复核", "parallel", "先验证证据和情景。"),
            _phase("committee_debate", "委员辩论", "round_robin", "围绕最强论点和反证多轮质询。"),
            _phase("committee_revision", "最终修订", "round_robin", "在人工门禁前提交结构化最终意见。"),
        ],
        _chair("deep_chair", "投委会主席", "high", site_id="mimo"),
        nodes,
        edges,
        cost_tier="deep",
        default_rounds=5,
        max_rounds=10,
        failure_policy="replan",
        recommended_for=("高影响决策", "证据不足的复杂问题", "组合风险审查"),
        max_steps=160,
        max_loop_iterations=2,
    )


def _event_impact_analysis() -> dict[str, Any]:
    roles = [
        _role("credibility_analyst", "事件可信度分析员", "neutral", "核查事件来源、时间与独立互证。", 10, "medium"),
        _role("impact_chain_analyst", "影响链分析员", "neutral", "拆解一阶、二阶影响和时间窗口。", 20, "medium", site_id="mimo"),
        _role("event_market_analyst", "市场确认分析员", "market", "检查价格、成交和跨市场是否确认事件。", 30, "medium"),
        _role("counterfactual_analyst", "反事实分析员", "bearish", "构造事件无效、已定价或方向相反的解释。", 40, "high", site_id="mimo"),
        _role("event_risk_controller", "事件风险控制员", "risk", "输出失效条件和建议降级原因。", 50, "high"),
    ]
    nodes = [
        _node("input_context", "input", 40, 230),
        _node("node_event_router", "router", 200, 230, operation="event_router"),
        _node("node_credibility", "agent", 390, 80, role_id="credibility_analyst"),
        _node("node_impact_chain", "agent", 390, 210, role_id="impact_chain_analyst"),
        _node("node_event_market", "agent", 390, 340, role_id="event_market_analyst"),
        _node("node_counterfactual", "agent", 610, 150, role_id="counterfactual_analyst"),
        _node("node_event_risk", "agent", 810, 230, role_id="event_risk_controller"),
        _node("node_event_chair", "chair", 1010, 230, role_id="event_chair"),
    ]
    edges = [_edge("edge_event_router", "input_context", "node_event_router")]
    for suffix in ("credibility", "impact_chain", "event_market"):
        edges.append(_edge(f"edge_event_{suffix}", "node_event_router", f"node_{suffix}"))
        edges.append(_edge(f"edge_{suffix}_counter", f"node_{suffix}", "node_counterfactual"))
    edges.extend(
        [
            _edge("edge_counter_risk", "node_counterfactual", "node_event_risk"),
            _edge("edge_event_chair", "node_event_risk", "node_event_chair"),
        ]
    )
    return _template(
        "event_impact_analysis",
        "事件冲击分析",
        "从事件可信度、影响链、市场确认和反事实四个方向评估冲击。",
        roles,
        [
            _phase("event_validation", "事件验证", "parallel", "先确认事件事实和时间。"),
            _phase("impact_challenge", "影响质询", "round_robin", "质询影响路径与已定价程度。"),
            _phase("impact_revision", "结论修订", "round_robin", "明确时间窗口和失效条件。"),
        ],
        _chair("event_chair", "事件分析主席", "high"),
        nodes,
        edges,
        cost_tier="standard",
        default_rounds=3,
        max_rounds=6,
        recommended_for=("突发新闻", "宏观事件", "项目公告"),
    )


def _position_review() -> dict[str, Any]:
    roles = [
        _role("thesis_reviewer", "原逻辑复核员", "neutral", "对照原建议、证据和失效条件。", 10, "medium"),
        _role("current_market_reviewer", "当前市场复核员", "market", "检查当前行情与原判断的偏差。", 20, "medium", site_id="mimo"),
        _role("risk_change_reviewer", "风险变化复核员", "risk", "识别风险、波动和相关性的变化。", 30, "high"),
        _role("exit_advocate", "退出论证员", "bearish", "优先寻找减仓、退出或观察的充分理由。", 40, "high", site_id="mimo"),
    ]
    nodes = [
        _node("input_context", "input", 40, 220),
        _node("node_position_snapshot", "deterministic", 210, 220, operation="position_snapshot"),
        _node("node_thesis", "agent", 410, 60, role_id="thesis_reviewer"),
        _node("node_current_market", "agent", 410, 170, role_id="current_market_reviewer"),
        _node("node_risk_change", "agent", 410, 280, role_id="risk_change_reviewer"),
        _node("node_exit", "agent", 410, 390, role_id="exit_advocate"),
        _node("node_position_merge", "aggregator", 670, 220, operation="position_review_merge"),
        _node("node_position_chair", "chair", 900, 220, role_id="position_chair"),
    ]
    edges = [_edge("edge_position_snapshot", "input_context", "node_position_snapshot")]
    for suffix in ("thesis", "current_market", "risk_change", "exit"):
        edges.append(_edge(f"edge_position_{suffix}", "node_position_snapshot", f"node_{suffix}"))
        edges.append(_edge(f"edge_{suffix}_merge", f"node_{suffix}", "node_position_merge"))
    edges.append(_edge("edge_position_chair", "node_position_merge", "node_position_chair"))
    return _template(
        "position_review",
        "持仓与建议复核",
        "回放原建议并对照当前市场、风险变化和退出论证。",
        roles,
        [
            _phase("position_replay", "建议回放", "parallel", "先独立检查原逻辑和当前状态。"),
            _phase("position_challenge", "继续或退出质询", "round_robin", "围绕失效条件和退出成本质询。"),
        ],
        _chair("position_chair", "复核主席", "high"),
        nodes,
        edges,
        cost_tier="standard",
        default_rounds=2,
        max_rounds=5,
        recommended_for=("既有建议复盘", "模拟持仓复核", "风险变化检查"),
    )


def _template(
    template_id: str,
    display_name: str,
    description: str,
    roles: list[dict[str, Any]],
    phases: list[dict[str, Any]],
    chair: dict[str, Any],
    nodes: list[dict[str, Any]],
    edges: list[dict[str, Any]],
    *,
    cost_tier: str,
    default_rounds: int,
    max_rounds: int,
    recommended_for: tuple[str, ...],
    failure_policy: str = "stop",
    max_steps: int = 100,
    max_loop_iterations: int = 3,
) -> dict[str, Any]:
    return {
        "template_id": template_id,
        "display_name": display_name,
        "description": description,
        "enabled": True,
        "builtin": True,
        "template_kind": template_id,
        "recommended_for": list(recommended_for),
        "cost_tier": cost_tier,
        "failure_policy": failure_policy,
        "quorum_ratio": 0.6,
        "max_roles": 12,
        "round_policy": {
            "default_rounds": default_rounds,
            "min_rounds": 1,
            "max_rounds": max_rounds,
        },
        "roles": roles,
        "phases": phases,
        "chair": chair,
        "workflow": {
            "version": 2,
            "max_steps": max_steps,
            "max_loop_iterations": max_loop_iterations,
            "nodes": nodes,
            "edges": edges,
        },
    }


def _role(
    role_id: str,
    display_name: str,
    stance: str,
    objective: str,
    order: int,
    reasoning_effort: str,
    *,
    site_id: str = "deepseek",
) -> dict[str, Any]:
    model = "mimo-v2.5-pro" if site_id == "mimo" else "deepseek-v4-flash"
    fallback = "deepseek" if site_id == "mimo" else "mimo"
    return {
        "role_id": role_id,
        "display_name": display_name,
        "stance": stance,
        "objective": objective,
        "enabled": True,
        "order": order,
        "site_id": site_id,
        "protocol": "chat",
        "model": model,
        "reasoning_effort": reasoning_effort,
        "fallback_site_ids": [fallback],
        "system_prompt": f"你是{display_name}。{objective}只输出可审计的结构化结论，不展示隐藏推理过程。",
    }


def _chair(role_id: str, display_name: str, reasoning_effort: str, *, site_id: str = "deepseek") -> dict[str, Any]:
    model = "mimo-v2.5-pro" if site_id == "mimo" else "deepseek-v4-flash"
    return {
        "role_id": role_id,
        "display_name": display_name,
        "site_id": site_id,
        "protocol": "chat",
        "model": model,
        "reasoning_effort": reasoning_effort,
        "fallback_site_ids": ["deepseek" if site_id == "mimo" else "mimo"],
        "system_prompt": "综合可追溯证据、主要分歧和风险门禁，输出面向人工复核的简体中文结论。",
    }


def _phase(
    phase_id: str,
    label: str,
    scheduling_mode: str,
    instructions: str,
    *,
    moderator_role_id: str | None = None,
) -> dict[str, Any]:
    payload = {
        "phase_id": phase_id,
        "label": label,
        "message_type": phase_id,
        "scheduling_mode": scheduling_mode,
        "instructions": instructions,
    }
    if moderator_role_id:
        payload["moderator_role_id"] = moderator_role_id
    return payload


def _node(
    node_id: str,
    node_type: str,
    x: float,
    y: float,
    *,
    role_id: str | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "node_id": node_id,
        "node_type": node_type,
        "role_id": role_id,
        "position": {"x": x, "y": y},
    }
    if operation:
        payload["operation"] = operation
    return payload


def _edge(edge_id: str, source: str, target: str, **extra: Any) -> dict[str, Any]:
    return {"edge_id": edge_id, "source_node_id": source, "target_node_id": target, **extra}


def _condition(field: str, operator: str, value: Any = None) -> dict[str, Any]:
    payload = {"field": field, "operator": operator}
    if value is not None:
        payload["value"] = value
    return payload
