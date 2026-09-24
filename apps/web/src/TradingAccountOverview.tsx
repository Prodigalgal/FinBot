import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, FormControl, FormControlLabel, InputLabel, MenuItem, Paper, Select, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import { SecretTextField } from './SecretTextField';
import type { AccountOverview, AccountsOverview, PositionRecord, TradeAutomationDetail, TradeAutomationSummary } from './types';
import { EmptyBlock, ErrorBlock, MetricStrip, SectionTitle, StatusBadge, PnlText, formatMoney, formatTime, statusLabel } from './ui';
import { TradingExecutionDetail } from './TradingExecutionDetail';

type RangePreset = 'HOURS_24' | 'DAYS_7' | 'DAYS_30' | 'ALL' | 'CUSTOM';

export function TradingAccountOverview({ initialAccounts, onAccountsChanged }: { initialAccounts: AccountsOverview; onAccountsChanged: (accounts: AccountsOverview) => void }) {
  const [range, setRange] = useState<RangePreset>('DAYS_30');
  const [customFrom, setCustomFrom] = useState(localDate(-7));
  const [customTo, setCustomTo] = useState(localDate(0));
  const [accounts, setAccounts] = useState(initialAccounts);
  const [positions, setPositions] = useState<PositionRecord[]>([]);
  const [automations, setAutomations] = useState<TradeAutomationSummary[]>([]);
  const [selectedAccount, setSelectedAccount] = useState(initialAccounts.accounts[0]?.accountId || '');
  const [execution, setExecution] = useState<TradeAutomationDetail | null>(null);
  const [savingAccountId, setSavingAccountId] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [credentialDraft, setCredentialDraft] = useState({ apiKey: '', apiSecret: '' });

  useEffect(() => { setAccounts(initialAccounts); }, [initialAccounts]);
  useEffect(() => { void refresh(); }, []);

  const rangeParams = () => range === 'CUSTOM'
    ? { from: dayBoundary(customFrom, false), to: dayBoundary(customTo, true) }
    : { from: undefined, to: undefined };

  const refresh = async () => {
    setLoading(true);
    setError(null);
    try {
      const boundaries = rangeParams();
      const [accountData, automationData] = await Promise.all([
        api.accounts(range, boundaries.from, boundaries.to),
        api.tradeAutomations(100),
      ]);
      setAccounts(accountData);
      onAccountsChanged(accountData);
      setAutomations(automationData);
      const accountId = selectedAccount || accountData.accounts[0]?.accountId || '';
      setSelectedAccount(accountId);
      setPositions(accountId ? await api.positions(accountId) : []);
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  };

  const chooseAccount = async (accountId: string) => {
    setSelectedAccount(accountId);
    setCredentialDraft({ apiKey: '', apiSecret: '' });
    setError(null);
    try { setPositions(await api.positions(accountId)); } catch (cause) { setError(cause); }
  };

  const putCredential = async (account: AccountOverview, secretName: 'API_KEY' | 'API_SECRET', value: string, version: number) => {
    if (value.trim().length < 8) return;
    setLoading(true); setError(null); setMessage('');
    try { await api.putRuntimeSecret('EXCHANGE_ACCOUNT', account.accountId, secretName, value.trim(), version); setCredentialDraft((current) => ({ ...current, [secretName === 'API_KEY' ? 'apiKey' : 'apiSecret']: '' })); setMessage(`${account.displayName}凭据已热更新`); await refresh(); }
    catch (cause) { setError(cause); } finally { setLoading(false); }
  };

  const clearCredential = async (account: AccountOverview, secretName: 'API_KEY' | 'API_SECRET', version: number) => {
    setLoading(true); setError(null); setMessage('');
    try { await api.clearRuntimeSecret('EXCHANGE_ACCOUNT', account.accountId, secretName, version); setMessage(`${account.displayName}已恢复启动备用凭据`); await refresh(); }
    catch (cause) { setError(cause); } finally { setLoading(false); }
  };

  const testAccount = async (account: AccountOverview) => {
    setLoading(true); setError(null); setMessage('');
    try { await api.testExchangeAccount(account.accountId); setMessage(`${account.displayName}在线同步测试通过`); await refresh(); }
    catch (cause) { setError(cause); } finally { setLoading(false); }
  };

  const setAccountEnabled = async (accountId: string, enabled: boolean, expectedVersion: number) => {
    setSavingAccountId(accountId);
    setError(null);
    try {
      await api.setExchangeAccountEnabled(accountId, enabled, expectedVersion);
      await refresh();
    } catch (cause) {
      setError(cause);
    } finally {
      setSavingAccountId('');
    }
  };

  const chooseExecution = async (workflowRunId: string) => {
    setError(null);
    try { setExecution(await api.tradeAutomation(workflowRunId)); } catch (cause) { setError(cause); }
  };

  return <Stack spacing={2.25}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}>
      <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel>盈亏区间</InputLabel><Select label="盈亏区间" value={range} onChange={(event) => setRange(event.target.value as RangePreset)}><MenuItem value="HOURS_24">24 小时</MenuItem><MenuItem value="DAYS_7">7 天</MenuItem><MenuItem value="DAYS_30">30 天</MenuItem><MenuItem value="ALL">全部历史</MenuItem><MenuItem value="CUSTOM">自定义</MenuItem></Select></FormControl>
      {range === 'CUSTOM' && <><TextField size="small" label="开始日期" type="date" value={customFrom} onChange={(event) => setCustomFrom(event.target.value)} InputLabelProps={{ shrink: true }} /><TextField size="small" label="结束日期" type="date" value={customTo} onChange={(event) => setCustomTo(event.target.value)} InputLabelProps={{ shrink: true }} /></>}
      <Button variant="outlined" startIcon={<RefreshIcon />} onClick={() => void refresh()} disabled={loading}>应用区间</Button>
      <Typography variant="caption" color="text.secondary" sx={{ ml: { lg: 'auto' } }}>统计区间 {formatTime(accounts.range.fromInclusive)} 至 {formatTime(accounts.range.toExclusive)} · 生成于 {formatTime(accounts.generatedAt)}</Typography>
    </Stack>

    <MetricStrip items={[
      { label: '总权益', value: formatMoney(accounts.totalEquity, accounts.currency), detail: '全部模拟账户合计' },
      { label: '可用余额', value: formatMoney(accounts.totalAvailableBalance, accounts.currency), detail: '当前可用于模拟委托' },
      { label: '未实现盈亏', value: <PnlText value={accounts.totalUnrealizedPnl} currency={accounts.currency} />, detail: '当前持仓浮动盈亏', tone: Number(accounts.totalUnrealizedPnl) < 0 ? 'error' : 'success' },
      { label: '区间已实现盈亏', value: <PnlText value={accounts.totalRealizedPnl} currency={accounts.currency} />, detail: '所选时间区间', tone: Number(accounts.totalRealizedPnl) < 0 ? 'error' : 'success' },
    ]} />

    <Box><SectionTitle title="模拟账户" subtitle="支持 Gate TestNet 与 Bybit Demo 独立隔离配置" /><Paper variant="outlined" sx={{ overflow: 'hidden' }}>{accounts.accounts.map((account, index) => {
      const selected = selectedAccount === account.accountId;
      return <Box key={account.accountId} onClick={() => void chooseAccount(account.accountId)} sx={{ position: 'relative', cursor: 'pointer', px: 2, py: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider', bgcolor: selected ? 'rgba(29, 78, 216, 0.04)' : 'background.paper', transition: 'all 0.15s ease', '&:hover': { bgcolor: selected ? 'rgba(29, 78, 216, 0.06)' : 'action.hover' }, '&::before': selected ? { content: '""', position: 'absolute', inset: '0 auto 0 0', width: 3.5, bgcolor: 'primary.main' } : undefined }}>
        <Stack direction={{ xs: 'column', md: 'row' }} alignItems={{ md: 'center' }} spacing={1.5}>
          <Box sx={{ minWidth: 180 }}><Stack direction="row" spacing={1} alignItems="center"><Typography variant="subtitle1" fontWeight={700}>{account.displayName}</Typography><StatusBadge status={!account.enabled ? 'DISABLED' : account.dataStatus === 'READY' ? 'READY' : account.dataStatus} label={!account.enabled ? '已停用' : account.dataStatus === 'READY' ? '数据就绪' : statusLabel(account.dataStatus)} /></Stack><Typography variant="caption" color="text.secondary">{account.exchange} · {account.environment}</Typography></Box>
          <Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="h2" sx={{ fontSize: 20 }}>{formatMoney(account.equity, account.currency)}</Typography><Typography variant="caption" color="text.secondary">可用 {formatMoney(account.availableBalance, account.currency)} · 快照 {formatTime(account.snapshotAt)}</Typography></Box>
          <Stack direction="row" spacing={2.5} sx={{ minWidth: { md: 280 } }}><Box><Typography variant="caption" color="text.secondary" display="block">未实现盈亏</Typography><PnlText value={account.unrealizedPnl} currency={account.currency} /></Box><Box><Typography variant="caption" color="text.secondary" display="block">区间已实现</Typography><PnlText value={account.realizedPnl} currency={account.currency} /></Box></Stack>
          <FormControlLabel sx={{ mr: 0 }} control={<Switch size="small" checked={account.enabled} disabled={savingAccountId === account.accountId} onClick={(event) => event.stopPropagation()} onChange={(event) => { event.stopPropagation(); void setAccountEnabled(account.accountId, event.target.checked, account.version); }} />} label="启用" />
        </Stack>
      </Box>;
    })}</Paper></Box>

    {accounts.accounts.find((account) => account.accountId === selectedAccount) && <AccountCredentialEditor account={accounts.accounts.find((account) => account.accountId === selectedAccount)!} draft={credentialDraft} setDraft={setCredentialDraft} busy={loading} put={putCredential} clear={clearCredential} test={testAccount} />}

    <Box><SectionTitle title="当前持仓" subtitle="已建立的双向与单向仓位" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>标的</TableCell><TableCell>方向</TableCell><TableCell align="right">数量</TableCell><TableCell align="right">开仓 / 标记</TableCell><TableCell align="right">杠杆</TableCell><TableCell align="right">保证金</TableCell><TableCell align="right">强平价</TableCell><TableCell align="right">浮动盈亏</TableCell></TableRow></TableHead><TableBody>{positions.map((position) => <TableRow key={`${position.accountId}-${position.symbol}-${position.side}`} hover><TableCell sx={{ fontWeight: 700 }}>{position.symbol}</TableCell><TableCell><StatusBadge status={position.side} label={statusLabel(position.side)} /></TableCell><TableCell align="right">{position.quantity}</TableCell><TableCell align="right">{position.entryPrice} / {position.markPrice}</TableCell><TableCell align="right">{position.leverage}x</TableCell><TableCell align="right">{formatMoney(position.margin)}</TableCell><TableCell align="right">{position.liquidationPrice ?? '-'}</TableCell><TableCell align="right"><PnlText value={position.unrealizedPnl} /></TableCell></TableRow>)}</TableBody></Table>{positions.length === 0 && <EmptyBlock>当前账户没有未平仓持仓</EmptyBlock>}</Paper></Box>

    <Box><SectionTitle title="AI 决策、预估与模拟执行" subtitle="由反思型执行机器人生成并提交的记录" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>时间</TableCell><TableCell>标的</TableCell><TableCell>决策</TableCell><TableCell align="right">置信度</TableCell><TableCell align="right">订单 / 预估</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{automations.map((item) => <TableRow key={item.automationRunId} hover selected={execution?.summary.automationRunId === item.automationRunId} onClick={() => void chooseExecution(item.workflowRunId)} sx={{ cursor: 'pointer' }}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(item.startedAt)}</TableCell><TableCell sx={{ fontWeight: 700 }}>{item.symbol || '-'}</TableCell><TableCell><StatusBadge status={item.action} label={statusLabel(item.action)} /></TableCell><TableCell align="right" sx={{ fontWeight: 600 }}>{item.confidence === null ? '-' : `${(Number(item.confidence) * 100).toFixed(0)}%`}</TableCell><TableCell align="right">{item.orderCount} / {item.estimatedTradeCount}</TableCell><TableCell><StatusBadge status={item.status} /></TableCell></TableRow>)}</TableBody></Table>{automations.length === 0 && <EmptyBlock>尚无最终交易机器人记录</EmptyBlock>}</Paper></Box>
    {execution && <TradingExecutionDetail detail={execution} />}
  </Stack>;
}

function AccountCredentialEditor({ account, draft, setDraft, busy, put, clear, test }: { account: AccountOverview; draft: { apiKey: string; apiSecret: string }; setDraft: (value: { apiKey: string; apiSecret: string }) => void; busy: boolean; put: (account: AccountOverview, name: 'API_KEY' | 'API_SECRET', value: string, version: number) => Promise<void>; clear: (account: AccountOverview, name: 'API_KEY' | 'API_SECRET', version: number) => Promise<void>; test: (account: AccountOverview) => Promise<void> }) {
  const commandSx = { minWidth: { md: 92 }, whiteSpace: 'nowrap' } as const;
  return <Box><SectionTitle title={`${account.displayName}凭据`} subtitle="运行时热配置与凭据轮换管理" /><Paper variant="outlined" sx={{ p: 2.25 }}><Stack spacing={2}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems="flex-start"><SecretTextField fullWidth autoComplete="new-password" label={`API Key · ${secretSourceLabel(account.apiKeySource)}`} value={draft.apiKey} onChange={(event) => setDraft({ ...draft, apiKey: event.target.value })} helperText={account.apiKeyFingerprint ? `当前指纹 ${account.apiKeyFingerprint}` : '当前无凭据'} /><Button variant="outlined" sx={commandSx} disabled={busy || draft.apiKey.trim().length < 8} onClick={() => void put(account, 'API_KEY', draft.apiKey, account.apiKeyVersion)}>设置 Key</Button><Button variant="outlined" sx={commandSx} color="error" disabled={busy || account.apiKeySource !== 'DATABASE_OVERRIDE'} onClick={() => void clear(account, 'API_KEY', account.apiKeyVersion)}>清除</Button></Stack><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems="flex-start"><SecretTextField fullWidth autoComplete="new-password" label={`API Secret · ${secretSourceLabel(account.apiSecretSource)}`} value={draft.apiSecret} onChange={(event) => setDraft({ ...draft, apiSecret: event.target.value })} helperText={account.apiSecretFingerprint ? `当前指纹 ${account.apiSecretFingerprint}` : '当前无凭据'} /><Button variant="outlined" sx={commandSx} disabled={busy || draft.apiSecret.trim().length < 8} onClick={() => void put(account, 'API_SECRET', draft.apiSecret, account.apiSecretVersion)}>设置 Secret</Button><Button variant="outlined" sx={commandSx} color="error" disabled={busy || account.apiSecretSource !== 'DATABASE_OVERRIDE'} onClick={() => void clear(account, 'API_SECRET', account.apiSecretVersion)}>清除</Button></Stack><Button variant="contained" disabled={busy || !account.credentialConfigured} onClick={() => void test(account)} sx={{ alignSelf: 'flex-end', whiteSpace: 'nowrap', px: 2.5 }}>在线同步测试</Button></Stack></Paper></Box>;
}

function secretSourceLabel(source: AccountOverview['apiKeySource']): string { return ({ DATABASE_OVERRIDE: '后台热配置', ENVIRONMENT_FALLBACK: '启动备用配置', UNCONFIGURED: '未配置' } as const)[source]; }

function localDate(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

function dayBoundary(value: string, end: boolean): string {
  const date = new Date(`${value}T00:00:00`);
  if (end) date.setDate(date.getDate() + 1);
  return date.toISOString();
}
