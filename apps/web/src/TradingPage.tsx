import RefreshIcon from '@mui/icons-material/Refresh';
import { Box, Button, Chip, FormControl, FormControlLabel, InputLabel, MenuItem, Paper, Select, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import type { AccountsOverview, ActivityRecord, PositionRecord, TradeAutomationDetail, TradeAutomationSummary } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatMoney, formatTime, jsonList, statusColor, statusLabel } from './ui';

export function TradingPage() {
  const [range, setRange] = useState('DAYS_30');
  const [accounts, setAccounts] = useState<AccountsOverview | null>(null);
  const [activity, setActivity] = useState<ActivityRecord[]>([]);
  const [automations, setAutomations] = useState<TradeAutomationSummary[]>([]);
  const [positions, setPositions] = useState<PositionRecord[]>([]);
  const [selectedAccount, setSelectedAccount] = useState<string>('');
  const [execution, setExecution] = useState<TradeAutomationDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [savingAccountId, setSavingAccountId] = useState('');
  const refresh = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const [accountData, activityData, automationData] = await Promise.all([api.accounts(range), api.activity({ range, limit: 100 }), api.tradeAutomations(50)]);
      setAccounts(accountData); setActivity(activityData.activities); setAutomations(automationData);
      const accountId = selectedAccount || accountData.accounts[0]?.accountId || '';
      setSelectedAccount(accountId);
      setPositions(accountId ? await api.positions(accountId) : []);
    } catch (cause) { setError(cause); } finally { setLoading(false); }
  }, [range, selectedAccount]);
  useEffect(() => { void refresh(); }, [range]);
  const chooseAccount = async (accountId: string) => { setSelectedAccount(accountId); try { setPositions(await api.positions(accountId)); } catch (cause) { setError(cause); } };
  const chooseExecution = async (runId: string) => { try { setExecution(await api.tradeAutomation(runId)); } catch (cause) { setError(cause); } };
  const setAccountEnabled = async (accountId: string, enabled: boolean, expectedVersion: number) => {
    setSavingAccountId(accountId); setError(null);
    try { await api.setExchangeAccountEnabled(accountId, enabled, expectedVersion); await refresh(); }
    catch (cause) { setError(cause); }
    finally { setSavingAccountId(''); }
  };
  if (error !== null && !accounts) return <ErrorBlock error={error} />;
  if (loading && !accounts) return <LoadingBlock label="正在同步账户与执行历史" />;
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}
    <Stack direction="row" justifyContent="space-between" alignItems="center"><SectionTitle title="账户盈亏" /><Stack direction="row" spacing={1}><FormControl size="small" sx={{ minWidth: 130 }}><InputLabel>时间区间</InputLabel><Select label="时间区间" value={range} onChange={(event) => setRange(event.target.value)}><MenuItem value="HOURS_24">24 小时</MenuItem><MenuItem value="DAYS_7">7 天</MenuItem><MenuItem value="DAYS_30">30 天</MenuItem><MenuItem value="ALL">全部历史</MenuItem></Select></FormControl><Button startIcon={<RefreshIcon />} onClick={() => void refresh()}>同步视图</Button></Stack></Stack>
    {accounts && <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>{[['总权益', formatMoney(accounts.totalEquity, accounts.currency)], ['可用余额', formatMoney(accounts.totalAvailableBalance, accounts.currency)], ['未实现盈亏', formatMoney(accounts.totalUnrealizedPnl, accounts.currency)], ['已实现盈亏', formatMoney(accounts.totalRealizedPnl, accounts.currency)]].map(([label, value]) => <Paper key={label} variant="outlined" sx={{ p: 2, flex: 1 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="h2" color={label.includes('盈亏') && Number(value.split(' ')[0].replace(/,/g, '')) < 0 ? 'error.main' : 'text.primary'}>{value}</Typography></Paper>)}</Stack>
      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2}>{accounts.accounts.map((account) => <Paper key={account.accountId} variant="outlined" onClick={() => void chooseAccount(account.accountId)} sx={{ p: 2, flex: 1, cursor: 'pointer', borderColor: selectedAccount === account.accountId ? 'primary.main' : 'divider' }}><Stack direction="row" justifyContent="space-between" spacing={1}><Box><Typography fontWeight={700}>{account.displayName}</Typography><Typography variant="caption" color="text.secondary">{account.exchange} · {account.environment}</Typography></Box><Stack alignItems="flex-end"><Chip size="small" color={account.enabled && account.dataStatus === 'READY' ? 'success' : 'warning'} label={!account.enabled ? '已停用' : account.dataStatus === 'READY' ? '数据正常' : account.dataStatus} /><FormControlLabel sx={{ mr: 0, mt: 0.5 }} control={<Switch size="small" checked={account.enabled} disabled={savingAccountId === account.accountId} onClick={(event) => event.stopPropagation()} onChange={(event) => { event.stopPropagation(); void setAccountEnabled(account.accountId, event.target.checked, account.version); }} />} label="启用" /></Stack></Stack><Typography variant="h2" sx={{ mt: 1 }}>{formatMoney(account.equity, account.currency)}</Typography><Typography variant="body2" color="text.secondary">未实现 {formatMoney(account.unrealizedPnl, account.currency)} · 已实现 {formatMoney(account.realizedPnl, account.currency)}</Typography><Typography variant="caption" color="text.secondary">快照 {formatTime(account.snapshotAt)}</Typography></Paper>)}</Stack>
    </>}
    <Box><SectionTitle title="当前持仓" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>标的</TableCell><TableCell>方向</TableCell><TableCell>数量</TableCell><TableCell>开仓 / 标记</TableCell><TableCell>杠杆</TableCell><TableCell>强平价</TableCell><TableCell>浮动盈亏</TableCell></TableRow></TableHead><TableBody>{positions.map((position) => <TableRow key={`${position.accountId}-${position.symbol}-${position.side}`}><TableCell>{position.symbol}</TableCell><TableCell>{statusLabel(position.side)}</TableCell><TableCell>{position.quantity}</TableCell><TableCell>{position.entryPrice} / {position.markPrice}</TableCell><TableCell>{position.leverage}x</TableCell><TableCell>{position.liquidationPrice ?? '-'}</TableCell><TableCell sx={{ color: Number(position.unrealizedPnl) < 0 ? 'error.main' : 'success.main' }}>{formatMoney(position.unrealizedPnl)}</TableCell></TableRow>)}</TableBody></Table>{positions.length === 0 && <EmptyBlock>当前无持仓</EmptyBlock>}</Paper></Box>
    <Box><SectionTitle title="AI 决策与模拟执行" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>时间</TableCell><TableCell>标的</TableCell><TableCell>决策</TableCell><TableCell>置信度</TableCell><TableCell>订单</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{automations.map((item) => <TableRow key={item.automationRunId} hover selected={execution?.summary.automationRunId === item.automationRunId} onClick={() => void chooseExecution(item.workflowRunId)} sx={{ cursor: 'pointer' }}><TableCell>{formatTime(item.startedAt)}</TableCell><TableCell>{item.symbol || '-'}</TableCell><TableCell>{statusLabel(item.action)}</TableCell><TableCell>{item.confidence === null ? '-' : `${(Number(item.confidence) * 100).toFixed(0)}%`}</TableCell><TableCell>{item.orderCount}</TableCell><TableCell><Chip size="small" color={statusColor(item.status)} label={statusLabel(item.status)} /></TableCell></TableRow>)}</TableBody></Table></Paper></Box>
    {execution && <ExecutionDetail detail={execution} />}
    <Box><SectionTitle title="交易所事实历史" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>时间</TableCell><TableCell>来源</TableCell><TableCell>类型</TableCell><TableCell>标的</TableCell><TableCell>状态</TableCell><TableCell>数量 / 价格</TableCell></TableRow></TableHead><TableBody>{activity.map((item) => <TableRow key={item.activityId}><TableCell>{formatTime(item.occurredAt)}</TableCell><TableCell>{item.exchange} · {item.source}</TableCell><TableCell>{item.activityType}</TableCell><TableCell>{item.symbol || '-'}</TableCell><TableCell>{statusLabel(item.status)}</TableCell><TableCell>{item.quantity ?? '-'} / {item.price ?? '-'}</TableCell></TableRow>)}</TableBody></Table></Paper></Box>
  </Stack>;
}

function ExecutionDetail({ detail }: { detail: TradeAutomationDetail }) {
  return <Stack spacing={1.5}><SectionTitle title="执行审计详情" />
    {detail.decision && <Paper variant="outlined" sx={{ p: 2 }}><Stack direction="row" justifyContent="space-between"><Box><Typography fontWeight={700}>{detail.decision.symbol} · {statusLabel(detail.decision.action)}</Typography><Typography variant="caption" color="text.secondary">建议 {statusLabel(detail.decision.proposalStatus)}，由最终机器人、风控与 OMS 自动流转</Typography></Box><Chip label={`置信度 ${(Number(detail.decision.confidence) * 100).toFixed(0)}%`} /></Stack><Typography variant="body2" sx={{ mt: 1 }}>入场 {detail.decision.entryReference ?? '-'} · 目标 {detail.decision.targetPrice ?? '-'} · 失效 {detail.decision.invalidationPrice ?? '-'}</Typography>{jsonList(detail.decision.rationaleJson).map((reason) => <Typography key={reason} variant="body2" color="text.secondary">• {reason}</Typography>)}</Paper>}
    <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5}>{detail.aiReviews.map((review) => <Paper key={review.reviewId} variant="outlined" sx={{ p: 2, flex: 1 }}><Stack direction="row" justifyContent="space-between"><Typography fontWeight={700}>{review.stage === 'DRAFT' ? 'Sol 初审' : 'Sol 反思终审'}</Typography><Chip size="small" color={statusColor(review.status)} label={statusLabel(review.status)} /></Stack><Typography variant="caption" color="text.secondary">{review.providerProfileId} / {review.modelName} / {review.reasoningEffort}</Typography><Typography component="pre" variant="body2" sx={{ mt: 1, whiteSpace: 'pre-wrap', maxHeight: 260, overflow: 'auto' }}>{pretty(review.outputJson || review.errorMessage || '-')}</Typography></Paper>)}</Stack>
    {detail.riskAssessments.map((risk) => <Paper key={risk.assessmentId} variant="outlined" sx={{ p: 2 }}><Stack direction="row" justifyContent="space-between"><Typography fontWeight={700}>风控 · {risk.accountId}</Typography><Chip size="small" color={statusColor(risk.status)} label={statusLabel(risk.status)} /></Stack><Typography variant="body2">数量 {risk.quantity ?? '-'} · 名义价值 {formatMoney(risk.notionalUsdt)} · 保证金 {formatMoney(risk.initialMarginUsdt)} · 杠杆 {risk.leverage ?? '-'}x · 最大损失 {formatMoney(risk.estimatedMaximumLossUsdt)}</Typography>{jsonList(risk.reasonsJson).map((reason) => <Typography key={reason} variant="caption" color="text.secondary" display="block">{reason}</Typography>)}</Paper>)}
    {detail.orders.map((order) => <Paper key={order.orderId} variant="outlined" sx={{ p: 2 }}><Stack direction="row" justifyContent="space-between"><Box><Typography fontWeight={700}>{order.exchange} {order.environment} · {order.symbol} {statusLabel(order.side)}</Typography><Typography variant="caption" color="text.secondary">{order.orderId} · client {order.clientOrderId || '-'}</Typography></Box><Chip size="small" color={statusColor(order.status)} label={statusLabel(order.status)} /></Stack><Typography variant="body2" sx={{ mt: 1 }}>请求 {order.requestedQuantity} · 成交 {order.filledQuantity} · 均价 {order.averageFillPrice ?? '-'} · {order.leverage}x</Typography>{order.submissionAttempts.map((attempt) => <Typography key={attempt.attemptId} variant="caption" display="block" color={attempt.status === 'REJECTED' ? 'error.main' : 'text.secondary'}>提交 #{attempt.attemptNumber} {statusLabel(attempt.status)} · HTTP {attempt.httpStatus ?? '-'} · {attempt.errorMessage || attempt.exchangeOrderId || '-'}</Typography>)}</Paper>)}
  </Stack>;
}
function pretty(value: string): string { try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
