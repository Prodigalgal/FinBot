import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  FormControl,
  IconButton,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import RefreshIcon from '@mui/icons-material/Refresh';
import { useCallback, useEffect, useRef, useState } from 'react';

import { api } from './api';
import { ExchangeAccountActivityList } from './ExchangeAccountActivityList';
import type {
  AccountActivityAdapter,
  AccountActivityStage,
  AccountPnlRange,
  ExchangeAccountActivityPayload,
  ExchangeAccountActivitySource,
} from './types';
import { formatTime, statusColor, statusText } from './utils';

interface ActivityFilters {
  range: AccountPnlRange;
  adapter: AccountActivityAdapter;
  stage: AccountActivityStage;
  status: string;
  symbol: string;
  customStart: string;
  customEnd: string;
}

const INITIAL_ACTIVITY_FILTERS: ActivityFilters = {
  range: '7d',
  adapter: 'all',
  stage: 'all',
  status: 'all',
  symbol: '',
  customStart: localDateOffset(-7),
  customEnd: localDateOffset(0),
};

const ACTIVITY_RANGE_OPTIONS: Array<{ value: AccountPnlRange; label: string }> = [
  { value: '24h', label: '24 小时' },
  { value: '7d', label: '7 天' },
  { value: '30d', label: '30 天' },
  { value: 'all', label: '全部可用' },
  { value: 'custom', label: '自定义' },
];

const ACTIVITY_STAGE_OPTIONS: Array<{ value: AccountActivityStage; label: string }> = [
  { value: 'all', label: '全部阶段' },
  { value: 'decision', label: 'AI 决策' },
  { value: 'proposal', label: '建议草案' },
  { value: 'execution', label: '执行引擎' },
  { value: 'order', label: '订单' },
  { value: 'fill', label: '成交' },
  { value: 'account', label: '账户变动' },
];

const ACTIVITY_STATUS_OPTIONS = [
  ['all', '全部状态'],
  ['open', '挂单中'],
  ['partial', '部分成交'],
  ['filled', '已成交'],
  ['submitted', '已提交'],
  ['planned', '已计划'],
  ['dry_run', '仅演练'],
  ['cancelled', '已撤销'],
  ['rejected', '已拒绝'],
  ['failed', '失败'],
  ['blocked', '已阻断'],
];

export function ExchangeAccountActivityPanel() {
  const [draft, setDraft] = useState<ActivityFilters>(INITIAL_ACTIVITY_FILTERS);
  const [applied, setApplied] = useState<ActivityFilters>(INITIAL_ACTIVITY_FILTERS);
  const [payload, setPayload] = useState<ExchangeAccountActivityPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSequence = useRef(0);

  const loadActivity = useCallback(async (filters: ActivityFilters, offset: number) => {
    const sequence = ++requestSequence.current;
    setLoading(true);
    setError(null);
    try {
      if (filters.range === 'custom') {
        if (!filters.customStart || !filters.customEnd) {
          throw new Error('请选择完整的起止日期');
        }
        if (filters.customStart > filters.customEnd) {
          throw new Error('开始日期不能晚于结束日期');
        }
      }
      const result = await api.exchangeAccountActivity({
        range_mode: filters.range,
        start_at: filters.range === 'custom' ? dateBoundaryIso(filters.customStart, false) : undefined,
        end_at: filters.range === 'custom' ? dateBoundaryIso(filters.customEnd, true) : undefined,
        adapter_id: filters.adapter,
        stage: filters.stage,
        status: filters.status,
        symbol: filters.symbol.trim() || undefined,
        offset,
        limit: 100,
      });
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
    void loadActivity(applied, 0);
  }, [applied, loadActivity]);

  const applyFilters = () => {
    setApplied({ ...draft, symbol: draft.symbol.trim().toUpperCase() });
  };

  const refresh = () => {
    void loadActivity(applied, payload?.page.offset || 0);
  };

  return (
    <Stack spacing={2} data-testid="exchange-account-activity">
      <Card>
        <CardContent>
          <Stack spacing={1.5}>
            <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1} alignItems={{ lg: 'center' }}>
              <ToggleButtonGroup
                exclusive
                size="small"
                value={draft.range}
                onChange={(_, value: AccountPnlRange | null) => value && setDraft((current) => ({ ...current, range: value }))}
                sx={{ '& .MuiToggleButton-root': { flex: { xs: 1, lg: 'initial' }, px: { xs: 0.75, sm: 1.25 } } }}
              >
                {ACTIVITY_RANGE_OPTIONS.map((option) => (
                  <ToggleButton key={option.value} value={option.value}>{option.label}</ToggleButton>
                ))}
              </ToggleButtonGroup>
              <FormControl sx={{ minWidth: { xs: '100%', sm: 150 } }}>
                <InputLabel>数据来源</InputLabel>
                <Select
                  label="数据来源"
                  value={draft.adapter}
                  onChange={(event) => setDraft((current) => ({ ...current, adapter: event.target.value as AccountActivityAdapter }))}
                >
                  <MenuItem value="all">全部来源</MenuItem>
                  <MenuItem value="local">FinBot 本地</MenuItem>
                  <MenuItem value="gate_testnet">Gate TestNet</MenuItem>
                  <MenuItem value="bybit_demo">Bybit Demo</MenuItem>
                </Select>
              </FormControl>
              <FormControl sx={{ minWidth: { xs: '100%', sm: 140 } }}>
                <InputLabel>操作阶段</InputLabel>
                <Select
                  label="操作阶段"
                  value={draft.stage}
                  onChange={(event) => setDraft((current) => ({ ...current, stage: event.target.value as AccountActivityStage }))}
                >
                  {ACTIVITY_STAGE_OPTIONS.map((option) => (
                    <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
                  ))}
                </Select>
              </FormControl>
              <FormControl sx={{ minWidth: { xs: '100%', sm: 130 } }}>
                <InputLabel>状态</InputLabel>
                <Select
                  label="状态"
                  value={draft.status}
                  onChange={(event) => setDraft((current) => ({ ...current, status: event.target.value }))}
                >
                  {ACTIVITY_STATUS_OPTIONS.map(([value, label]) => (
                    <MenuItem key={value} value={value}>{label}</MenuItem>
                  ))}
                </Select>
              </FormControl>
              <TextField
                label="合约"
                placeholder="BTCUSDT"
                value={draft.symbol}
                onChange={(event) => setDraft((current) => ({ ...current, symbol: event.target.value }))}
                onKeyDown={(event) => event.key === 'Enter' && applyFilters()}
                sx={{ minWidth: { xs: '100%', sm: 150 } }}
              />
              <Button variant="contained" onClick={applyFilters} disabled={loading}>查询</Button>
              <Tooltip title="按当前条件重新读取本地与交易所历史">
                <IconButton aria-label="刷新操作历史" onClick={refresh} disabled={loading}>
                  {loading ? <CircularProgress size={18} /> : <RefreshIcon />}
                </IconButton>
              </Tooltip>
            </Stack>
            {draft.range === 'custom' && (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                <TextField
                  type="date"
                  label="开始日期"
                  value={draft.customStart}
                  onChange={(event) => setDraft((current) => ({ ...current, customStart: event.target.value }))}
                  slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: localDateOffset(0) } }}
                />
                <TextField
                  type="date"
                  label="结束日期"
                  value={draft.customEnd}
                  onChange={(event) => setDraft((current) => ({ ...current, customEnd: event.target.value }))}
                  slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: draft.customStart, max: localDateOffset(0) } }}
                />
              </Stack>
            )}
            <Typography variant="caption" color="text.secondary">
              本地状态用于解释决策与提交过程；只有交易所订单和成交记录代表交易所侧事实。
            </Typography>
          </Stack>
        </CardContent>
      </Card>

      {error && <Alert severity="error" action={<Button color="inherit" size="small" onClick={refresh}>重试</Button>}>{error}</Alert>}
      {!payload && loading && <LoadingActivityState />}
      {payload && (
        <>
          <ActivitySummary payload={payload} loading={loading} />
          <ActivitySources sources={payload.sources} />
          {payload.summary.exchange_order_count === 0 && payload.summary.exchange_fill_count === 0 && (
            <Alert severity="info">
              所选范围没有读取到交易所订单或成交。AI 决策和建议草案均不等于已经下单。
            </Alert>
          )}
          <ExchangeAccountActivityList payload={payload} />
          <Stack direction="row" justifyContent="space-between" alignItems="center">
            <Typography variant="caption" color="text.secondary">
              {payload.page.returned > 0
                ? `显示 ${payload.page.offset + 1}-${payload.page.offset + payload.page.returned}，当前匹配 ${payload.summary.matched_count} 条`
                : `当前匹配 ${payload.summary.matched_count} 条`}
            </Typography>
            <Stack direction="row" spacing={1}>
              <Button
                variant="outlined"
                disabled={loading || payload.page.offset === 0}
                onClick={() => void loadActivity(applied, Math.max(0, payload.page.offset - payload.page.limit))}
              >
                上一页
              </Button>
              <Button
                variant="outlined"
                disabled={loading || !payload.page.has_more}
                onClick={() => void loadActivity(applied, payload.page.offset + payload.page.limit)}
              >
                下一页
              </Button>
            </Stack>
          </Stack>
        </>
      )}
    </Stack>
  );
}

function ActivitySummary({ payload, loading }: { payload: ExchangeAccountActivityPayload; loading: boolean }) {
  return (
    <Card>
      <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <ReceiptLongIcon color="primary" fontSize="small" />
            <Box>
              <Typography variant="subtitle1">操作审计汇总</Typography>
              <Typography variant="caption" color="text.secondary">更新于 {formatTime(payload.generated_at)}</Typography>
            </Box>
          </Stack>
          <Stack direction="row" spacing={1} alignItems="center">
            {loading && <CircularProgress size={16} />}
            <Chip size="small" color={statusColor(payload.status)} label={statusText(payload.status)} />
          </Stack>
        </Stack>
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(6, minmax(0, 1fr))' } }}>
          <ActivityMetric label="AI 决策" value={payload.summary.decision_count} />
          <ActivityMetric label="建议草案" value={payload.summary.proposal_count} />
          <ActivityMetric label="执行引擎记录" value={payload.summary.local_execution_count} />
          <ActivityMetric label="交易所订单" value={payload.summary.exchange_order_count} />
          <ActivityMetric label="交易所成交" value={payload.summary.exchange_fill_count} />
          <ActivityMetric label="账户变动" value={payload.summary.account_change_count} />
        </Box>
      </CardContent>
    </Card>
  );
}

function ActivityMetric({ label, value }: { label: string; value: number }) {
  return (
    <Box sx={{ px: 2, py: 1.5, minHeight: 72, borderRight: '1px solid', borderBottom: '1px solid', borderColor: 'divider' }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="h3" sx={{ mt: 0.5 }}>{value}</Typography>
    </Box>
  );
}

function ActivitySources({ sources }: { sources: ExchangeAccountActivitySource[] }) {
  return (
    <Card>
      <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <Typography variant="subtitle1" sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}>数据来源与完整性</Typography>
        <Stack divider={<Divider flexItem />}>
          {sources.map((source) => (
            <Stack key={source.source_id} direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" sx={{ px: 2, py: 1.25 }}>
              <Box sx={{ minWidth: 0 }}>
                <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap">
                  <Typography variant="body2" fontWeight={700}>{source.display_name}</Typography>
                  <Chip size="small" color={statusColor(source.status)} label={statusText(source.status)} />
                  <Chip size="small" variant="outlined" label={source.complete ? '区间完整' : '范围受限'} />
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>{source.message}</Typography>
                {source.error && <Typography variant="caption" color="error.main" sx={{ display: 'block' }}>{source.error}</Typography>}
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
                命中 {source.matched_record_count} / 读取 {source.fetched_record_count}
              </Typography>
            </Stack>
          ))}
        </Stack>
      </CardContent>
    </Card>
  );
}

function LoadingActivityState() {
  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <CircularProgress size={20} />
          <Typography variant="body2" color="text.secondary">正在读取本地审计与交易所只读历史</Typography>
        </Stack>
      </CardContent>
    </Card>
  );
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
