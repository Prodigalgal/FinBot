import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useCallback, useEffect, useRef, useState } from 'react';

import { api } from './api';
import type {
  AccountPnlRange,
  ExchangeAccountSnapshot,
  ExchangeAccountsPayload,
  ExchangePositionSnapshot,
} from './types';
import { formatTime, statusColor, statusText } from './utils';

const RANGE_OPTIONS: Array<{ value: AccountPnlRange; label: string }> = [
  { value: 'all', label: '全部历史' },
  { value: '24h', label: '24 小时' },
  { value: '7d', label: '7 天' },
  { value: '30d', label: '30 天' },
  { value: 'custom', label: '自定义' },
];

export function ExchangeAccountsPanel() {
  const [pnlRange, setPnlRange] = useState<AccountPnlRange>('all');
  const [customStart, setCustomStart] = useState(() => localDateOffset(-7));
  const [customEnd, setCustomEnd] = useState(() => localDateOffset(0));
  const [payload, setPayload] = useState<ExchangeAccountsPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSequence = useRef(0);

  const loadAccounts = useCallback(async (
    range: AccountPnlRange,
    startDate?: string,
    endDate?: string,
  ) => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(null);
    try {
      const params: { pnl_range: AccountPnlRange; start_at?: string; end_at?: string } = { pnl_range: range };
      if (range === 'custom') {
        if (!startDate || !endDate) {
          throw new Error('请选择完整的起止日期');
        }
        if (startDate > endDate) {
          throw new Error('开始日期不能晚于结束日期');
        }
        params.start_at = dateBoundaryIso(startDate, false);
        params.end_at = dateBoundaryIso(endDate, true);
      }
      const result = await api.exchangeAccounts(params);
      if (sequence === requestSequence.current) {
        setPayload(result);
      }
    } catch (loadError) {
      if (sequence === requestSequence.current) {
        setError(loadError instanceof Error ? loadError.message : String(loadError));
      }
    } finally {
      if (sequence === requestSequence.current) {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadAccounts('all');
  }, [loadAccounts]);

  const selectRange = (range: AccountPnlRange | null) => {
    if (!range) {
      return;
    }
    setPnlRange(range);
    if (range !== 'custom') {
      void loadAccounts(range);
    }
  };

  const refresh = () => {
    void loadAccounts(pnlRange, customStart, customEnd);
  };

  return (
    <Stack spacing={2} data-testid="exchange-accounts-panel">
      <Card>
        <CardContent>
          <Stack
            direction={{ xs: 'column', lg: 'row' }}
            spacing={1.5}
            alignItems={{ xs: 'stretch', lg: 'center' }}
            justifyContent="space-between"
          >
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="subtitle1">盈亏区间</Typography>
              <Typography variant="caption" color="text.secondary">
                当前未实现盈亏始终按最新持仓计算
              </Typography>
            </Box>
            <ToggleButtonGroup
              exclusive
              size="small"
              value={pnlRange}
              onChange={(_, value: AccountPnlRange | null) => selectRange(value)}
              sx={{ alignSelf: { xs: 'stretch', lg: 'center' }, '& .MuiToggleButton-root': { flex: { xs: 1, lg: 'initial' }, px: { xs: 0.75, sm: 1.5 } } }}
            >
              {RANGE_OPTIONS.map((option) => (
                <ToggleButton key={option.value} value={option.value}>{option.label}</ToggleButton>
              ))}
            </ToggleButtonGroup>
            <Tooltip title="刷新账户快照">
              <Button
                variant="outlined"
                startIcon={loading ? <CircularProgress size={16} /> : <RefreshIcon />}
                disabled={loading}
                onClick={refresh}
              >
                刷新
              </Button>
            </Tooltip>
          </Stack>

          {pnlRange === 'custom' && (
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              alignItems={{ xs: 'stretch', sm: 'center' }}
              sx={{ mt: 2, pt: 2, borderTop: '1px solid', borderColor: 'divider' }}
            >
              <TextField
                type="date"
                label="开始日期"
                value={customStart}
                onChange={(event) => setCustomStart(event.target.value)}
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: localDateOffset(0) } }}
              />
              <TextField
                type="date"
                label="结束日期"
                value={customEnd}
                onChange={(event) => setCustomEnd(event.target.value)}
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: customStart, max: localDateOffset(0) } }}
              />
              <Button
                variant="contained"
                startIcon={<CalendarMonthIcon />}
                disabled={loading || !customStart || !customEnd}
                onClick={refresh}
              >
                查询区间
              </Button>
            </Stack>
          )}
        </CardContent>
      </Card>

      {error && <Alert severity="error" action={<Button color="inherit" size="small" onClick={refresh}>重试</Button>}>{error}</Alert>}
      {!payload && loading && <LoadingState />}
      {payload && (
        <>
          {payload.status === 'partial' && (
            <Alert severity="warning">部分交易所账户读取失败，汇总值仅包含当前可用账户。</Alert>
          )}
          {payload.status === 'blocked' && (
            <Alert severity="error">当前没有可读取的模拟交易账户，请检查交易所启用状态、凭据与代理路由。</Alert>
          )}
          <AccountSummary payload={payload} loading={loading} />
          <Stack spacing={2}>
            {payload.accounts.map((account) => (
              <ExchangeAccountSection key={account.adapter_id} account={account} pnlRange={payload.pnl_window.mode} />
            ))}
          </Stack>
        </>
      )}
    </Stack>
  );
}

function AccountSummary({ payload, loading }: { payload: ExchangeAccountsPayload; loading: boolean }) {
  const totals = payload.totals;
  const allHistory = payload.pnl_window.mode === 'all';
  return (
    <Card>
      <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1}
          justifyContent="space-between"
          alignItems={{ xs: 'flex-start', sm: 'center' }}
          sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}
        >
          <Stack direction="row" spacing={1} alignItems="center">
            <AccountBalanceWalletIcon color="primary" fontSize="small" />
            <Box>
              <Typography variant="subtitle1">账户汇总</Typography>
              <Typography variant="caption" color="text.secondary">
                {pnlWindowText(payload)} · 更新于 {formatTime(payload.generated_at)}
              </Typography>
            </Box>
          </Stack>
          <Stack direction="row" spacing={1} alignItems="center">
            {loading && <CircularProgress size={16} />}
            <Chip size="small" color={statusColor(payload.status)} label={statusText(payload.status)} />
            <Chip size="small" variant="outlined" label="模拟账户 · 只读" />
          </Stack>
        </Stack>
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(5, minmax(0, 1fr))' } }}>
          <SummaryMetric label="账户总权益" value={money(totals.total_equity_usdt)} />
          <SummaryMetric label="可用余额" value={money(totals.available_balance_usdt)} />
          <SummaryMetric
            label={allHistory ? '累计已实现盈亏' : '区间已实现盈亏'}
            value={money(totals.realized_pnl_usdt, true)}
            pnl={totals.realized_pnl_usdt}
          />
          <SummaryMetric
            label="当前未实现盈亏"
            value={money(totals.unrealized_pnl_usdt, true)}
            pnl={totals.unrealized_pnl_usdt}
          />
          <SummaryMetric
            label={allHistory ? '当前总盈亏' : '保证金占用'}
            value={allHistory ? money(totals.total_pnl_usdt, true) : money(totals.margin_used_usdt)}
            pnl={allHistory ? totals.total_pnl_usdt : undefined}
            detail={allHistory ? '累计已实现 + 当前未实现' : undefined}
            mobileFullWidth
          />
        </Box>
      </CardContent>
    </Card>
  );
}

function ExchangeAccountSection({ account, pnlRange }: { account: ExchangeAccountSnapshot; pnlRange: AccountPnlRange }) {
  const available = account.status === 'ready' || account.status === 'partial';
  return (
    <Card>
      <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={1}
          justifyContent="space-between"
          alignItems={{ xs: 'flex-start', sm: 'center' }}
          sx={{ px: 2, py: 1.5 }}
        >
          <Box>
            <Typography variant="h3">{account.display_name}</Typography>
            <Typography variant="caption" color="text.secondary">
              {account.currency} · {account.position_count} 个持仓 · {formatTime(account.fetched_at)}
            </Typography>
          </Box>
          <Chip size="small" color={statusColor(account.status)} label={statusText(account.status)} />
        </Stack>

        {!available && (
          <Box sx={{ px: 2, pb: 2 }}>
            <Alert severity={account.status === 'disabled' ? 'info' : 'error'}>{account.error || '账户暂不可用'}</Alert>
          </Box>
        )}

        {available && (
          <>
            {(account.warnings || []).map((warning) => (
              <Alert key={warning} severity="warning" sx={{ mx: 2, mb: 1 }}>{warning}</Alert>
            ))}
            <Divider />
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(3, minmax(0, 1fr))', xl: 'repeat(6, minmax(0, 1fr))' } }}>
              <AccountMetric label="总权益" value={money(account.total_equity_usdt)} />
              <AccountMetric label="钱包余额" value={money(account.wallet_balance_usdt)} />
              <AccountMetric label="可用余额" value={money(account.available_balance_usdt)} />
              <AccountMetric label={pnlRange === 'all' ? '累计已实现' : '区间已实现'} value={money(account.realized_pnl_usdt, true)} pnl={account.realized_pnl_usdt} />
              <AccountMetric label="当前未实现" value={money(account.unrealized_pnl_usdt, true)} pnl={account.unrealized_pnl_usdt} />
              <AccountMetric label="保证金占用" value={money(account.margin_used_usdt)} />
            </Box>
            <Divider />
            <Box sx={{ px: 2, py: 1.5 }}>
              <Typography variant="subtitle1" sx={{ mb: 1 }}>未平仓持仓</Typography>
              {account.positions.length ? <PositionsView positions={account.positions} /> : <EmptyPositions />}
            </Box>
          </>
        )}
      </CardContent>
    </Card>
  );
}

function PositionsView({ positions }: { positions: ExchangePositionSnapshot[] }) {
  return (
    <>
      <TableContainer sx={{ display: { xs: 'none', md: 'block' }, border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
        <Table size="small" aria-label="交易所持仓">
          <TableHead>
            <TableRow>
              <TableCell>合约</TableCell>
              <TableCell>方向</TableCell>
              <TableCell align="right">数量</TableCell>
              <TableCell align="right">杠杆</TableCell>
              <TableCell align="right">开仓价</TableCell>
              <TableCell align="right">标记价</TableCell>
              <TableCell align="right">强平价</TableCell>
              <TableCell align="right">仓位价值</TableCell>
              <TableCell align="right">未实现盈亏</TableCell>
              <TableCell align="right">ROE</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {positions.map((position) => (
              <TableRow key={`${position.symbol}-${position.side}`} hover>
                <TableCell sx={{ fontWeight: 700 }}>{position.symbol}</TableCell>
                <TableCell><DirectionChip side={position.side} /></TableCell>
                <TableCell align="right">{quantity(position.size)}</TableCell>
                <TableCell align="right">{leverageText(position.leverage)}</TableCell>
                <TableCell align="right">{price(position.entry_price)}</TableCell>
                <TableCell align="right">{price(position.mark_price)}</TableCell>
                <TableCell align="right">{price(position.liquidation_price)}</TableCell>
                <TableCell align="right">{money(position.position_value_usdt)}</TableCell>
                <TableCell align="right" sx={{ color: pnlColor(position.unrealized_pnl_usdt), fontWeight: 700 }}>
                  {money(position.unrealized_pnl_usdt, true)}
                </TableCell>
                <TableCell align="right" sx={{ color: pnlColor(position.roe_pct), fontWeight: 700 }}>
                  {percent(position.roe_pct)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <Stack sx={{ display: { xs: 'flex', md: 'none' } }} divider={<Divider flexItem />}>
        {positions.map((position) => (
          <Box key={`${position.symbol}-${position.side}`} sx={{ py: 1.25 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
              <Typography variant="subtitle1">{position.symbol}</Typography>
              <DirectionChip side={position.side} />
            </Stack>
            <Box sx={{ mt: 1, display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
              <MobileFact label="数量 / 杠杆" value={`${quantity(position.size)} / ${leverageText(position.leverage)}`} />
              <MobileFact label="仓位价值" value={money(position.position_value_usdt)} />
              <MobileFact label="开仓价" value={price(position.entry_price)} />
              <MobileFact label="标记价" value={price(position.mark_price)} />
              <MobileFact label="未实现盈亏" value={money(position.unrealized_pnl_usdt, true)} pnl={position.unrealized_pnl_usdt} />
              <MobileFact label="ROE" value={percent(position.roe_pct)} pnl={position.roe_pct} />
            </Box>
          </Box>
        ))}
      </Stack>
    </>
  );
}

function SummaryMetric({
  label,
  value,
  pnl,
  detail,
  mobileFullWidth = false,
}: {
  label: string;
  value: string;
  pnl?: number | null;
  detail?: string;
  mobileFullWidth?: boolean;
}) {
  return (
    <Box sx={{ gridColumn: { xs: mobileFullWidth ? '1 / -1' : 'auto', lg: 'auto' }, px: { xs: 1.25, sm: 2 }, py: 1.75, minWidth: 0, minHeight: 86, borderRight: '1px solid', borderBottom: '1px solid', borderColor: 'divider' }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h3" sx={{ mt: 0.5, color: pnlColor(pnl), overflowWrap: 'anywhere' }}>{value}</Typography>
      {detail && <Typography variant="caption" color="text.secondary">{detail}</Typography>}
    </Box>
  );
}

function AccountMetric({ label, value, pnl }: { label: string; value: string; pnl?: number | null }) {
  return (
    <Box sx={{ px: 2, py: 1.5, minWidth: 0, borderRight: '1px solid', borderBottom: '1px solid', borderColor: 'divider' }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="subtitle1" sx={{ color: pnlColor(pnl), overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  );
}

function MobileFact({ label, value, pnl }: { label: string; value: string; pnl?: number | null }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2" sx={{ color: pnlColor(pnl), fontWeight: 700, overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  );
}

function DirectionChip({ side }: { side: ExchangePositionSnapshot['side'] }) {
  return (
    <Chip
      size="small"
      variant="outlined"
      color={side === 'long' ? 'success' : side === 'short' ? 'error' : 'default'}
      label={side === 'long' ? '多' : side === 'short' ? '空' : '未知'}
    />
  );
}

function EmptyPositions() {
  return (
    <Box sx={{ py: 3, textAlign: 'center', borderTop: '1px solid', borderColor: 'divider' }}>
      <Typography variant="body2" color="text.secondary">暂无未平仓持仓</Typography>
    </Box>
  );
}

function LoadingState() {
  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <CircularProgress size={20} />
          <Typography variant="body2" color="text.secondary">正在读取模拟交易账户</Typography>
        </Stack>
      </CardContent>
    </Card>
  );
}

function pnlWindowText(payload: ExchangeAccountsPayload): string {
  const mode = payload.pnl_window.mode;
  if (mode === 'all') {
    return '全部历史';
  }
  if (mode !== 'custom') {
    return RANGE_OPTIONS.find((option) => option.value === mode)?.label || mode;
  }
  return `${dateText(payload.pnl_window.start_at)} 至 ${dateText(payload.pnl_window.end_at)}`;
}

function money(value?: number | null, signed = false): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  const prefix = signed && value > 0 ? '+' : '';
  return `${prefix}${new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value)} USDT`;
}

function price(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return '-';
  }
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 }).format(value);
}

function quantity(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 }).format(value);
}

function leverageText(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  return value === 0 ? '全仓' : `${value}x`;
}

function percent(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`;
}

function pnlColor(value?: number | null): string | undefined {
  if (typeof value !== 'number' || value === 0) {
    return undefined;
  }
  return value > 0 ? 'success.main' : 'error.main';
}

function localDateOffset(days: number): string {
  const date = new Date();
  date.setHours(12, 0, 0, 0);
  date.setDate(date.getDate() + days);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function dateBoundaryIso(dateTextValue: string, endOfDay: boolean): string {
  const date = new Date(`${dateTextValue}T00:00:00`);
  if (endOfDay) {
    date.setDate(date.getDate() + 1);
    date.setMilliseconds(date.getMilliseconds() - 1);
    const now = new Date();
    if (date > now) {
      return now.toISOString();
    }
  }
  return date.toISOString();
}

function dateText(value?: string | null): string {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN');
}
