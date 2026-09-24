import CheckIcon from '@mui/icons-material/Check';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined';
import {
  Alert,
  Box,
  CircularProgress,
  IconButton,
  Paper,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import type { ReactNode } from 'react';
import { useState } from 'react';

/** Loading component with smooth institutional spinner */
export function LoadingBlock({ label = '正在加载' }: { label?: string }) {
  return (
    <Stack
      direction="row"
      spacing={1.5}
      alignItems="center"
      sx={{
        minHeight: 180,
        justifyContent: 'center',
        color: 'text.secondary',
      }}
    >
      <CircularProgress size={18} thickness={4.5} sx={{ color: 'primary.main' }} />
      <Typography variant="body2" sx={{ fontWeight: 500, letterSpacing: '0.01em' }}>
        {label}
      </Typography>
    </Stack>
  );
}

/** Error block with clear border and clean typography */
export function ErrorBlock({ error }: { error: unknown }) {
  return (
    <Alert severity="error" variant="outlined" sx={{ fontWeight: 500 }}>
      {error instanceof Error ? error.message : String(error)}
    </Alert>
  );
}

/** Empty state block with subtle vector illustration and actionable layout */
export function EmptyBlock({
  children = '暂无数据',
  title,
  action,
}: {
  children?: ReactNode;
  title?: string;
  action?: ReactNode;
}) {
  return (
    <Box
      sx={{
        minHeight: 140,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        px: 3,
        py: 4,
        textAlign: 'center',
      }}
    >
      <Box
        sx={{
          width: 44,
          height: 44,
          borderRadius: '50%',
          bgcolor: 'action.hover',
          display: 'grid',
          placeItems: 'center',
          mb: 1.5,
          color: 'text.disabled',
        }}
      >
        <InboxOutlinedIcon sx={{ fontSize: 24 }} />
      </Box>
      {title && (
        <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 0.5, color: 'text.primary' }}>
          {title}
        </Typography>
      )}
      <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 420 }}>
        {children}
      </Typography>
      {action && <Box sx={{ mt: 2 }}>{action}</Box>}
    </Box>
  );
}

/** Section title with institutional indicator bar and clean typography */
export function SectionTitle({
  title,
  subtitle,
  action,
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
}) {
  return (
    <Stack
      direction="row"
      alignItems="center"
      justifyContent="space-between"
      spacing={2}
      sx={{ minHeight: 34, mb: 1.25 }}
    >
      <Stack direction="row" alignItems="center" spacing={1.25}>
        <Box
          aria-hidden
          sx={{
            width: 3.5,
            height: 18,
            borderRadius: '2px',
            bgcolor: 'primary.main',
            flexShrink: 0,
          }}
        />
        <Box>
          <Typography variant="h2" sx={{ fontWeight: 700, fontSize: { xs: 16, sm: 17 } }}>
            {title}
          </Typography>
          {subtitle && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.2 }}>
              {subtitle}
            </Typography>
          )}
        </Box>
      </Stack>
      {action}
    </Stack>
  );
}

/** Professional status badge with glowing pulse dot indicator */
export interface StatusBadgeProps {
  status: string | null | undefined;
  label?: string;
  pulse?: boolean;
  size?: 'small' | 'medium';
}

export function StatusBadge({ status, label, pulse, size = 'small' }: StatusBadgeProps) {
  const text = label || statusLabel(status);
  const color = statusColor(status);
  const shouldPulse =
    pulse ??
    (status === 'RUNNING' ||
      status === 'CLAIMED' ||
      status === 'ACTIVE' ||
      status === 'STARTED' ||
      status === 'SUBMITTING');

  const colorStyles = {
    success: {
      bg: 'rgba(21, 128, 61, 0.08)',
      border: 'rgba(21, 128, 61, 0.28)',
      text: '#14532d',
      dot: '#16a34a',
    },
    warning: {
      bg: 'rgba(180, 83, 9, 0.08)',
      border: 'rgba(180, 83, 9, 0.28)',
      text: '#92400e',
      dot: '#d97706',
    },
    error: {
      bg: 'rgba(185, 28, 28, 0.08)',
      border: 'rgba(185, 28, 28, 0.28)',
      text: '#7f1d1d',
      dot: '#dc2626',
    },
    info: {
      bg: 'rgba(29, 78, 216, 0.08)',
      border: 'rgba(29, 78, 216, 0.28)',
      text: '#1e3a8a',
      dot: '#2563eb',
    },
    default: {
      bg: 'rgba(100, 116, 139, 0.08)',
      border: 'rgba(100, 116, 139, 0.22)',
      text: '#334155',
      dot: '#94a3b8',
    },
  }[color];

  return (
    <Box
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.75,
        px: size === 'small' ? 1 : 1.25,
        py: size === 'small' ? 0.25 : 0.4,
        borderRadius: '4px',
        bgcolor: colorStyles.bg,
        border: '1px solid',
        borderColor: colorStyles.border,
        color: colorStyles.text,
        fontSize: size === 'small' ? 11.5 : 12.5,
        fontWeight: 650,
        lineHeight: 1.3,
        letterSpacing: '0.01em',
        fontVariantNumeric: 'tabular-nums',
        fontFeatureSettings: '"tnum"',
        whiteSpace: 'nowrap',
        userSelect: 'none',
      }}
    >
      <Box
        component="span"
        sx={{
          width: 6,
          height: 6,
          borderRadius: '50%',
          bgcolor: colorStyles.dot,
          flexShrink: 0,
          boxShadow: `0 0 6px ${colorStyles.dot}`,
          ...(shouldPulse && {
            animation: 'finbotPulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite',
          }),
        }}
      />
      {text}
    </Box>
  );
}

/** PnL formatted text with institutional green/red coloring and sign */
export function PnlText({
  value,
  currency = 'USDT',
  showPlus = true,
}: {
  value: number | string | null | undefined;
  currency?: string;
  showPlus?: boolean;
}) {
  if (value === null || value === undefined || !Number.isFinite(Number(value))) {
    return <Typography component="span" variant="inherit" color="text.disabled">-</Typography>;
  }
  const num = Number(value);
  const isPositive = num > 0;
  const isNegative = num < 0;
  const prefix = isPositive && showPlus ? '+' : '';
  const formatted = `${prefix}${num.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  })} ${currency}`;

  const color = isPositive ? 'success.main' : isNegative ? 'error.main' : 'text.primary';

  return (
    <Typography
      component="span"
      variant="inherit"
      sx={{
        color,
        fontWeight: 650,
        fontVariantNumeric: 'tabular-nums',
        fontFeatureSettings: '"tnum"',
      }}
    >
      {formatted}
    </Typography>
  );
}

/** One-click copyable identifier text with tooltip feedback */
export function CopyableText({
  text,
  display,
  tooltip = '点击复制',
}: {
  text: string;
  display?: string;
  tooltip?: string;
}) {
  const [copied, setCopied] = useState(false);
  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation();
    void navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 1600);
  };

  return (
    <Tooltip title={copied ? '已复制到剪贴板' : tooltip} arrow>
      <Box
        component="span"
        onClick={handleCopy}
        sx={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 0.5,
          cursor: 'pointer',
          borderRadius: '4px',
          px: 0.6,
          py: 0.2,
          bgcolor: 'action.hover',
          border: '1px solid',
          borderColor: 'divider',
          fontFamily: 'monospace',
          fontSize: '0.85em',
          fontWeight: 600,
          color: 'text.secondary',
          transition: 'all 0.15s ease',
          '&:hover': {
            bgcolor: 'action.selected',
            color: 'primary.main',
            borderColor: 'primary.light',
          },
        }}
      >
        <span>{display || text}</span>
        {copied ? (
          <CheckIcon sx={{ fontSize: 13, color: 'success.main' }} />
        ) : (
          <ContentCopyIcon sx={{ fontSize: 12, opacity: 0.7 }} />
        )}
      </Box>
    </Tooltip>
  );
}

export interface MetricStripItem {
  label: string;
  value: ReactNode;
  detail?: ReactNode;
  tone?: 'default' | 'success' | 'warning' | 'error';
}

export function MetricStrip({ items }: { items: MetricStripItem[] }) {
  return (
    <Paper
      variant="outlined"
      sx={{
        display: 'grid',
        gridTemplateColumns: {
          xs: '1fr',
          sm: 'repeat(2, minmax(0, 1fr))',
          xl: `repeat(${items.length}, minmax(0, 1fr))`,
        },
        overflow: 'hidden',
        boxShadow: '0 1px 3px 0 rgba(15, 23, 42, 0.04)',
      }}
    >
      {items.map((item, index) => (
        <Box
          key={item.label}
          sx={{
            minWidth: 0,
            px: { xs: 2, md: 2.25 },
            py: 2,
            borderTop: { xs: index ? '1px solid' : 0, sm: index > 1 ? '1px solid' : 0, xl: 0 },
            borderLeft: { xs: 0, sm: index % 2 ? '1px solid' : 0, xl: index ? '1px solid' : 0 },
            borderColor: 'divider',
            transition: 'background-color 0.15s ease',
            '&:hover': {
              bgcolor: 'rgba(15, 23, 42, 0.015)',
            },
          }}
        >
          <Typography
            variant="caption"
            sx={{
              color: 'text.secondary',
              fontWeight: 600,
              letterSpacing: '0.02em',
              textTransform: 'uppercase',
              fontSize: 11,
              display: 'block',
            }}
          >
            {item.label}
          </Typography>
          <Typography
            variant="h2"
            sx={{
              mt: 0.5,
              color:
                item.tone && item.tone !== 'default'
                  ? `${item.tone}.main`
                  : 'text.primary',
              fontVariantNumeric: 'tabular-nums',
              overflowWrap: 'anywhere',
              fontWeight: 700,
              fontSize: { xs: 20, sm: 22 },
            }}
          >
            {item.value}
          </Typography>
          {item.detail !== undefined && (
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.35, fontSize: 12 }}
            >
              {item.detail}
            </Typography>
          )}
        </Box>
      ))}
    </Paper>
  );
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

