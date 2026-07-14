import { Alert, Box, CircularProgress, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

export function LoadingBlock({ label = '正在加载' }: { label?: string }) {
  return <Stack direction="row" spacing={1.25} alignItems="center" sx={{ py: 6, justifyContent: 'center' }}><CircularProgress size={22} /><Typography color="text.secondary">{label}</Typography></Stack>;
}

export function ErrorBlock({ error }: { error: unknown }) {
  return <Alert severity="error">{error instanceof Error ? error.message : String(error)}</Alert>;
}

export function EmptyBlock({ children = '暂无数据' }: { children?: ReactNode }) {
  return <Box sx={{ py: 5, textAlign: 'center', color: 'text.secondary' }}><Typography>{children}</Typography></Box>;
}

export function SectionTitle({ title, action }: { title: string; action?: ReactNode }) {
  return <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={2} sx={{ mb: 1.5 }}><Typography variant="h2">{title}</Typography>{action}</Stack>;
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
    SUBMITTED: '已提交交易所', APPROVED: '已批准', REJECTED: '已拒绝', FILLED: '已成交',
    PARTIALLY_FILLED: '部分成交', PLANNED: '已规划', SUBMITTING: '提交中', WATCH: '观察',
    HOLD: '持有/等待', BUY: '买入', SELL: '卖出', GENERATED: '已生成建议',
  };
  return status ? labels[status] || status : '-';
}

export function statusColor(status: string | null | undefined): 'default' | 'success' | 'warning' | 'error' | 'info' {
  if (!status) return 'default';
  if (['COMPLETED', 'FILLED', 'APPROVED', 'SUBMITTED', 'RUNNING'].includes(status)) return status === 'RUNNING' ? 'info' : 'success';
  if (['FAILED', 'REJECTED', 'CANCELLED'].includes(status)) return 'error';
  if (['PARTIAL', 'BLOCKED', 'ORDER_PLANNED', 'PARTIALLY_FILLED', 'PENDING', 'CLAIMED'].includes(status)) return 'warning';
  return 'default';
}

export function jsonList(value: string | null | undefined): string[] {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [value];
  }
}
