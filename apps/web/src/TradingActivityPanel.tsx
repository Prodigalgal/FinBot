import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import FilterAltOutlinedIcon from '@mui/icons-material/FilterAltOutlined';
import { Box, Button, Chip, Collapse, Divider, FormControl, IconButton, InputLabel, MenuItem, Paper, Select, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography, useMediaQuery, useTheme } from '@mui/material';
import { Fragment, useEffect, useState } from 'react';

import { api } from './api';
import type { AccountOverview, ActivityPage, ActivityRecord } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatMoney, formatTime, statusColor, statusLabel } from './ui';

type Cursor = ActivityPage['nextCursor'];
type Filters = { accountId: string; source: string; activityType: string; status: string; symbol: string; range: string; from: string; to: string; limit: number };
const activityTypes = ['DECISION', 'PROPOSAL', 'AI_REVIEW', 'RISK_ASSESSMENT', 'ESTIMATE', 'OMS_ORDER', 'OMS_EVENT', 'SUBMISSION_ATTEMPT', 'ACCOUNT', 'BALANCE', 'ORDER', 'FILL', 'POSITION', 'REALIZED_PNL', 'RECONCILIATION'];
const initialFilters: Filters = { accountId: '', source: '', activityType: '', status: '', symbol: '', range: 'DAYS_30', from: localDate(-7), to: localDate(0), limit: 50 };

export function TradingActivityPanel({ accounts }: { accounts: AccountOverview[] }) {
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'));
  const [draft, setDraft] = useState(initialFilters);
  const [filters, setFilters] = useState(initialFilters);
  const [payload, setPayload] = useState<ActivityPage | null>(null);
  const [cursor, setCursor] = useState<Cursor>(null);
  const [history, setHistory] = useState<Cursor[]>([]);
  const [expanded, setExpanded] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const load = async (nextFilters: Filters, nextCursor: Cursor) => {
    setLoading(true);
    setError(null);
    try {
      const custom = nextFilters.range === 'CUSTOM';
      setPayload(await api.activity({
        accountId: nextFilters.accountId || undefined,
        source: nextFilters.source || undefined,
        activityType: nextFilters.activityType || undefined,
        status: nextFilters.status || undefined,
        symbol: nextFilters.symbol || undefined,
        range: nextFilters.range,
        from: custom ? dayBoundary(nextFilters.from, false) : undefined,
        to: custom ? dayBoundary(nextFilters.to, true) : undefined,
        beforeOccurredAt: nextCursor?.occurredAt,
        beforeActivityId: nextCursor?.activityId,
        limit: nextFilters.limit,
      }));
      setCursor(nextCursor);
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(filters, null); }, []);

  const apply = () => {
    setFilters(draft);
    setHistory([]);
    setExpanded('');
    void load(draft, null);
  };
  const next = () => {
    if (!payload?.nextCursor) return;
    setHistory((values) => [...values, cursor]);
    setExpanded('');
    void load(filters, payload.nextCursor);
  };
  const previous = () => {
    if (history.length === 0) return;
    const nextHistory = history.slice(0, -1);
    const previousCursor = history[history.length - 1] || null;
    setHistory(nextHistory);
    setExpanded('');
    void load(filters, previousCursor);
  };

  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}><Stack direction="row" spacing={1} alignItems="center"><FilterAltOutlinedIcon color="primary" /><Typography fontWeight={700}>历史筛选</Typography></Stack><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25} flexWrap={{ md: 'wrap' }} useFlexGap>
      <TextField select size="small" label="账户" value={draft.accountId} onChange={(event) => setDraft({ ...draft, accountId: event.target.value })} sx={{ minWidth: 190 }}><MenuItem value="">全部账户与无账户记录</MenuItem>{accounts.map((account) => <MenuItem key={account.accountId} value={account.accountId}>{account.displayName}</MenuItem>)}</TextField>
      <TextField select size="small" label="来源" value={draft.source} onChange={(event) => setDraft({ ...draft, source: event.target.value })} sx={{ minWidth: 150 }}><MenuItem value="">全部来源</MenuItem><MenuItem value="LOCAL_OMS">FinBot 本地审计</MenuItem><MenuItem value="EXCHANGE">交易所事实</MenuItem></TextField>
      <TextField select size="small" label="阶段" value={draft.activityType} onChange={(event) => setDraft({ ...draft, activityType: event.target.value })} sx={{ minWidth: 170 }}><MenuItem value="">全部阶段</MenuItem>{activityTypes.map((type) => <MenuItem key={type} value={type}>{statusLabel(type)}</MenuItem>)}</TextField>
      <TextField size="small" label="状态" value={draft.status} onChange={(event) => setDraft({ ...draft, status: event.target.value })} placeholder="如 FILLED / BLOCKED" />
      <TextField size="small" label="标的" value={draft.symbol} onChange={(event) => setDraft({ ...draft, symbol: event.target.value })} placeholder="BTC / AAPL" />
      <FormControl size="small" sx={{ minWidth: 140 }}><InputLabel>时间区间</InputLabel><Select label="时间区间" value={draft.range} onChange={(event) => setDraft({ ...draft, range: event.target.value })}><MenuItem value="HOURS_24">24 小时</MenuItem><MenuItem value="DAYS_7">7 天</MenuItem><MenuItem value="DAYS_30">30 天</MenuItem><MenuItem value="ALL">全部历史</MenuItem><MenuItem value="CUSTOM">自定义</MenuItem></Select></FormControl>
      {draft.range === 'CUSTOM' && <><TextField size="small" type="date" label="开始日期" value={draft.from} onChange={(event) => setDraft({ ...draft, from: event.target.value })} InputLabelProps={{ shrink: true }} /><TextField size="small" type="date" label="结束日期" value={draft.to} onChange={(event) => setDraft({ ...draft, to: event.target.value })} InputLabelProps={{ shrink: true }} /></>}
      <TextField select size="small" label="每页" value={draft.limit} onChange={(event) => setDraft({ ...draft, limit: Number(event.target.value) })} sx={{ width: 100 }}>{[25, 50, 100, 200].map((limit) => <MenuItem key={limit} value={limit}>{limit}</MenuItem>)}</TextField>
      <Button variant="contained" onClick={apply} disabled={loading}>应用筛选</Button>
    </Stack></Stack></Paper>

    {loading && !payload ? <LoadingBlock label="正在读取永久交易仓库" /> : payload && <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
        <Metric label="匹配记录" value={payload.matchedCount} />
        <Metric label="AI 决策" value={count(payload, 'DECISION')} />
        <Metric label="风险/预估" value={count(payload, 'RISK_ASSESSMENT') + count(payload, 'ESTIMATE')} />
        <Metric label="本地订单事件" value={count(payload, 'OMS_ORDER') + count(payload, 'OMS_EVENT')} />
        <Metric label="交易所订单/成交" value={count(payload, 'ORDER') + count(payload, 'FILL')} />
      </Stack>

      <Box><SectionTitle title="数据来源与完整性" /><Stack spacing={1}>{payload.sources.map((source) => <Paper key={`${source.source}-${source.accountId || 'local'}`} variant="outlined" sx={{ px: 2, py: 1.25 }}><Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}><Box><Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap"><Typography fontWeight={700}>{source.source === 'LOCAL_OMS' ? 'FinBot 本地永久审计' : `${source.exchange} · ${source.accountId}`}</Typography><Chip size="small" color={statusColor(source.status)} label={statusLabel(source.status)} /><Chip size="small" variant="outlined" label={source.complete ? '来源完整' : '需要关注'} /></Stack><Typography variant="caption" color="text.secondary">{source.message}</Typography></Box><Typography variant="caption" color="text.secondary">最新 {formatTime(source.latestAt)}</Typography></Stack></Paper>)}</Stack></Box>

      <Box><SectionTitle title="统一操作时间线" />{desktop ? <DesktopActivityTable payload={payload} expanded={expanded} setExpanded={setExpanded} /> : <MobileActivityList payload={payload} expanded={expanded} setExpanded={setExpanded} />}{payload.activities.length === 0 && <Paper variant="outlined"><EmptyBlock>{payload.matchedCount === 0 ? '当前筛选条件下确实没有操作记录' : '本页没有可显示记录'}</EmptyBlock></Paper>}</Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="caption" color="text.secondary">第 {history.length + 1} 页 · 当前 {payload.activities.length} 条 / 共 {payload.matchedCount} 条</Typography><Stack direction="row" spacing={1}><Button variant="outlined" onClick={previous} disabled={loading || history.length === 0}>上一页</Button><Button variant="outlined" onClick={next} disabled={loading || !payload.nextCursor}>下一页</Button></Stack></Stack>
    </>}
  </Stack>;
}

function DesktopActivityTable({ payload, expanded, setExpanded }: { payload: ActivityPage; expanded: string; setExpanded: (id: string) => void }) {
  return <Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell padding="checkbox" /><TableCell>发生时间</TableCell><TableCell>来源 / 阶段</TableCell><TableCell>交易所 / 标的</TableCell><TableCell>方向</TableCell><TableCell align="right">数量 / 价格</TableCell><TableCell align="right">金额 / 盈亏</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{payload.activities.map((activity) => <Fragment key={activity.activityId}><TableRow hover selected={expanded === activity.activityId}><TableCell padding="checkbox"><IconButton size="small" onClick={() => setExpanded(expanded === activity.activityId ? '' : activity.activityId)}>{expanded === activity.activityId ? <ExpandLessIcon /> : <ExpandMoreIcon />}</IconButton></TableCell><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(activity.occurredAt)}</TableCell><TableCell><Typography variant="body2" fontWeight={700}>{activity.source === 'LOCAL_OMS' ? '本地审计' : '交易所事实'}</Typography><Typography variant="caption" color="text.secondary">{statusLabel(activity.activityType)}</Typography></TableCell><TableCell><Typography variant="body2">{activity.exchange || 'FinBot'}</Typography><Typography variant="caption" color="text.secondary">{activity.symbol || '-'}</Typography></TableCell><TableCell>{statusLabel(activity.side)}</TableCell><TableCell align="right">{activity.quantity ?? '-'} / {activity.price ?? '-'}</TableCell><TableCell align="right" sx={{ color: amountColor(activity) }}>{activity.amount === null ? '-' : formatMoney(activity.amount, activity.currency || 'USDT')}</TableCell><TableCell><Chip size="small" color={statusColor(activity.status)} label={statusLabel(activity.status)} /></TableCell></TableRow><TableRow><TableCell colSpan={8} sx={{ py: 0, borderBottom: expanded === activity.activityId ? undefined : 0 }}><Collapse in={expanded === activity.activityId} unmountOnExit><ActivityDetail activity={activity} /></Collapse></TableCell></TableRow></Fragment>)}</TableBody></Table></Paper>;
}

function MobileActivityList({ payload, expanded, setExpanded }: { payload: ActivityPage; expanded: string; setExpanded: (id: string) => void }) {
  return <Paper variant="outlined"><Stack divider={<Divider flexItem />}>{payload.activities.map((activity) => <Box key={activity.activityId} sx={{ p: 1.5 }}><Stack direction="row" justifyContent="space-between" spacing={1}><Box sx={{ minWidth: 0 }}><Typography fontWeight={700}>{activity.title}</Typography><Typography variant="caption" color="text.secondary">{formatTime(activity.occurredAt)} · {activity.exchange || 'FinBot'} · {activity.symbol || statusLabel(activity.activityType)}</Typography></Box><Chip size="small" color={statusColor(activity.status)} label={statusLabel(activity.status)} /></Stack><Typography variant="body2" color="text.secondary" sx={{ mt: 0.75 }}>{activity.detail}</Typography><Button size="small" endIcon={expanded === activity.activityId ? <ExpandLessIcon /> : <ExpandMoreIcon />} onClick={() => setExpanded(expanded === activity.activityId ? '' : activity.activityId)} sx={{ mt: 0.5 }}>{expanded === activity.activityId ? '收起详情' : '查看详情'}</Button><Collapse in={expanded === activity.activityId} unmountOnExit><ActivityDetail activity={activity} /></Collapse></Box>)}</Stack></Paper>;
}

function ActivityDetail({ activity }: { activity: ActivityRecord }) {
  const details = parseDetails(activity.detailsJson);
  const baseFacts: Array<[string, unknown]> = [
    ['事件', activity.title], ['说明', activity.detail], ['来源事件 ID', activity.sourceEventId],
    ['账户', activity.accountId], ['Client Order ID', activity.clientOrderId], ['Exchange Order ID', activity.exchangeOrderId],
  ];
  const facts = [...baseFacts, ...Object.entries(details)]
    .filter(([, value]) => value !== null && value !== undefined && value !== '');
  return <Box sx={{ my: 1, p: 1.5, bgcolor: 'action.hover' }}><Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(3, minmax(0, 1fr))' }, gap: 1.25 }}>{facts.map(([label, value], index) => <Box key={`${label}-${index}`} sx={{ minWidth: 0 }}><Typography variant="caption" color="text.secondary">{detailLabel(label)}</Typography><Typography variant="body2" sx={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{detailValue(value)}</Typography></Box>)}</Box></Box>;
}

function Metric({ label, value }: { label: string; value: number }) { return <Paper variant="outlined" sx={{ p: 1.5, flex: 1, minWidth: 0 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="h2">{value}</Typography></Paper>; }
function count(payload: ActivityPage, type: string): number { return payload.counts.find((item) => item.activityType === type)?.count || 0; }
function parseDetails(value: string): Record<string, unknown> { try { const parsed = JSON.parse(value) as unknown; return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}; } catch { return {}; } }
function detailValue(value: unknown): string { return typeof value === 'object' && value !== null ? JSON.stringify(value, null, 2) : String(value ?? '-'); }
function detailLabel(value: string): string { const labels: Record<string, string> = { workflowRunId: 'Workflow Run ID', decisionId: 'Decision ID', proposalId: 'Proposal ID', projectionId: 'Projection ID', assessmentId: 'Risk Assessment ID', automationRunId: 'Automation Run ID', omsOrderId: 'OMS Order ID', submissionAttemptId: 'Submission Attempt ID', policyVersion: '风控策略版本', entryReference: '入场参考', targetPrice: '目标价格', invalidationPrice: '失效价格', stopPrice: '止损价格', estimatedProfitUsdt: '预估净盈利', estimatedLossUsdt: '预估净亏损', riskRewardRatio: '盈亏比', errorCode: '错误代码', errorMessage: '错误说明' }; return labels[value] || value; }
function amountColor(activity: ActivityRecord): string | undefined { if (activity.amount === null || activity.amount === 0) return undefined; return activity.activityType.includes('PNL') || activity.activityType === 'FILL' || activity.activityType === 'ESTIMATE' ? (activity.amount > 0 ? 'success.main' : 'error.main') : undefined; }
function localDate(offsetDays: number): string { const date = new Date(); date.setDate(date.getDate() + offsetDays); return date.toISOString().slice(0, 10); }
function dayBoundary(value: string, end: boolean): string { const date = new Date(`${value}T00:00:00`); if (end) date.setDate(date.getDate() + 1); return date.toISOString(); }
