import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, Dialog, DialogContent, DialogTitle, FormControlLabel, Paper, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { LocalPaperAccount, LocalPaperTradeDetail, LocalPaperTradePage, PaperTrade } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime } from './ui';

const statusLabels: Record<PaperTrade['status'], string> = { PENDING_ENTRY: '等待入场', OPEN: '持仓中', CLOSED: '已平仓', CANCELLED: '已取消', EXPIRED: '已到期' };
const healthLabels: Record<PaperTrade['healthCode'], string> = { READY: '行情可用', QUOTE_UNAVAILABLE: '报价过期或时序无效', FUNDING_DATA_PENDING: '等待资金费数据', CANDLE_DATA_GAP: '行情回补有缺口', MARKET_UNAVAILABLE: '行情不可用', RISK_BLOCKED: '风险约束阻断' };
const amount = (value: number | null) => value === null ? '—' : value.toLocaleString('zh-CN', { maximumFractionDigits: 6 });

export function LocalPaperPanel() {
  const [account, setAccount] = useState<LocalPaperAccount | null>(null);
  const [active, setActive] = useState<PaperTrade[]>([]);
  const [history, setHistory] = useState<LocalPaperTradePage | null>(null);
  const [detail, setDetail] = useState<LocalPaperTradeDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const fetching = useRef(false);
  const mounted = useRef(true);
  const loadedPages = useRef(1);

  const refresh = useCallback(async () => {
    if (fetching.current) return;
    fetching.current = true;
    setRefreshing(true);
    try {
      const [nextAccount, nextActive, nextHistory] = await Promise.all([api.localPaperAccount(), api.activeLocalPaperTrades(), api.localPaperTrades()]);
      const refreshedTrades = new Map(nextHistory.trades.map(trade => [trade.tradeId, trade]));
      let nextCursor = nextHistory.nextBeforeTradeId;
      for (let page = 1; page < loadedPages.current && nextCursor; page++) {
        const earlierPage = await api.localPaperTrades(nextCursor);
        for (const trade of earlierPage.trades) refreshedTrades.set(trade.tradeId, trade);
        nextCursor = earlierPage.nextBeforeTradeId;
      }
      if (mounted.current) {
        setAccount(nextAccount); setActive(nextActive);
        setHistory({ trades: [...refreshedTrades.values()], nextBeforeTradeId: nextCursor });
        setError(null);
      }
    } catch (failure) { if (mounted.current) setError(failure); }
    finally { fetching.current = false; if (mounted.current) setRefreshing(false); }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void refresh();
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void refresh(); }, 10000);
    return () => { mounted.current = false; window.clearInterval(timer); };
  }, [refresh]);

  const mutate = async (operation: () => Promise<unknown>) => {
    setBusy(true); setActionError(null);
    try { await operation(); if (mounted.current) setDetail(null); }
    catch (failure) { if (mounted.current) setActionError(failure); }
    finally { if (mounted.current) { setBusy(false); await refresh(); } }
  };
  const inspect = async (trade: PaperTrade) => {
    setActionError(null);
    try { const next = await api.localPaperTrade(trade.tradeId); if (mounted.current) setDetail(next); }
    catch (failure) { if (mounted.current) setActionError(failure); }
  };
  const more = async () => {
    if (!history?.nextBeforeTradeId || fetching.current) return;
    fetching.current = true; setRefreshing(true);
    try {
      const next = await api.localPaperTrades(history.nextBeforeTradeId);
      if (mounted.current) {
        loadedPages.current += 1;
        setHistory(previous => {
          const merged = new Map((previous?.trades || []).map(trade => [trade.tradeId, trade]));
          for (const trade of next.trades) merged.set(trade.tradeId, trade);
          return { trades: [...merged.values()], nextBeforeTradeId: next.nextBeforeTradeId };
        });
      }
    } catch (failure) { if (mounted.current) setError(failure); }
    finally { fetching.current = false; if (mounted.current) setRefreshing(false); }
  };

  if (!account) return <Stack>{error !== null ? <><ErrorBlock error={error} /><Button onClick={() => void refresh()}>重试</Button></> : <LoadingBlock label="读取本地模拟账户" />}</Stack>;
  return <Stack spacing={2}>
    {error !== null && <ErrorBlock error={error} />}
    {actionError !== null && <ErrorBlock error={actionError} />}
    <Alert severity="info">使用公开 LIVE 行情和独立虚拟 USDT 账户。挂单由指定商品的研究、执行审核和风控生成；行情不完整时暂停撮合。成交采用估算模型。</Alert>
    <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1}>
      <Box><Typography fontWeight={700}>本地模拟 · LOCAL_PAPER</Typography><Typography variant="caption" color="text.secondary">最近检查 {formatTime(account.lastCheckedAt)}</Typography></Box>
      <Stack direction="row" spacing={1}><FormControlLabel label={account.ordersEnabled ? '接收新挂单' : '暂停新挂单'} control={<Switch checked={account.ordersEnabled} disabled={busy} onChange={(event) => void mutate(() => api.updateLocalPaperAccount(event.target.checked, account.version))} />} /><Button startIcon={<RefreshIcon />} disabled={refreshing || busy} onClick={() => void refresh()}>刷新</Button></Stack>
    </Stack>
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0,1fr))', lg: 'repeat(4, minmax(0,1fr))' }, gap: 1.25 }}>
      {[['权益', account.equity], ['可用资金', account.availableBalance], ['保证金与费用预留', account.reservedMargin], ['余额', account.cashBalance], ['未实现盈亏', account.unrealizedPnl], ['已实现净盈亏', account.realizedPnl], ['手续费', account.feesUsdt], ['资金费（负值为收入）', account.fundingUsdt]].map(([label, value]) => <Paper key={label} variant="outlined" sx={{ p: 1.5 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography fontWeight={700} sx={{ overflowWrap: 'anywhere' }}>{amount(Number(value))} <Typography component="span" variant="caption">USDT</Typography></Typography></Paper>)}
    </Box>
    <SectionTitle title={`挂单与持仓（${account.pendingCount} / ${account.openCount}）`} />
    <TradeRows trades={active} busy={busy} inspect={inspect} cancel={trade => mutate(() => api.cancelLocalPaperTrade(trade.tradeId, trade.version))} close={trade => mutate(() => api.closeLocalPaperTrade(trade.tradeId, trade.version))} />
    <SectionTitle title="模拟历史" />
    <TradeRows trades={(history?.trades || []).filter(trade => !['PENDING_ENTRY', 'OPEN'].includes(trade.status))} busy={busy} inspect={inspect} />
    {history?.nextBeforeTradeId && <Button disabled={refreshing} onClick={() => void more()}>加载更早记录</Button>}
    <Dialog open={detail !== null} onClose={() => setDetail(null)} fullWidth maxWidth="md"><DialogTitle>模拟事件 · {detail?.trade.symbol}</DialogTitle><DialogContent><Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>{detail?.trade.tradeId} · 风险版本 {detail?.trade.terms.policyVersion}</Typography>{detail?.events.map(event => <Paper key={event.eventId} variant="outlined" sx={{ p: 1.5, mt: 1 }}><Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between"><Typography fontWeight={700}>{event.type}</Typography><Typography>{formatTime(event.occurredAt)}</Typography></Stack><Typography variant="body2">成交价 {amount(event.price)} · 现金变化 {amount(event.cashDeltaUsdt)} USDT · 手续费 {amount(event.feeUsdt)}</Typography><Typography variant="caption" color="text.secondary" sx={{ display: 'block', overflowWrap: 'anywhere' }}>{event.model}<br />{event.sourceEndpoint}</Typography></Paper>)}</DialogContent></Dialog>
  </Stack>;
}

function TradeRows({ trades, busy, inspect, cancel, close }: { trades: PaperTrade[]; busy: boolean; inspect: (trade: PaperTrade) => Promise<void>; cancel?: (trade: PaperTrade) => Promise<void>; close?: (trade: PaperTrade) => Promise<void> }) {
  if (!trades.length) return <Paper variant="outlined"><EmptyBlock>暂无模拟交易记录</EmptyBlock></Paper>;
  return <Paper variant="outlined" sx={{ overflowX: 'auto' }}><Table size="small" sx={{ minWidth: 720 }}><TableHead><TableRow>{['商品 / 状态', '方向 / 数量', '挂单 / 成交价', '止盈 / 止损', '标记价 / 行情时间', '已实现净盈亏', '操作'].map(label => <TableCell key={label}>{label}</TableCell>)}</TableRow></TableHead><TableBody>{trades.map(trade => <TableRow key={trade.tradeId}>
    <TableCell><Typography fontWeight={700}>{trade.symbol}</Typography><Chip size="small" label={statusLabels[trade.status]} color={trade.status === 'OPEN' ? 'success' : 'default'} /><Typography variant="caption" display="block" color={trade.healthCode === 'READY' ? 'text.secondary' : 'error.main'}>{healthLabels[trade.healthCode]}</Typography></TableCell>
    <TableCell>{trade.terms.side === 'BUY' ? '做多' : '做空'} · {amount(trade.terms.quantity)}<Typography variant="caption" display="block">{amount(trade.terms.leverage)}× · 合约单位 {amount(trade.terms.contractSize)}</Typography></TableCell>
    <TableCell>{amount(trade.terms.limitPrice)} / {amount(trade.entryPrice)}</TableCell><TableCell>{amount(trade.terms.targetPrice)} / {amount(trade.terms.stopPrice)}</TableCell>
    <TableCell>{amount(trade.markPrice)}<Typography variant="caption" display="block">{formatTime(trade.quoteAt)}</Typography></TableCell><TableCell>{amount(trade.realizedPnlUsdt)} USDT</TableCell>
    <TableCell><Stack direction="row"><Button size="small" onClick={() => void inspect(trade)}>详情</Button>{cancel && trade.status === 'PENDING_ENTRY' && <Button size="small" disabled={busy} onClick={() => void cancel(trade)}>取消挂单</Button>}{close && trade.status === 'OPEN' && <Button size="small" disabled={busy || trade.healthCode !== 'READY'} onClick={() => void close(trade)}>模拟平仓</Button>}</Stack></TableCell>
  </TableRow>)}</TableBody></Table></Paper>;
}
