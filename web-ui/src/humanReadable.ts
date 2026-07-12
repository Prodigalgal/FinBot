import type { ProductRecommendation } from './types';
import { statusText } from './utils';

export interface RecommendationNarrative {
  evidenceState: string;
  gateExplanation: string;
  nextAction: string;
  primaryReason: string;
  risk: string;
}

export function shortId(value?: string | null, length = 10): string {
  if (!value) {
    return '-';
  }
  return value.length <= length ? value : `${value.slice(0, length)}...`;
}

export function numberText(value?: number | null, maximumFractionDigits = 8): string {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '-';
  }
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits }).format(value);
}

export function percentText(value?: number | null, ratio = false): string {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '样本不足';
  }
  const normalized = ratio ? value * 100 : value;
  return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(normalized)}%`;
}

export function durationText(value?: number | null): string {
  if (typeof value !== 'number' || Number.isNaN(value)) {
    return '-';
  }
  if (value < 1000) {
    return `${Math.round(value)} ms`;
  }
  const seconds = value / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainingSeconds = Math.round(seconds % 60);
  return `${minutes} 分 ${remainingSeconds} 秒`;
}

export function listConfigValue(value: unknown): string {
  if (Array.isArray(value)) {
    return value.map((item) => String(item)).join(', ') || '-';
  }
  return String(value || '-');
}

export function recommendationActionText(action?: string | null): string {
  const map: Record<string, string> = {
    BUY: '买入',
    SELL: '卖出',
    HOLD: '观望',
    WATCH: '观察',
  };
  return action ? map[action] || action : '-';
}

export function agentRoleText(role?: string | null): string {
  const map: Record<string, string> = {
    bull_researcher: '多方研究',
    bear_researcher: '空方研究',
    market_structure: '市场结构',
    risk_controller: '风险控制',
    chair_arbiter: '主席仲裁',
  };
  return role ? map[role] || role : '-';
}

export function councilPhaseText(phase?: string | null): string {
  const map: Record<string, string> = {
    independent_analysis: '独立分析',
    cross_examination: '交叉质询',
    position_revision: '立场修订',
    chair_synthesis: '主席结论',
  };
  return phase ? map[phase] || phase : '-';
}

export function stanceText(stance?: string | null): string {
  const map: Record<string, string> = {
    bullish: '偏多',
    bearish: '偏空',
    market: '结构观察',
    risk: '风险优先',
    neutral: '中性仲裁',
  };
  return stance ? map[stance] || stance : '-';
}

export function triggerTypeText(triggerType?: string | null): string {
  const map: Record<string, string> = {
    scheduler: '定时调度',
    'manual-web': '网页手动触发',
    'p0-live-e2e': '端到端验证',
  };
  return triggerType ? map[triggerType] || triggerType : '-';
}

export function decisionReadinessText(status?: string | null): string {
  const map: Record<string, string> = {
    ready: '决策已就绪',
    'needs-followup': '需要补充证据',
    blocked: '决策已阻断',
    empty: '暂无可用决策',
  };
  return status ? map[status] || statusText(status) : '待新一轮评估';
}

export function decisionReadinessReasonText(reason: string): string {
  const map: Record<string, string> = {
    no_recommendations: '本轮没有形成产品建议。',
    no_product_recommendations: '本轮没有形成可进入决策流程的产品建议。',
    no_valid_market_data: '本轮没有通过有效性门禁的行情数据。',
    insufficient_market_data: '部分候选的价格或主周期 K 线不足，相关建议已阻断。',
    unconfirmed_research: '部分候选尚未完成研究证据确认。',
    research_evidence_unconfirmed: '部分候选尚未获得可交叉验证的研究证据。',
    no_directional_recommendation: '本轮没有达到方向性建议阈值，当前仅适合观察。',
    no_directional_recommendations: '本轮没有达到方向性建议阈值，当前仅适合观察。',
  };
  return map[reason] || humanizeAiText(reason.split('_').join(' '));
}

export function autonomousStepText(stepName: string): string {
  const map: Record<string, string> = {
    research_pipeline: '采集与处理',
    instrument_catalog: '交易产品目录',
    universe_selection: '产品 Universe',
    operator_workbench: '快速市场分析',
    product_candidates: '产品候选映射',
    ai_debate: '多 Agent 辩论',
    trade_synthesis: 'AI 决策合成',
    product_selection: '最终产品筛选',
    recommendation_evaluation: '建议历史评估',
    portfolio_risk: '组合风险',
    ai_governance: 'AI 治理',
    paper_execution: '多交易所模拟执行',
    publish_status: '结果发布',
  };
  return map[stepName] || stepName;
}

export function researchStepText(stepName: string): string {
  const map: Record<string, string> = {
    preflight: '运行前检查',
    ingestion_run: '信息源采集',
    process_evidence: '证据清理与归一化',
    macro_facts: '宏观事实整理',
    market_confirmation: '市场数据确认',
    ai_compression: 'AI 信息压缩',
    build_research_cards: '生成研究卡片',
    validate_research_cards: '研究卡片校验',
    promote_research_cards: '研究卡片晋级',
    dispatch_followups: '补证据任务分发',
    run_followups: '补证据执行',
    build_phase4_brief: '研究简报生成',
    build_phase41_council: '研究复核委员会',
    status_snapshot: '状态快照',
  };
  return map[stepName] || stepName;
}

export function researchStateText(value: unknown): string {
  const status = String(value || 'unknown');
  const map: Record<string, string> = {
    'needs-followup': '研究证据待补充',
    'market-confirmed': '跨交易所技术面已确认',
    passed: '研究证据已确认',
    promoted: '研究结论已晋级',
    'manual-review': '等待人工复核',
    unknown: '未关联研究确认',
  };
  return map[status] || statusText(status);
}

export function humanizeAiText(value: unknown): string {
  const text = String(value || '').trim();
  if (!text) {
    return '';
  }
  const deterministicAction = text.match(/^Action (BUY|SELL|HOLD) is derived from multi-timeframe trend, momentum, and 24h quote change\.$/i);
  if (deterministicAction) {
    return `建议由多周期趋势、动量与 24 小时价格变化共同得出，当前结论为${recommendationActionText(deterministicAction[1].toUpperCase())}。`;
  }
  const exactTranslations: Array<[RegExp, string]> = [
    [/^All three candidates have research status 'needs-followup', meaning no research confirmation is available\.$/i, '三个候选均处于“需补证据”状态，当前没有可用的研究确认。'],
    [/^LAB_USDT shows strong bearish alignment across timeframes but research is incomplete\.$/i, 'LAB_USDT 多周期技术信号一致偏空，但研究证据尚未完成。'],
    [/^LIT_USDT shows bullish alignment with high confidence, but research is incomplete\.$/i, 'LIT_USDT 多周期技术信号偏多，但研究证据尚未完成。'],
    [/^HYPE_USDT has mixed timeframe signals and low confidence, research incomplete\.$/i, 'HYPE_USDT 多周期信号存在分歧且信号强度偏低，研究证据尚未完成。'],
    [/^Strong bearish technical alignment \((\d+(?:\.\d+)?) agreement\) across 1h\/4h\/1d\.$/i, '1h、4h、1d 技术信号一致偏空（时间框架一致度 $1）。'],
    [/^Bullish technical alignment \((\d+(?:\.\d+)?) agreement\) with strong daily score \(([-\d.]+)\)\.$/i, '多周期技术信号一致偏多，日线评分为 $2（时间框架一致度 $1）。'],
    [/^Mixed timeframe signals \(agreement (\d+(?:\.\d+)?)\) and low confidence \((\d+(?:\.\d+)?)\)\.$/i, '多周期信号存在分歧（一致度 $1），原始信号强度为 $2。'],
    [/^Research status is 'needs-followup', so no fundamental confirmation\.$/i, '研究状态为“需补证据”，当前缺少研究确认。'],
    [/^Research status is 'needs-followup', no fundamental backing\.$/i, '研究状态为“需补证据”，当前缺少研究确认。'],
    [/^Due to missing research, action is WATCH instead of SELL\.$/i, '因研究证据未确认，系统将偏空信号降级为观察。'],
    [/^Action is WATCH pending research completion\.$/i, '研究完成前维持观察。'],
    [/^Action is HOLD due to insufficient evidence\.$/i, '因证据不足，当前建议观望。'],
    [/^Research not confirmed; may miss fundamental catalysts\.$/i, '研究尚未确认，可能遗漏基本面催化因素。'],
    [/^Research not confirmed; may miss fundamental risks\.$/i, '研究尚未确认，可能遗漏基本面风险。'],
    [/^High volatility \(([-\d.]+)%\) could lead to rapid reversals\.$/i, '波动率较高（$1%），价格可能快速反转。'],
    [/^Momentum slightly negative \(([-\d.]+)%\) could indicate short-term pullback\.$/i, '短期动量为负（$1%），存在回调压力。'],
    [/^Low confidence and mixed signals increase uncertainty\.$/i, '信号强度偏低且多周期方向分歧，不确定性较高。'],
    [/^Low volatility \(([-\d.]+)%\) and negative 24h change \(([-\d.]+)%\) suggest caution\.$/i, '波动率较低（$1%）且 24 小时涨跌为 $2%，当前应谨慎观察。'],
    [/^Confidence is below the default trade threshold; prefer HOLD or manual review\.$/i, '置信度低于默认方向阈值，建议观望或人工复核。'],
    [/^This is an advisory output only and cannot execute orders\.$/i, '该结果仅用于研究建议，系统不能执行订单。'],
  ];
  for (const [pattern, translated] of exactTranslations) {
    if (pattern.test(text)) {
      return text.replace(pattern, translated);
    }
  }
  return text
    .replace(/needs-followup/gi, '需补证据')
    .replace(/各BIWATCH/gi, '各标的保持观察')
    .replace(/\bWATCH\b/g, '观察')
    .replace(/\bHOLD\b/g, '观望')
    .replace(/\bBUY\b/g, '买入')
    .replace(/\bSELL\b/g, '卖出');
}

export function recommendationNarrative(item: ProductRecommendation): RecommendationNarrative {
  const researchStatus = item.research_context?.status;
  const fundamentalResearchStatus = String(item.research_context?.fundamental_research_status || '');
  const marketOnlyConfirmation = String(researchStatus || '') === 'market-confirmed'
    && String(item.research_context?.confirmation_scope || '') === 'market-only';
  const evidenceState = marketOnlyConfirmation && fundamentalResearchStatus === 'needs-followup'
    ? '跨交易所技术面已确认 · 基本面证据待补充'
    : researchStateText(researchStatus);
  const rationale = (item.rationale || []).map(humanizeAiText).filter(Boolean);
  const riskWarnings = (item.risk_warnings || []).map(humanizeAiText).filter(Boolean);
  const firstRationale = rationale[0] || '当前没有可展示的模型依据。';
  const needsFollowup = String(researchStatus || '') === 'needs-followup';
  const gatedToZero = item.score > 0 && item.confidence === 0;

  let primaryReason = firstRationale;
  if (needsFollowup) {
    const signal = /bearish|偏空|看空|看跌/i.test(String((item.rationale || [])[0] || ''))
      ? '技术信号偏空'
      : /bullish|偏多|看多|看涨/i.test(String((item.rationale || [])[0] || ''))
        ? '技术信号偏多'
        : /mixed|分歧|矛盾/i.test(String((item.rationale || [])[0] || ''))
          ? '多周期信号存在分歧'
          : firstRationale;
    primaryReason = `${signal}，但研究证据尚未确认，因此当前仅${recommendationActionText(item.action)}。`;
  }

  return {
    evidenceState,
    primaryReason,
    risk: riskWarnings[0] || '未提供额外风险提示。',
    nextAction: needsFollowup
      ? '补齐研究证据后重新评估，在此之前不建立方向性仓位。'
      : marketOnlyConfirmation && fundamentalResearchStatus === 'needs-followup'
        ? '可按跨交易所技术信号进入人工复核；未确认的基本面事件仍作为独立风险项持续跟踪。'
        : '持续观察风险条件和结论失效条件。',
    gateExplanation: gatedToZero
      ? '候选评分来自市场与技术筛选；研究/风控门禁未确认，最终建议置信度因此归零。'
      : '最终建议置信度已综合研究确认与风险门禁。',
  };
}

export function stepOutputText(output?: Record<string, unknown>): string {
  if (!output) {
    return '-';
  }
  const status = output.status ? statusText(String(output.status)) : '';
  const runId = output.run_id ? `运行 ${shortId(String(output.run_id), 8)}` : '';
  const reportId = output.report_id ? `报告 ${shortId(String(output.report_id), 8)}` : '';
  const count = output.recommended_products && typeof output.recommended_products === 'object'
    ? `建议 ${(output.recommended_products as { count?: number }).count ?? 0} 条`
    : '';
  const candidates = output.candidates && typeof output.candidates === 'object'
    ? `候选 ${(output.candidates as { count?: number }).count ?? 0} 个`
    : '';
  const decisions = output.ai_decisions && typeof output.ai_decisions === 'object'
    ? `AI 决策 ${(output.ai_decisions as { count?: number }).count ?? 0} 条`
    : '';
  const debateId = output.debate_id ? `辩论 ${shortId(String(output.debate_id), 8)}` : '';
  return [status, runId, reportId, debateId, candidates, decisions, count].filter(Boolean).join(' · ') || '-';
}

export function reportKindTitle(kind: string): string {
  const map: Record<string, string> = {
    'autonomous-loop': '自动研究报告',
    'operator-workbench': '市场分析报告',
    'research-pipeline': '采集与处理报告',
    'ingestion-status': '采集状态报告',
    'recommendation-evaluation': '建议历史评估',
    'portfolio-risk': '组合风险报告',
    'ai-governance': 'AI 治理报告',
  };
  return map[kind] || kind;
}

export function reportKindDescription(kind: string): string {
  const map: Record<string, string> = {
    'autonomous-loop': '一次完整自动研究、辩论、筛选和治理闭环。',
    'operator-workbench': '公共行情驱动的确定性建议及纸面方案。',
    'research-pipeline': '信息采集、清理、压缩、研究与补证据执行情况。',
    'ingestion-status': '信息源、证据库存和采集阻断项。',
    'recommendation-evaluation': '历史建议成熟度、命中与置信度校准结果。',
    'portfolio-risk': '当前建议组合的方向暴露、集中度与压力风险。',
    'ai-governance': 'AI 调用成功率、Token、成本和证据覆盖审计。',
  };
  return map[kind] || '系统生成的结构化报告。';
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function recordArray(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter(isRecord) : [];
}
