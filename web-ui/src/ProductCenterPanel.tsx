import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Drawer,
  FormControl,
  IconButton,
  InputLabel,
  MenuItem,
  Pagination,
  Select,
  Skeleton,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
  useMediaQuery,
} from '@mui/material';
import { useTheme } from '@mui/material/styles';
import BookmarkAddOutlinedIcon from '@mui/icons-material/BookmarkAddOutlined';
import CloseIcon from '@mui/icons-material/Close';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import RefreshIcon from '@mui/icons-material/Refresh';
import SaveIcon from '@mui/icons-material/Save';
import SearchIcon from '@mui/icons-material/Search';
import StarIcon from '@mui/icons-material/Star';
import StarBorderIcon from '@mui/icons-material/StarBorder';
import TravelExploreIcon from '@mui/icons-material/TravelExplore';
import type { ChangeEvent, MouseEvent } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type {
  CatalogInstrument,
  InstantResearchSeed,
  PaperExecutionStatusPayload,
  ProductDetailPayload,
  ProductListPayload,
  WatchlistResearchMode,
  WatchlistSummary,
} from './types';
import { statusColor, statusText } from './utils';

const PAGE_SIZE = 25;

const researchModeLabels: Record<WatchlistResearchMode, string> = {
  monitor: '仅关注',
  research: '进入研究',
  pinned: '优先研究',
};

const marketTypeLabels: Record<string, string> = {
  spot: '现货',
  perpetual: '永续',
  linear: '线性永续',
  future: '交割合约',
};

interface WatchlistFormState {
  preferredInstrumentId: string;
  researchMode: WatchlistResearchMode;
  tags: string;
  notes: string;
}

const emptyForm: WatchlistFormState = {
  preferredInstrumentId: '',
  researchMode: 'monitor',
  tags: '',
  notes: '',
};

export function ProductCenterPanel({ onStartResearch }: { onStartResearch: (request: InstantResearchSeed) => void }) {
  const theme = useTheme();
  const desktopInspector = useMediaQuery(theme.breakpoints.up('xl'));
  const tableLayout = useMediaQuery(theme.breakpoints.up('sm'));
  const [catalog, setCatalog] = useState<ProductListPayload | null>(null);
  const [watchlists, setWatchlists] = useState<WatchlistSummary[]>([]);
  const [paperStatus, setPaperStatus] = useState<PaperExecutionStatusPayload | null>(null);
  const [selectedProductId, setSelectedProductId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ProductDetailPayload | null>(null);
  const [mobileDetailOpen, setMobileDetailOpen] = useState(false);
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [provider, setProvider] = useState('');
  const [marketType, setMarketType] = useState('');
  const [activeFilter, setActiveFilter] = useState('true');
  const [watchedOnly, setWatchedOnly] = useState(false);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<WatchlistFormState>(emptyForm);

  const defaultWatchlist = useMemo(
    () => watchlists.find((watchlist) => watchlist.is_default) || watchlists[0] || null,
    [watchlists],
  );

  const loadCatalog = useCallback(async () => {
    if (!defaultWatchlist) {
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const payload = await api.products({
        search,
        provider,
        market_type: marketType,
        active: activeFilter === '' ? undefined : activeFilter === 'true',
        watchlist_id: defaultWatchlist.watchlist_id,
        watched_only: watchedOnly,
        page,
        page_size: PAGE_SIZE,
      });
      setCatalog(payload);
      if (payload.items.length > 0) {
        setSelectedProductId((current) => current || payload.items[0].product_id);
      }
      if (payload.items.length === 0) {
        setSelectedProductId(null);
        setDetail(null);
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [activeFilter, defaultWatchlist, marketType, page, provider, search, watchedOnly]);

  const loadDetail = useCallback(async (productId: string) => {
    if (!defaultWatchlist) {
      return;
    }
    setDetailLoading(true);
    try {
      const payload = await api.product(productId, defaultWatchlist.watchlist_id);
      setDetail(payload);
      const watched = payload.watchlist_item;
      setForm({
        preferredInstrumentId: watched?.preferred_instrument_id || payload.instruments[0]?.instrument_id || '',
        researchMode: watched?.research_mode || 'monitor',
        tags: (watched?.tags || []).join(', '),
        notes: watched?.notes || '',
      });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setDetailLoading(false);
    }
  }, [defaultWatchlist]);

  const loadWorkspace = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [watchlistPayload, executionPayload] = await Promise.all([
        api.watchlists(),
        api.paperExecutionStatus(),
      ]);
      setWatchlists(watchlistPayload.watchlists);
      setPaperStatus(executionPayload);
    } catch (err) {
      setError(errorMessage(err));
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadWorkspace();
  }, [loadWorkspace]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setPage(1);
      setSearch(searchInput.trim());
    }, 300);
    return () => window.clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    loadCatalog();
  }, [loadCatalog]);

  useEffect(() => {
    if (selectedProductId) {
      loadDetail(selectedProductId);
    }
  }, [loadDetail, selectedProductId]);

  const selectProduct = (instrument: CatalogInstrument) => {
    setSelectedProductId(instrument.product_id);
    if (!desktopInspector) {
      setMobileDetailOpen(true);
    }
  };

  const refreshAll = async () => {
    await Promise.all([
      loadCatalog(),
      api.paperExecutionStatus().then(setPaperStatus),
      selectedProductId ? loadDetail(selectedProductId) : Promise.resolve(),
    ]);
  };

  const toggleWatchlist = async (event: MouseEvent, instrument: CatalogInstrument) => {
    event.stopPropagation();
    if (!defaultWatchlist) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      if (instrument.watchlist_item) {
        await api.deleteWatchlistItem(defaultWatchlist.watchlist_id, instrument.product_id);
      } else {
        await api.upsertWatchlistItem(defaultWatchlist.watchlist_id, instrument.product_id, {
          preferred_instrument_id: instrument.instrument_id,
          research_mode: 'monitor',
          notes: '',
          tags: [],
        });
      }
      await Promise.all([loadCatalog(), api.watchlists().then((payload) => setWatchlists(payload.watchlists))]);
      if (selectedProductId === instrument.product_id) {
        await loadDetail(instrument.product_id);
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const saveWatchlistItem = async () => {
    if (!defaultWatchlist || !detail) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await api.upsertWatchlistItem(
        defaultWatchlist.watchlist_id,
        detail.product.product_id,
        {
          preferred_instrument_id: form.preferredInstrumentId || null,
          research_mode: form.researchMode,
          tags: splitTags(form.tags),
          notes: form.notes.trim(),
        },
      );
      await Promise.all([
        loadCatalog(),
        loadDetail(detail.product.product_id),
        api.watchlists().then((payload) => setWatchlists(payload.watchlists)),
      ]);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const removeWatchlistItem = async () => {
    if (!defaultWatchlist || !detail?.watchlist_item) {
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await api.deleteWatchlistItem(defaultWatchlist.watchlist_id, detail.product.product_id);
      await Promise.all([
        loadCatalog(),
        loadDetail(detail.product.product_id),
        api.watchlists().then((payload) => setWatchlists(payload.watchlists)),
      ]);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  const handleModeChange = (_event: MouseEvent<HTMLElement>, value: WatchlistResearchMode | null) => {
    if (value) {
      setForm((current) => ({ ...current, researchMode: value }));
    }
  };

  const startResearch = () => {
    if (!detail) return;
    const instrument = detail.instruments.find((item) => item.instrument_id === form.preferredInstrumentId)
      || detail.instruments[0];
    if (!instrument) return;
    onStartResearch({
      seed_id: `${detail.product.product_id}:${instrument.instrument_id}:${Date.now()}`,
      query: `研究 ${detail.product.display_name}（${instrument.symbol}）的最新信息、市场驱动、主要风险和交易条件`,
      symbols: [instrument.normalized_symbol || instrument.symbol],
      display_name: detail.product.display_name,
      product_id: detail.product.product_id,
      preferred_instrument_id: instrument.instrument_id,
      watchlist_id: defaultWatchlist?.watchlist_id,
      provider: instrument.provider,
      market_type: instrument.market_type,
    });
  };

  return (
    <Stack spacing={2}>
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

      <ExecutionStatusBand status={paperStatus} />

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: 'minmax(0, 1fr)', lg: 'minmax(680px, 1fr) 360px' },
          gap: 2,
          alignItems: 'start',
        }}
      >
        <Box sx={{ minWidth: 0, bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
          <Box sx={{ px: { xs: 1.5, md: 2 }, pt: 1.25 }}>
            <Stack
              direction={{ xs: 'column', md: 'row' }}
              alignItems={{ xs: 'stretch', md: 'center' }}
              justifyContent="space-between"
              spacing={1}
            >
              <Tabs
                value={watchedOnly ? 'watchlist' : 'all'}
                onChange={(_event, value: 'all' | 'watchlist') => {
                  setPage(1);
                  setWatchedOnly(value === 'watchlist');
                }}
                aria-label="产品视图"
              >
                <Tab value="all" label="全部产品" />
                <Tab value="watchlist" label={`Watchlist${defaultWatchlist?.item_count !== undefined ? ` ${defaultWatchlist.item_count}` : ''}`} />
              </Tabs>
              <Tooltip title="刷新产品与模拟执行状态">
                <IconButton onClick={refreshAll} disabled={loading} sx={{ alignSelf: { xs: 'flex-end', md: 'center' } }}>
                  <RefreshIcon />
                </IconButton>
              </Tooltip>
            </Stack>

            <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ py: 1.5 }}>
              <TextField
                size="small"
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                placeholder="搜索产品或交易对"
                slotProps={{ input: { startAdornment: <SearchIcon color="action" fontSize="small" sx={{ mr: 1 }} /> } }}
                sx={{ flex: { xs: '0 0 auto', md: '1 1 260px' } }}
              />
              <FilterSelect label="交易所" value={provider} onChange={(value) => { setProvider(value); setPage(1); }} options={[['gate', 'Gate'], ['bybit', 'Bybit']]} />
              <FilterSelect
                label="交易类型"
                value={marketType}
                onChange={(value) => { setMarketType(value); setPage(1); }}
                options={[['spot', '现货'], ['perpetual', 'Gate 永续'], ['linear', 'Bybit 线性永续']]}
              />
              <FilterSelect
                label="状态"
                value={activeFilter}
                onChange={(value) => { setActiveFilter(value); setPage(1); }}
                options={[['true', '可交易'], ['false', '已停用']]}
                allLabel="全部状态"
              />
            </Stack>
          </Box>
          <Divider />

          {loading && !catalog ? (
            <Stack spacing={1} sx={{ p: 2 }}>
              {Array.from({ length: 8 }, (_, index) => <Skeleton key={index} height={44} />)}
            </Stack>
          ) : catalog?.items.length ? (
            tableLayout ? (
              <ProductTable
                items={catalog.items}
                selectedProductId={selectedProductId}
                disabled={saving}
                onSelect={selectProduct}
                onToggleWatchlist={toggleWatchlist}
              />
            ) : (
              <ProductMobileList
                items={catalog.items}
                disabled={saving}
                onSelect={selectProduct}
                onToggleWatchlist={toggleWatchlist}
              />
            )
          ) : (
            <Box sx={{ py: 8, px: 2, textAlign: 'center' }}>
              <BookmarkAddOutlinedIcon color="disabled" sx={{ fontSize: 42 }} />
              <Typography variant="h3" sx={{ mt: 1 }}>{watchedOnly ? '暂无关注产品' : '暂无匹配产品'}</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                调整搜索条件或等待下一轮产品目录同步
              </Typography>
            </Box>
          )}

          {catalog && catalog.total_pages > 1 && (
            <Stack direction="row" justifyContent="center" sx={{ px: 2, py: 1.5, borderTop: '1px solid', borderColor: 'divider' }}>
              <Pagination
                page={catalog.page}
                count={catalog.total_pages}
                onChange={(_event, nextPage) => setPage(nextPage)}
                color="primary"
                size={tableLayout ? 'medium' : 'small'}
              />
            </Stack>
          )}
        </Box>

        {desktopInspector && (
          <ProductInspector
            detail={detail}
            loading={detailLoading}
            saving={saving}
            form={form}
            onFormChange={setForm}
            onModeChange={handleModeChange}
            onSave={saveWatchlistItem}
            onRemove={removeWatchlistItem}
            onStartResearch={startResearch}
          />
        )}
      </Box>

      {!desktopInspector && (
        <Drawer
          anchor="bottom"
          open={mobileDetailOpen}
          onClose={() => setMobileDetailOpen(false)}
          PaperProps={{
            sx: {
              maxHeight: '88vh',
              borderTopLeftRadius: 8,
              borderTopRightRadius: 8,
            },
          }}
        >
          <Box sx={{ position: 'sticky', top: 0, zIndex: 1, bgcolor: 'background.paper', borderBottom: '1px solid', borderColor: 'divider' }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ pl: 2, pr: 1, py: 1 }}>
              <Typography variant="h3">产品详情</Typography>
              <Tooltip title="关闭">
                <IconButton onClick={() => setMobileDetailOpen(false)}><CloseIcon /></IconButton>
              </Tooltip>
            </Stack>
          </Box>
          <Box sx={{ p: { xs: 1.5, sm: 2 } }}>
            <ProductInspector
              detail={detail}
              loading={detailLoading}
              saving={saving}
              form={form}
              embedded
              onFormChange={setForm}
              onModeChange={handleModeChange}
              onSave={saveWatchlistItem}
              onRemove={removeWatchlistItem}
              onStartResearch={startResearch}
            />
          </Box>
        </Drawer>
      )}
    </Stack>
  );
}

function ExecutionStatusBand({ status }: { status: PaperExecutionStatusPayload | null }) {
  if (!status) {
    return <Skeleton height={72} variant="rounded" />;
  }
  const latestExecution = status.recent_executions[0];
  const latestRunSummary = status.recent_runs[0]?.summary as Record<string, unknown> | undefined;
  const recentExecutionText = latestExecution
    ? `${String(latestExecution.adapter_id || '').replace('_testnet', '').replace('_demo', '')} · ${String(latestExecution.symbol || '-')} · ${statusText(String(latestExecution.status || 'unknown'))}`
    : latestRunSummary
      ? `本轮 ${String(latestRunSummary.execution_count ?? 0)} 笔`
      : '暂无';
  return (
    <Box sx={{ bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 1, px: { xs: 1.5, md: 2 }, py: 1.25 }}>
      <Stack direction={{ xs: 'column', md: 'row' }} alignItems={{ xs: 'stretch', md: 'center' }} spacing={1.5}>
        <Box sx={{ minWidth: 170 }}>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>模拟执行</Typography>
          <Typography variant="caption" color="text.secondary">
            {status.enabled ? (status.mode === 'submit' ? '模拟下单已开启' : '计划演练已开启') : '未加入自动研究'}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 0.75, flexGrow: 1 }}>
          {status.adapters.map((adapter) => {
            const ready = adapter.enabled && adapter.credentials_verified === true && adapter.status === 'ready';
            const label = adapter.status === 'blocked'
              ? adapter.credentials_configured ? '鉴权受阻' : '待凭据'
              : statusText(adapter.status);
            return (
              <Tooltip key={adapter.adapter_id} title={adapter.credential_probe?.reason || adapter.blockers.join(' · ')}>
                <Chip
                  size="small"
                  variant={ready ? 'filled' : 'outlined'}
                  color={ready ? 'success' : adapter.status === 'blocked' ? 'error' : adapter.enabled ? 'warning' : 'default'}
                  label={`${adapter.display_name} · ${adapter.enabled ? label : '未启用'}`}
                />
              </Tooltip>
            );
          })}
        </Stack>
        <Stack direction="row" spacing={2} sx={{ whiteSpace: 'nowrap' }}>
          <Metric label="单笔上限" value={`${formatNumber(status.policy.max_notional_usdt)} USDT`} />
          <Metric label="最低置信度" value={`${Math.round(status.policy.min_confidence * 100)}%`} />
          <Metric label="最近执行" value={recentExecutionText} />
        </Stack>
      </Stack>
    </Box>
  );
}

function ProductTable({
  items,
  selectedProductId,
  disabled,
  onSelect,
  onToggleWatchlist,
}: {
  items: CatalogInstrument[];
  selectedProductId: string | null;
  disabled: boolean;
  onSelect: (instrument: CatalogInstrument) => void;
  onToggleWatchlist: (event: MouseEvent, instrument: CatalogInstrument) => void;
}) {
  return (
    <TableContainer>
      <Table size="small" sx={{ tableLayout: 'fixed', minWidth: 760 }}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: 48 }} />
            <TableCell sx={{ width: 180 }}>产品</TableCell>
            <TableCell sx={{ width: 90 }}>交易所</TableCell>
            <TableCell sx={{ width: 120 }}>交易类型</TableCell>
            <TableCell align="right" sx={{ width: 120 }}>最新价</TableCell>
            <TableCell align="right" sx={{ width: 130 }}>24h 成交额</TableCell>
            <TableCell sx={{ width: 100 }}>研究状态</TableCell>
            <TableCell sx={{ width: 88 }}>状态</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((instrument) => (
            <TableRow
              key={instrument.instrument_id}
              hover
              selected={selectedProductId === instrument.product_id}
              onClick={() => onSelect(instrument)}
              sx={{ cursor: 'pointer', '& .MuiTableCell-root': { py: 1 } }}
            >
              <TableCell>
                <Tooltip title={instrument.watchlist_item ? '移出 Watchlist' : '加入 Watchlist'}>
                  <span>
                    <IconButton
                      size="small"
                      color={instrument.watchlist_item ? 'warning' : 'default'}
                      disabled={disabled}
                      aria-label={instrument.watchlist_item ? '移出 Watchlist' : '加入 Watchlist'}
                      onClick={(event) => onToggleWatchlist(event, instrument)}
                    >
                      {instrument.watchlist_item ? <StarIcon fontSize="small" /> : <StarBorderIcon fontSize="small" />}
                    </IconButton>
                  </span>
                </Tooltip>
              </TableCell>
              <TableCell>
                <Typography variant="body2" sx={{ fontWeight: 800 }} noWrap>{instrument.display_name}</Typography>
                <Typography variant="caption" color="text.secondary" noWrap>{instrument.symbol}</Typography>
              </TableCell>
              <TableCell><ProviderLabel provider={instrument.provider} /></TableCell>
              <TableCell>{marketTypeLabel(instrument.market_type)}</TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 700 }}>
                {formatPrice(instrument.market_snapshot.last_price)}
              </TableCell>
              <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                {formatCompact(instrument.market_snapshot.turnover_24h)}
              </TableCell>
              <TableCell>
                {instrument.watchlist_item ? (
                  <Chip size="small" variant="outlined" color={instrument.watchlist_item.research_mode === 'pinned' ? 'primary' : 'default'} label={researchModeLabels[instrument.watchlist_item.research_mode]} />
                ) : <Typography variant="caption" color="text.disabled">未关注</Typography>}
              </TableCell>
              <TableCell>
                <Chip size="small" color={instrument.active ? 'success' : 'default'} variant="outlined" label={instrument.active ? '可交易' : '停用'} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

function ProductMobileList({
  items,
  disabled,
  onSelect,
  onToggleWatchlist,
}: {
  items: CatalogInstrument[];
  disabled: boolean;
  onSelect: (instrument: CatalogInstrument) => void;
  onToggleWatchlist: (event: MouseEvent, instrument: CatalogInstrument) => void;
}) {
  return (
    <Stack divider={<Divider flexItem />}>
      {items.map((instrument) => (
        <Box key={instrument.instrument_id} onClick={() => onSelect(instrument)} sx={{ px: 1.5, py: 1.25, cursor: 'pointer' }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <IconButton
              size="small"
              color={instrument.watchlist_item ? 'warning' : 'default'}
              disabled={disabled}
              onClick={(event) => onToggleWatchlist(event, instrument)}
              aria-label={instrument.watchlist_item ? '移出 Watchlist' : '加入 Watchlist'}
            >
              {instrument.watchlist_item ? <StarIcon fontSize="small" /> : <StarBorderIcon fontSize="small" />}
            </IconButton>
            <Box sx={{ minWidth: 0, flexGrow: 1 }}>
              <Stack direction="row" spacing={0.75} alignItems="center">
                <Typography variant="body2" sx={{ fontWeight: 800 }} noWrap>{instrument.display_name}</Typography>
                <Typography variant="caption" color="text.secondary">{instrument.provider.toUpperCase()}</Typography>
              </Stack>
              <Typography variant="caption" color="text.secondary" noWrap>
                {marketTypeLabel(instrument.market_type)} · {instrument.symbol}
              </Typography>
            </Box>
            <Box sx={{ textAlign: 'right', minWidth: 96 }}>
              <Typography variant="body2" sx={{ fontWeight: 800, fontVariantNumeric: 'tabular-nums' }}>
                {formatPrice(instrument.market_snapshot.last_price)}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {instrument.watchlist_item ? researchModeLabels[instrument.watchlist_item.research_mode] : formatCompact(instrument.market_snapshot.turnover_24h)}
              </Typography>
            </Box>
          </Stack>
        </Box>
      ))}
    </Stack>
  );
}

function ProductInspector({
  detail,
  loading,
  saving,
  form,
  embedded = false,
  onFormChange,
  onModeChange,
  onSave,
  onRemove,
  onStartResearch,
}: {
  detail: ProductDetailPayload | null;
  loading: boolean;
  saving: boolean;
  form: WatchlistFormState;
  embedded?: boolean;
  onFormChange: (value: WatchlistFormState) => void;
  onModeChange: (event: MouseEvent<HTMLElement>, value: WatchlistResearchMode | null) => void;
  onSave: () => void;
  onRemove: () => void;
  onStartResearch: () => void;
}) {
  const content = loading && !detail ? (
    <Stack spacing={1.5}>
      <Skeleton height={40} />
      <Skeleton height={72} />
      <Skeleton height={120} />
    </Stack>
  ) : !detail ? (
    <Box sx={{ py: 8, textAlign: 'center' }}>
      <Typography variant="body2" color="text.secondary">选择一个产品查看详情</Typography>
    </Box>
  ) : (
    <Stack spacing={2}>
      <Box>
        <Stack direction="row" justifyContent="space-between" spacing={1} alignItems="flex-start">
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="h2" sx={{ fontSize: 22 }} noWrap>{detail.product.display_name}</Typography>
            <Typography variant="caption" color="text.secondary">
              {detail.product.base_asset} · {detail.product.quote_asset || '未指定计价资产'}
            </Typography>
          </Box>
          <Chip
            size="small"
            variant="outlined"
            color={detail.product.status === 'active' ? 'success' : statusColor(detail.product.status)}
            label={detail.product.status === 'active' ? '活跃' : statusText(detail.product.status)}
          />
        </Stack>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
        <Metric label="交易场所" value={`${detail.instruments.length}`} />
        <Metric label="产品类型" value={marketTypeLabel(detail.product.product_type)} />
      </Box>

      <Divider />

      <Box>
        <Typography variant="body2" sx={{ fontWeight: 800, mb: 1 }}>首选交易标的</Typography>
        <TextField
          select
          fullWidth
          size="small"
          value={form.preferredInstrumentId}
          onChange={(event) => onFormChange({ ...form, preferredInstrumentId: event.target.value })}
        >
          {detail.instruments.map((instrument) => (
            <MenuItem key={instrument.instrument_id} value={instrument.instrument_id}>
              {instrument.provider.toUpperCase()} · {instrument.symbol} · {marketTypeLabel(instrument.market_type)}
            </MenuItem>
          ))}
        </TextField>
      </Box>

      <Box>
        <Typography variant="body2" sx={{ fontWeight: 800, mb: 1 }}>研究优先级</Typography>
        <ToggleButtonGroup
          value={form.researchMode}
          exclusive
          fullWidth
          size="small"
          onChange={onModeChange}
          aria-label="研究优先级"
        >
          {(Object.keys(researchModeLabels) as WatchlistResearchMode[]).map((mode) => (
            <ToggleButton key={mode} value={mode}>{researchModeLabels[mode]}</ToggleButton>
          ))}
        </ToggleButtonGroup>
      </Box>

      <TextField
        label="标签"
        size="small"
        value={form.tags}
        onChange={(event) => onFormChange({ ...form, tags: event.target.value })}
        placeholder="核心, 高流动性"
      />
      <TextField
        label="研究备注"
        multiline
        minRows={4}
        value={form.notes}
        onChange={(event) => onFormChange({ ...form, notes: event.target.value })}
      />

      <Button
        variant="outlined"
        startIcon={<TravelExploreIcon />}
        disabled={saving || !form.preferredInstrumentId}
        onClick={onStartResearch}
        fullWidth
      >
        发起即时研究
      </Button>

      <Stack direction="row" spacing={1}>
        <Button
          variant="contained"
          startIcon={saving ? <CircularProgress size={16} color="inherit" /> : <SaveIcon />}
          disabled={saving || !form.preferredInstrumentId}
          onClick={onSave}
          fullWidth
        >
          {detail.watchlist_item ? '保存关注设置' : '加入 Watchlist'}
        </Button>
        {detail.watchlist_item && (
          <Tooltip title="移出 Watchlist">
            <span>
              <IconButton aria-label="移出 Watchlist" color="error" disabled={saving} onClick={onRemove} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1 }}>
                <DeleteOutlineIcon />
              </IconButton>
            </span>
          </Tooltip>
        )}
      </Stack>

      <Divider />
      <Box>
        <Typography variant="body2" sx={{ fontWeight: 800, mb: 1 }}>可用交易标的</Typography>
        <Stack spacing={0.75}>
          {detail.instruments.map((instrument) => (
            <Box key={instrument.instrument_id} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, px: 1.25, py: 1 }}>
              <Stack direction="row" justifyContent="space-between" spacing={1}>
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" sx={{ fontWeight: 800 }} noWrap>
                    {instrument.provider.toUpperCase()} · {instrument.symbol}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {marketTypeLabel(instrument.market_type)} · 最小数量 {formatNumber(instrument.min_amount)} · 最小名义 {formatNumber(instrument.min_notional)} USDT
                  </Typography>
                  {instrument.contract && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      合约乘数 {formatNumber(instrument.contract_size)} · 杠杆 {leverageText(instrument.leverage)}
                    </Typography>
                  )}
                </Box>
                <Typography variant="body2" sx={{ fontWeight: 800, fontVariantNumeric: 'tabular-nums' }}>
                  {formatPrice(instrument.market_snapshot.last_price)}
                </Typography>
              </Stack>
            </Box>
          ))}
        </Stack>
      </Box>
    </Stack>
  );

  if (embedded) {
    return content;
  }
  return (
    <Box sx={{ bgcolor: 'background.paper', border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 2, position: 'sticky', top: 96 }}>
      {content}
    </Box>
  );
}

function FilterSelect({
  label,
  value,
  options,
  allLabel = '全部',
  onChange,
}: {
  label: string;
  value: string;
  options: Array<[string, string]>;
  allLabel?: string;
  onChange: (value: string) => void;
}) {
  return (
    <FormControl size="small" sx={{ minWidth: { xs: '100%', md: 132 } }}>
      <InputLabel>{label}</InputLabel>
      <Select value={value} label={label} onChange={(event) => onChange(event.target.value)}>
        <MenuItem value="">{allLabel}</MenuItem>
        {options.map(([optionValue, optionLabel]) => (
          <MenuItem key={optionValue} value={optionValue}>{optionLabel}</MenuItem>
        ))}
      </Select>
    </FormControl>
  );
}

function ProviderLabel({ provider }: { provider: string }) {
  return <Typography variant="body2" sx={{ fontWeight: 700 }}>{provider === 'gate' ? 'Gate' : provider === 'bybit' ? 'Bybit' : provider}</Typography>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{label}</Typography>
      <Typography variant="body2" sx={{ fontWeight: 800 }} noWrap>{value}</Typography>
    </Box>
  );
}

function marketTypeLabel(value: string): string {
  return marketTypeLabels[value] || value || '未分类';
}

function formatPrice(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '--';
  }
  return new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: value < 1 ? 4 : 2,
    maximumFractionDigits: value < 1 ? 8 : 2,
  }).format(value);
}

function formatCompact(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '--';
  }
  return new Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 2 }).format(value);
}

function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '--';
  }
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 6 }).format(value);
}

function splitTags(value: string): string[] {
  return Array.from(new Set(value.split(/[,，]/).map((tag) => tag.trim()).filter(Boolean)));
}

function leverageText(leverage: Record<string, unknown>): string {
  const minimum = Number(leverage.min);
  const maximum = Number(leverage.max);
  if (Number.isFinite(minimum) && Number.isFinite(maximum)) {
    return `${formatNumber(minimum)}-${formatNumber(maximum)}x`;
  }
  if (Number.isFinite(maximum)) {
    return `最高 ${formatNumber(maximum)}x`;
  }
  return '未提供';
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    try {
      const payload = JSON.parse(error.message) as { detail?: string };
      return payload.detail || error.message;
    } catch {
      return error.message;
    }
  }
  return String(error);
}
