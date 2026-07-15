import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import NavigateBeforeIcon from '@mui/icons-material/NavigateBefore';
import NavigateNextIcon from '@mui/icons-material/NavigateNext';
import SaveIcon from '@mui/icons-material/Save';
import SearchIcon from '@mui/icons-material/Search';
import SyncIcon from '@mui/icons-material/Sync';
import { Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, InputAdornment, MenuItem, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type { CatalogSyncRun, ProductDetail, ProductSummary, WatchlistDetail, WatchlistSummary } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime } from './ui';

export function CatalogPage({ onResearch }: { onResearch?: (question: string) => void }) {
  const [products, setProducts] = useState<ProductSummary[] | null>(null);
  const [watchlists, setWatchlists] = useState<WatchlistSummary[]>([]);
  const [watchlist, setWatchlist] = useState<WatchlistDetail | null>(null);
  const [detail, setDetail] = useState<ProductDetail | null>(null);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('');
  const [exchange, setExchange] = useState('');
  const [marketType, setMarketType] = useState('');
  const [after, setAfter] = useState('');
  const [cursorStack, setCursorStack] = useState<string[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [totalCount, setTotalCount] = useState(0);
  const [syncRuns, setSyncRuns] = useState<CatalogSyncRun[]>([]);
  const [syncBusy, setSyncBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState('重点观察');
  const [listName, setListName] = useState('');
  const [listDescription, setListDescription] = useState('');

  const load = async () => {
    try {
      const [page, lists, latestSyncRuns] = await Promise.all([api.products({ search, category, exchange, marketType, after, limit: 50 }), api.watchlists(), api.catalogSyncRuns()]);
      setProducts(page.products); setNextCursor(page.nextCursor); setTotalCount(page.totalCount); setWatchlists(lists);
      setSyncRuns(latestSyncRuns);
      const selected = lists.find((item) => item.defaultWatchlist) || lists[0];
      const selectedDetail = watchlist && lists.some((item) => item.watchlistId === watchlist.watchlistId) ? await api.watchlist(watchlist.watchlistId) : selected ? await api.watchlist(selected.watchlistId) : null;
      setWatchlist(selectedDetail); setListName(selectedDetail?.name || ''); setListDescription(selectedDetail?.description || '');
    } catch (cause) { setError(cause); }
  };
  useEffect(() => { const timer = window.setTimeout(() => void load(), 250); return () => window.clearTimeout(timer); }, [search, category, exchange, marketType, after]);
  const watchedIds = useMemo(() => new Set(watchlist?.items.map((item) => item.productId) || []), [watchlist]);
  const toggle = async (product: ProductSummary) => {
    if (!watchlist) return;
    try {
      setWatchlist(watchedIds.has(product.productId)
        ? await api.removeWatchlistItem(watchlist.watchlistId, product.productId)
        : await api.upsertWatchlistItem(watchlist.watchlistId, product.productId, { preferredInstrumentId: null, researchMode: 'RESEARCH', note: '' }));
    } catch (cause) { setError(cause); }
  };
  const create = async () => {
    try {
      const created = await api.createWatchlist(newName.trim(), '管理员自定义产品集合');
      setWatchlists(await api.watchlists());
      setWatchlist(created); setListName(created.name); setListDescription(created.description);
      setCreateOpen(false);
    } catch (cause) { setError(cause); }
  };
  const selectWatchlist = async (watchlistId: string) => { try { const selected = await api.watchlist(watchlistId); setWatchlist(selected); setListName(selected.name); setListDescription(selected.description); } catch (cause) { setError(cause); } };
  const saveWatchlist = async () => { if (!watchlist) return; try { const updated = await api.updateWatchlist(watchlist.watchlistId, listName, listDescription, watchlist.version); setWatchlist(updated); setWatchlists(await api.watchlists()); } catch (cause) { setError(cause); } };
  const deleteWatchlist = async () => { if (!watchlist || watchlist.defaultWatchlist || !window.confirm(`删除自选列表“${watchlist.name}”？`)) return; try { await api.deleteWatchlist(watchlist.watchlistId); setWatchlist(null); await load(); } catch (cause) { setError(cause); } };
  const resetCursor = () => { setAfter(''); setCursorStack([]); };
  const synchronize = async () => {
    setSyncBusy(true); setError(null);
    try {
      const requestGroup = crypto.randomUUID();
      await Promise.all([
        api.synchronizeCatalog('GATE', 'SPOT', `${requestGroup}-gate-spot`),
        api.synchronizeCatalog('GATE', 'LINEAR_PERPETUAL', `${requestGroup}-gate-linear`),
        api.synchronizeCatalog('BYBIT', 'SPOT', `${requestGroup}-bybit-spot`),
        api.synchronizeCatalog('BYBIT', 'LINEAR_PERPETUAL', `${requestGroup}-bybit-linear`),
      ]);
      window.setTimeout(() => void load(), 1500);
    } catch (cause) { setError(cause); } finally { setSyncBusy(false); }
  };
  if (error !== null && !products) return <ErrorBlock error={error} />;
  if (!products) return <LoadingBlock label="正在读取规范化产品库" />;
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField fullWidth value={search} onChange={(event) => { setSearch(event.target.value); resetCursor(); }} placeholder="搜索产品、基础资产、报价资产或交易所代码" InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> }} /><TextField select label="类目" value={category} onChange={(event) => { setCategory(event.target.value); resetCursor(); }} sx={{ minWidth: 150 }}><MenuItem value="">全部类目</MenuItem>{['CRYPTO', 'COMMODITY', 'INDEX', 'FOREX', 'EQUITY'].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField><TextField select label="交易所" value={exchange} onChange={(event) => { setExchange(event.target.value); resetCursor(); }} sx={{ minWidth: 130 }}><MenuItem value="">全部交易所</MenuItem><MenuItem value="GATE">Gate</MenuItem><MenuItem value="BYBIT">Bybit</MenuItem></TextField><TextField select label="交易类型" value={marketType} onChange={(event) => { setMarketType(event.target.value); resetCursor(); }} sx={{ minWidth: 180 }}><MenuItem value="">全部类型</MenuItem>{['SPOT', 'LINEAR_PERPETUAL', 'INVERSE_PERPETUAL', 'FUTURE'].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField></Stack></Paper>
    <Stack direction={{ xs: 'column', xl: 'row' }} spacing={2} alignItems="flex-start">
      <Box sx={{ flex: 1, width: '100%' }}><SectionTitle title={`产品库 · ${totalCount} 项`} action={<Button size="small" startIcon={<SyncIcon />} disabled={syncBusy} onClick={() => void synchronize()}>{syncBusy ? '正在提交' : '同步实盘产品'}</Button>} /><Paper variant="outlined" sx={{ p: 1.25, mb: 1.25 }}><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} useFlexGap flexWrap="wrap">{syncRuns.map((run) => <Chip key={`${run.exchange}-${run.marketType}`} size="small" color={run.status === 'COMPLETED' ? 'success' : run.status === 'FAILED' ? 'error' : 'info'} label={`${run.exchange} ${run.marketType === 'SPOT' ? '现货' : 'USDT 永续'} · ${run.status === 'COMPLETED' ? `${run.activeCount} 个有效产品` : run.status === 'RUNNING' ? '同步中' : '同步失败'} · ${formatTime(run.completedAt || run.startedAt)}`} />)}{syncRuns.length === 0 && <Typography variant="caption" color="text.secondary">尚无实盘产品目录同步记录，后台定时任务将自动执行。</Typography>}</Stack></Paper><Paper variant="outlined" sx={{ overflow: 'hidden' }}>{products.map((product, index) => <Stack key={product.productId} direction="row" alignItems="center" spacing={1.5} sx={{ p: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider', cursor: 'pointer' }} onClick={() => api.product(product.productId).then(setDetail).catch(setError)}><Box sx={{ flex: 1, minWidth: 0 }}><Typography fontWeight={700}>{product.displayName}</Typography><Typography variant="caption" color="text.secondary">{product.baseAsset}/{product.quoteAsset} · {product.category} · {product.instrumentCount} 个交易所产品映射</Typography></Box>{product.highestWatchlistMode && <Chip size="small" label={product.highestWatchlistMode} />}<Tooltip title={watchedIds.has(product.productId) ? '移出自选' : '加入自选'}><IconButton onClick={(event) => { event.stopPropagation(); void toggle(product); }} color={watchedIds.has(product.productId) ? 'error' : 'primary'}>{watchedIds.has(product.productId) ? <DeleteOutlineIcon /> : <AddIcon />}</IconButton></Tooltip></Stack>)}{products.length === 0 && <EmptyBlock />}</Paper><Stack direction="row" justifyContent="flex-end" spacing={1} sx={{ mt: 1 }}><Button startIcon={<NavigateBeforeIcon />} disabled={cursorStack.length === 0} onClick={() => { const stack = [...cursorStack]; const previous = stack.pop() || ''; setCursorStack(stack); setAfter(previous); }}>上一页</Button><Button endIcon={<NavigateNextIcon />} disabled={!nextCursor} onClick={() => { setCursorStack((stack) => [...stack, after]); setAfter(nextCursor || ''); }}>下一页</Button></Stack></Box>
      <Box sx={{ width: { xs: '100%', xl: 410 }, flexShrink: 0 }}><SectionTitle title="自选列表" action={<Button size="small" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>新建</Button>} /><TextField select fullWidth label="当前列表" value={watchlist?.watchlistId || ''} onChange={(event) => void selectWatchlist(event.target.value)} sx={{ mb: 1.25 }}>{watchlists.map((item) => <MenuItem key={item.watchlistId} value={item.watchlistId}>{item.name} ({item.itemCount})</MenuItem>)}</TextField>{watchlist && <Paper variant="outlined" sx={{ p: 1.5, mb: 1.25 }}><Stack spacing={1}><TextField size="small" label="列表名称" value={listName} onChange={(event) => setListName(event.target.value)} /><TextField size="small" label="说明" value={listDescription} onChange={(event) => setListDescription(event.target.value)} /><Stack direction="row" justifyContent="flex-end"><Tooltip title="保存列表"><IconButton color="primary" onClick={() => void saveWatchlist()}><SaveIcon /></IconButton></Tooltip><Tooltip title={watchlist.defaultWatchlist ? '默认列表不可删除' : '删除列表'}><span><IconButton color="error" disabled={watchlist.defaultWatchlist} onClick={() => void deleteWatchlist()}><DeleteOutlineIcon /></IconButton></span></Tooltip></Stack></Stack></Paper>}<Paper variant="outlined" sx={{ overflow: 'hidden' }}>{watchlist?.items.map((item, index) => <Stack key={item.productId} direction="row" spacing={1} alignItems="center" sx={{ p: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{item.displayName}</Typography><Typography variant="caption" color="text.secondary">{item.researchMode} · {item.preferredInstrumentId || '自动选择交易所映射'}</Typography></Box><Button size="small" onClick={() => onResearch?.(`对 ${item.displayName}（${item.baseAsset}/${item.quoteAsset}）进行完整研究，比较 Gate 与 Bybit 行情、证据、量化结果和交易风险`)}>研究</Button></Stack>)}{!watchlist?.items.length && <EmptyBlock>自选列表为空</EmptyBlock>}</Paper></Box>
    </Stack>
    {detail && <Dialog open maxWidth="md" fullWidth onClose={() => setDetail(null)}><DialogTitle>{detail.displayName}</DialogTitle><DialogContent dividers><Stack spacing={1.5}>{detail.instruments.map((instrument) => <Paper key={instrument.instrumentId} variant="outlined" sx={{ p: 1.5 }}><Stack direction="row" justifyContent="space-between" spacing={1}><Typography fontWeight={700}>{instrument.exchange} · {instrument.symbol}</Typography><Stack direction="row" spacing={0.75}><Chip size="small" color={instrument.executionEnabled ? 'success' : 'default'} label={instrument.executionEnabled ? '可模拟执行' : '仅研究'} /><Chip size="small" label={`${instrument.maximumLeverage}x max`} /></Stack></Stack><Typography variant="body2" color="text.secondary">{instrument.marketType} · 合约单位 {instrument.contractSize} · 价格步长 {instrument.priceTick} · 数量步长 {instrument.quantityStep} · 最小数量 {instrument.minimumQuantity}</Typography><Typography variant="body2" sx={{ mt: .5 }}>最新价 {instrument.latestPrice ?? '-'} · {formatTime(instrument.latestPriceAt)}</Typography></Paper>)}</Stack></DialogContent><DialogActions><Button onClick={() => setDetail(null)}>关闭</Button><Button variant="contained" onClick={() => { onResearch?.(`对 ${detail.displayName} 进行完整研究与模拟交易评估`); setDetail(null); }}>发起研究</Button></DialogActions></Dialog>}
    <Dialog open={createOpen} onClose={() => setCreateOpen(false)}><DialogTitle>新建自选列表</DialogTitle><DialogContent><TextField autoFocus label="名称" value={newName} onChange={(event) => setNewName(event.target.value)} sx={{ mt: 1 }} /></DialogContent><DialogActions><Button onClick={() => setCreateOpen(false)}>取消</Button><Button variant="contained" disabled={!newName.trim()} onClick={() => void create()}>创建</Button></DialogActions></Dialog>
  </Stack>;
}
