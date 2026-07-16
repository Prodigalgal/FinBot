import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, FormControl, FormControlLabel, InputLabel, MenuItem, Paper, Select, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import type { AccountOverview, AccountsOverview, PositionRecord, TradeAutomationDetail, TradeAutomationSummary } from './types';
import { EmptyBlock, ErrorBlock, SectionTitle, formatMoney, formatTime, statusColor, statusLabel } from './ui';
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

  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}>
      <FormControl size="small" sx={{ minWidth: 150 }}><InputLabel>盈亏区间</InputLabel><Select label="盈亏区间" value={range} onChange={(event) => setRange(event.target.value as RangePreset)}><MenuItem value="HOURS_24">24 小时</MenuItem><MenuItem value="DAYS_7">7 天</MenuItem><MenuItem value="DAYS_30">30 天</MenuItem><MenuItem value="ALL">全部历史</MenuItem><MenuItem value="CUSTOM">自定义</MenuItem></Select></FormControl>
      {range === 'CUSTOM' && <><TextField size="small" label="开始日期" type="date" value={customFrom} onChange={(event) => setCustomFrom(event.target.value)} InputLabelProps={{ shrink: true }} /><TextField size="small" label="结束日期" type="date" value={customTo} onChange={(event) => setCustomTo(event.target.value)} InputLabelProps={{ shrink: true }} /></>}
      <Button startIcon={<RefreshIcon />} onClick={() => void refresh()} disabled={loading}>应用区间</Button>
      <Typography variant="caption" color="text.secondary" sx={{ ml: { lg: 'auto' } }}>统计区间 {formatTime(accounts.range.fromInclusive)} 至 {formatTime(accounts.range.toExclusive)} · 生成于 {formatTime(accounts.generatedAt)}</Typography>
    </Stack>

    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>{[
      ['总权益', formatMoney(accounts.totalEquity, accounts.currency)],
      ['可用余额', formatMoney(accounts.totalAvailableBalance, accounts.currency)],
      ['未实现盈亏', formatMoney(accounts.totalUnrealizedPnl, accounts.currency)],
      ['区间已实现盈亏', formatMoney(accounts.totalRealizedPnl, accounts.currency)],
    ].map(([label, value]) => <Paper key={label} variant="outlined" sx={{ p: 2, flex: 1 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="h2" color={label.includes('盈亏') && numericMoney(value) < 0 ? 'error.main' : 'text.primary'}>{value}</Typography></Paper>)}</Stack>

    <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5}>{accounts.accounts.map((account) => <Paper key={account.accountId} variant="outlined" onClick={() => void chooseAccount(account.accountId)} sx={{ p: 2, flex: 1, cursor: 'pointer', borderColor: selectedAccount === account.accountId ? 'primary.main' : 'divider' }}><Stack direction="row" justifyContent="space-between" spacing={1}><Box><Typography fontWeight={700}>{account.displayName}</Typography><Typography variant="caption" color="text.secondary">{account.exchange} · {account.environment}</Typography></Box><Stack alignItems="flex-end"><Chip size="small" color={account.enabled && account.dataStatus === 'READY' ? 'success' : 'warning'} label={!account.enabled ? '已停用' : account.dataStatus === 'READY' ? '数据正常' : statusLabel(account.dataStatus)} /><FormControlLabel sx={{ mr: 0, mt: 0.5 }} control={<Switch size="small" checked={account.enabled} disabled={savingAccountId === account.accountId} onClick={(event) => event.stopPropagation()} onChange={(event) => { event.stopPropagation(); void setAccountEnabled(account.accountId, event.target.checked, account.version); }} />} label="启用" /></Stack></Stack><Typography variant="h2" sx={{ mt: 1 }}>{formatMoney(account.equity, account.currency)}</Typography><Typography variant="body2" color="text.secondary">未实现 {formatMoney(account.unrealizedPnl, account.currency)} · 区间已实现 {formatMoney(account.realizedPnl, account.currency)}</Typography><Typography variant="caption" color="text.secondary">可用 {formatMoney(account.availableBalance, account.currency)} · 快照 {formatTime(account.snapshotAt)}</Typography></Paper>)}</Stack>

    {accounts.accounts.find((account) => account.accountId === selectedAccount) && <AccountCredentialEditor account={accounts.accounts.find((account) => account.accountId === selectedAccount)!} draft={credentialDraft} setDraft={setCredentialDraft} busy={loading} put={putCredential} clear={clearCredential} test={testAccount} />}

    <Box><SectionTitle title="当前持仓" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>标的</TableCell><TableCell>方向</TableCell><TableCell align="right">数量</TableCell><TableCell align="right">开仓 / 标记</TableCell><TableCell align="right">杠杆</TableCell><TableCell align="right">保证金</TableCell><TableCell align="right">强平价</TableCell><TableCell align="right">浮动盈亏</TableCell></TableRow></TableHead><TableBody>{positions.map((position) => <TableRow key={`${position.accountId}-${position.symbol}-${position.side}`}><TableCell>{position.symbol}</TableCell><TableCell>{statusLabel(position.side)}</TableCell><TableCell align="right">{position.quantity}</TableCell><TableCell align="right">{position.entryPrice} / {position.markPrice}</TableCell><TableCell align="right">{position.leverage}x</TableCell><TableCell align="right">{formatMoney(position.margin)}</TableCell><TableCell align="right">{position.liquidationPrice ?? '-'}</TableCell><TableCell align="right" sx={{ color: Number(position.unrealizedPnl) < 0 ? 'error.main' : 'success.main' }}>{formatMoney(position.unrealizedPnl)}</TableCell></TableRow>)}</TableBody></Table>{positions.length === 0 && <EmptyBlock>当前账户没有未平仓持仓</EmptyBlock>}</Paper></Box>

    <Box><SectionTitle title="AI 决策、预估与模拟执行" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>时间</TableCell><TableCell>标的</TableCell><TableCell>决策</TableCell><TableCell align="right">置信度</TableCell><TableCell align="right">订单 / 预估</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{automations.map((item) => <TableRow key={item.automationRunId} hover selected={execution?.summary.automationRunId === item.automationRunId} onClick={() => void chooseExecution(item.workflowRunId)} sx={{ cursor: 'pointer' }}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(item.startedAt)}</TableCell><TableCell>{item.symbol || '-'}</TableCell><TableCell>{statusLabel(item.action)}</TableCell><TableCell align="right">{item.confidence === null ? '-' : `${(Number(item.confidence) * 100).toFixed(0)}%`}</TableCell><TableCell align="right">{item.orderCount} / {item.estimatedTradeCount}</TableCell><TableCell><Chip size="small" color={statusColor(item.status)} label={statusLabel(item.status)} /></TableCell></TableRow>)}</TableBody></Table>{automations.length === 0 && <EmptyBlock>尚无最终交易机器人记录</EmptyBlock>}</Paper></Box>
    {execution && <TradingExecutionDetail detail={execution} />}
  </Stack>;
}

function AccountCredentialEditor({ account, draft, setDraft, busy, put, clear, test }: { account: AccountOverview; draft: { apiKey: string; apiSecret: string }; setDraft: (value: { apiKey: string; apiSecret: string }) => void; busy: boolean; put: (account: AccountOverview, name: 'API_KEY' | 'API_SECRET', value: string, version: number) => Promise<void>; clear: (account: AccountOverview, name: 'API_KEY' | 'API_SECRET', version: number) => Promise<void>; test: (account: AccountOverview) => Promise<void> }) {
  return <Box><SectionTitle title={`${account.displayName}凭据`} /><Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField fullWidth type="password" autoComplete="new-password" label={`API Key · ${secretSourceLabel(account.apiKeySource)}`} value={draft.apiKey} onChange={(event) => setDraft({ ...draft, apiKey: event.target.value })} helperText={account.apiKeyFingerprint ? `当前指纹 ${account.apiKeyFingerprint}` : '当前无凭据'} /><Button disabled={busy || draft.apiKey.trim().length < 8} onClick={() => void put(account, 'API_KEY', draft.apiKey, account.apiKeyVersion)}>设置 Key</Button><Button color="error" disabled={busy || account.apiKeySource !== 'DATABASE_OVERRIDE'} onClick={() => void clear(account, 'API_KEY', account.apiKeyVersion)}>清除</Button></Stack><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField fullWidth type="password" autoComplete="new-password" label={`API Secret · ${secretSourceLabel(account.apiSecretSource)}`} value={draft.apiSecret} onChange={(event) => setDraft({ ...draft, apiSecret: event.target.value })} helperText={account.apiSecretFingerprint ? `当前指纹 ${account.apiSecretFingerprint}` : '当前无凭据'} /><Button disabled={busy || draft.apiSecret.trim().length < 8} onClick={() => void put(account, 'API_SECRET', draft.apiSecret, account.apiSecretVersion)}>设置 Secret</Button><Button color="error" disabled={busy || account.apiSecretSource !== 'DATABASE_OVERRIDE'} onClick={() => void clear(account, 'API_SECRET', account.apiSecretVersion)}>清除</Button></Stack><Button variant="contained" disabled={busy || !account.credentialConfigured} onClick={() => void test(account)} sx={{ alignSelf: 'flex-end' }}>在线同步测试</Button></Stack></Paper></Box>;
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

function numericMoney(value: string): number { return Number(value.split(' ')[0].replace(/,/g, '')); }
