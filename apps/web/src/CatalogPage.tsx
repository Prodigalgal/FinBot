import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import SearchIcon from '@mui/icons-material/Search';
import { Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, InputAdornment, MenuItem, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type { ProductDetail, ProductSummary, WatchlistDetail, WatchlistSummary } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle } from './ui';

export function CatalogPage({ onResearch }: { onResearch?: (question: string) => void }) {
  const [products, setProducts] = useState<ProductSummary[] | null>(null);
  const [watchlists, setWatchlists] = useState<WatchlistSummary[]>([]);
  const [watchlist, setWatchlist] = useState<WatchlistDetail | null>(null);
  const [detail, setDetail] = useState<ProductDetail | null>(null);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [newName, setNewName] = useState('重点观察');

  const load = async () => {
    try {
      const [page, lists] = await Promise.all([api.products({ search, category, limit: 100 }), api.watchlists()]);
      setProducts(page.products); setWatchlists(lists);
      const selected = lists.find((item) => item.defaultWatchlist) || lists[0];
      setWatchlist(selected ? await api.watchlist(selected.watchlistId) : null);
    } catch (cause) { setError(cause); }
  };
  useEffect(() => { const timer = window.setTimeout(() => void load(), 250); return () => window.clearTimeout(timer); }, [search, category]);
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
    try { const created = await api.createWatchlist(newName.trim(), '管理员自定义产品集合'); setWatchlist(created); setCreateOpen(false); await load(); } catch (cause) { setError(cause); }
  };
  if (error !== null && !products) return <ErrorBlock error={error} />;
  if (!products) return <LoadingBlock label="正在读取规范化产品库" />;
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField fullWidth value={search} onChange={(event) => setSearch(event.target.value)} placeholder="搜索产品、基础资产或报价资产" InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> }} /><TextField select label="类目" value={category} onChange={(event) => setCategory(event.target.value)} sx={{ minWidth: 160 }}><MenuItem value="">全部</MenuItem>{['CRYPTO', 'COMMODITY', 'INDEX', 'FOREX', 'EQUITY'].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField></Stack></Paper>
    <Stack direction={{ xs: 'column', xl: 'row' }} spacing={2} alignItems="flex-start">
      <Box sx={{ flex: 1, width: '100%' }}><SectionTitle title="产品库" /><Paper variant="outlined" sx={{ overflow: 'hidden' }}>{products.map((product, index) => <Stack key={product.productId} direction="row" alignItems="center" spacing={1.5} sx={{ p: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider', cursor: 'pointer' }} onClick={() => api.product(product.productId).then(setDetail).catch(setError)}><Box sx={{ flex: 1, minWidth: 0 }}><Typography fontWeight={700}>{product.displayName}</Typography><Typography variant="caption" color="text.secondary">{product.baseAsset}/{product.quoteAsset} · {product.category} · {product.instrumentCount} 个交易映射</Typography></Box>{product.highestWatchlistMode && <Chip size="small" label={product.highestWatchlistMode} />}<Tooltip title={watchedIds.has(product.productId) ? '移出自选' : '加入自选'}><IconButton onClick={(event) => { event.stopPropagation(); void toggle(product); }} color={watchedIds.has(product.productId) ? 'error' : 'primary'}>{watchedIds.has(product.productId) ? <DeleteOutlineIcon /> : <AddIcon />}</IconButton></Tooltip></Stack>)}{products.length === 0 && <EmptyBlock />}</Paper></Box>
      <Box sx={{ width: { xs: '100%', xl: 390 }, flexShrink: 0 }}><SectionTitle title="自选列表" action={<Button size="small" startIcon={<AddIcon />} onClick={() => setCreateOpen(true)}>新建</Button>} /><TextField select fullWidth label="当前列表" value={watchlist?.watchlistId || ''} onChange={(event) => api.watchlist(event.target.value).then(setWatchlist).catch(setError)} sx={{ mb: 1.5 }}>{watchlists.map((item) => <MenuItem key={item.watchlistId} value={item.watchlistId}>{item.name} ({item.itemCount})</MenuItem>)}</TextField><Paper variant="outlined" sx={{ overflow: 'hidden' }}>{watchlist?.items.map((item, index) => <Stack key={item.productId} direction="row" spacing={1} alignItems="center" sx={{ p: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{item.displayName}</Typography><Typography variant="caption" color="text.secondary">{item.researchMode} · {item.preferredInstrumentId || '自动选择交易所映射'}</Typography></Box><Button size="small" onClick={() => onResearch?.(`对 ${item.displayName}（${item.baseAsset}/${item.quoteAsset}）进行完整研究，比较 Gate 与 Bybit 行情、证据、量化结果和交易风险`)}>研究</Button></Stack>)}{!watchlist?.items.length && <EmptyBlock>自选列表为空</EmptyBlock>}</Paper></Box>
    </Stack>
    {detail && <Dialog open maxWidth="md" fullWidth onClose={() => setDetail(null)}><DialogTitle>{detail.displayName}</DialogTitle><DialogContent dividers><Stack spacing={1.5}>{detail.instruments.map((instrument) => <Paper key={instrument.instrumentId} variant="outlined" sx={{ p: 1.5 }}><Stack direction="row" justifyContent="space-between" spacing={1}><Typography fontWeight={700}>{instrument.exchange} · {instrument.symbol}</Typography><Stack direction="row" spacing={0.75}><Chip size="small" color={instrument.executionEnabled ? 'success' : 'default'} label={instrument.executionEnabled ? '可模拟执行' : '仅研究'} /><Chip size="small" label={`${instrument.maximumLeverage}x max`} /></Stack></Stack><Typography variant="body2" color="text.secondary">{instrument.marketType} · 合约单位 {instrument.contractSize} · 价格步长 {instrument.priceTick} · 数量步长 {instrument.quantityStep} · 最小数量 {instrument.minimumQuantity}</Typography></Paper>)}</Stack></DialogContent><DialogActions><Button onClick={() => setDetail(null)}>关闭</Button><Button variant="contained" onClick={() => { onResearch?.(`对 ${detail.displayName} 进行完整研究与模拟交易评估`); setDetail(null); }}>发起研究</Button></DialogActions></Dialog>}
    <Dialog open={createOpen} onClose={() => setCreateOpen(false)}><DialogTitle>新建自选列表</DialogTitle><DialogContent><TextField autoFocus label="名称" value={newName} onChange={(event) => setNewName(event.target.value)} sx={{ mt: 1 }} /></DialogContent><DialogActions><Button onClick={() => setCreateOpen(false)}>取消</Button><Button variant="contained" disabled={!newName.trim()} onClick={() => void create()}>创建</Button></DialogActions></Dialog>
  </Stack>;
}
