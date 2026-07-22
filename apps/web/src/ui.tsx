import { Alert, Box, CircularProgress, Paper, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

export function LoadingBlock({ label = '正在加载' }: { label?: string }) {
  return <Stack direction="row" spacing={1.25} alignItems="center" sx={{ minHeight: 180, justifyContent: 'center', color: 'text.secondary' }}><CircularProgress size={20} thickness={4} /><Typography variant="body2">{label}</Typography></Stack>;
}

export function ErrorBlock({ error }: { error: unknown }) {
  return <Alert severity="error" variant="outlined">{error instanceof Error ? error.message : String(error)}</Alert>;
}

export function EmptyBlock({ children = '暂无数据' }: { children?: ReactNode }) {
  return <Box sx={{ minHeight: 112, display: 'grid', placeItems: 'center', px: 2, py: 3, textAlign: 'center', color: 'text.secondary' }}><Typography variant="body2">{children}</Typography></Box>;
}

export function SectionTitle({ title, action }: { title: string; action?: ReactNode }) {
  return <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={2} sx={{ minHeight: 34, mb: 1.25 }}><Stack direction="row" alignItems="center" spacing={1}><Box aria-hidden sx={{ width: 3, height: 16, borderRadius: '2px', bgcolor: 'primary.main' }} /><Typography variant="h2">{title}</Typography></Stack>{action}</Stack>;
}

export interface MetricStripItem {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
  tone?: 'default' | 'success' | 'warning' | 'error';
}

export function MetricStrip({ items }: { items: MetricStripItem[] }) {
  return <Paper variant="outlined" sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', xl: `repeat(${items.length}, minmax(0, 1fr))` }, overflow: 'hidden' }}>
    {items.map((item, index) => <Box key={item.label} sx={{ minWidth: 0, px: { xs: 1.75, md: 2 }, py: 1.75, borderTop: { xs: index ? '1px solid' : 0, sm: index > 1 ? '1px solid' : 0, xl: 0 }, borderLeft: { xs: 0, sm: index % 2 ? '1px solid' : 0, xl: index ? '1px solid' : 0 }, borderColor: 'divider' }}>
      <Typography variant="caption" color="text.secondary">{item.label}</Typography>
      <Typography variant="h2" sx={{ mt: .35, color: item.tone && item.tone !== 'default' ? `${item.tone}.main` : 'text.primary', fontVariantNumeric: 'tabular-nums', overflowWrap: 'anywhere' }}>{item.value}</Typography>
      {item.detail !== undefined && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: .25 }}>{item.detail}</Typography>}
    </Box>)}
  </Paper>;
}

export function formatTime(value: string | null | undefined): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

export function formatMoney(value: number | null | undefined, currency = 'USDT'): string {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) return '-';
  return `${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })} ${currency}`;
}

export function statusLabel(status: string | null | undefined): string {
  const labels: Record<string, string> = {
    ACCEPTED: '已受理', RUNNING: '运行中', WAITING_HUMAN: '等待人工', PARTIAL: '部分完成',
    COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消', PENDING: '待领取', CLAIMED: '处理中',
    STARTED: '已启动', NO_ACTION: '无需操作', BLOCKED: '风控阻断', ORDER_PLANNED: '订单待提交',
    ESTIMATED: '已生成预估', SUBMITTED: '已提交交易所', APPROVED: '已批准', REJECTED: '已拒绝', FILLED: '已成交',
    PARTIALLY_FILLED: '部分成交', PLANNED: '已规划', SUBMITTING: '提交中', WATCH: '观察',
    HOLD: '持有/等待', BUY: '买入', SELL: '卖出', GENERATED: '已生成建议', PROPOSED: '建议待处理',
    READY: '就绪', WARNING: '需关注', DISABLED: '已停用', NO_DATA: '尚无数据', UNKNOWN: '未知',
    ACKNOWLEDGED: '交易所已确认', SNAPSHOT: '快照', PAUSED: '已暂停', DRAFT: '草稿',
    DECISION: 'AI 决策', PROPOSAL: '交易建议', AI_REVIEW: '最终执行机器人复核',
    RISK_ASSESSMENT: '确定性风控', ESTIMATE: '预估交易', OMS_ORDER: 'OMS 订单',
    OMS_EVENT: 'OMS 状态变化', SUBMISSION_ATTEMPT: '交易所提交尝试', ACCOUNT: '账户快照',
    BALANCE: '余额变动', ORDER: '交易所订单', FILL: '交易所成交', POSITION: '持仓快照',
    REALIZED_PNL: '已实现盈亏', RECONCILIATION: '交易所对账',
    ACTIVE: '有效', EXPIRED: '已过期', REVOKED: '已吊销',
  };
  return status ? labels[status] || status : '-';
}

export function statusColor(status: string | null | undefined): 'default' | 'success' | 'warning' | 'error' | 'info' {
  if (!status) return 'default';
  if (['COMPLETED', 'FILLED', 'APPROVED', 'SUBMITTED', 'READY', 'ACKNOWLEDGED', 'RUNNING', 'ACTIVE'].includes(status)) return status === 'RUNNING' ? 'info' : 'success';
  if (status === 'ESTIMATED') return 'info';
  if (['FAILED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'REVOKED'].includes(status)) return 'error';
  if (['PARTIAL', 'BLOCKED', 'WARNING', 'ORDER_PLANNED', 'PARTIALLY_FILLED', 'PENDING', 'CLAIMED'].includes(status)) return 'warning';
  if (['ACCEPTED', 'PROPOSED', 'GENERATED', 'ESTIMATE', 'NO_DATA'].includes(status)) return 'info';
  return 'default';
}

export function jsonList(value: string | null | undefined): string[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed) ? parsed.map(humanizeJsonValue) : [];
  } catch {
    return [value];
  }
}

function humanizeJsonValue(value: unknown): string {
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (!value || typeof value !== 'object') return String(value ?? '');
  const record = value as Record<string, unknown>;
  for (const key of ['summary', 'claim', 'text', 'description', 'reference', 'url', 'title']) {
    const candidate = record[key];
    if (typeof candidate === 'string' && candidate.trim()) return candidate.trim();
  }
  return JSON.stringify(record);
}
