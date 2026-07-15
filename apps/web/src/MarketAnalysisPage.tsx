import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import { Alert, Autocomplete, Button, MenuItem, Paper, Stack, TextField } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import type { InstrumentRecord, ProductSummary, ResearchLaunch, WorkflowDefinitionSummary } from './types';
import { ErrorBlock } from './ui';

export function MarketAnalysisPage({ onOpenRun }: { onOpenRun: (launch: ResearchLaunch) => void }) {
  const [symbol, setSymbol] = useState('BTCUSDT');
  const [instrumentId, setInstrumentId] = useState('');
  const [exchange, setExchange] = useState('BYBIT');
  const [productSearch, setProductSearch] = useState('BTC');
  const [products, setProducts] = useState<ProductSummary[]>([]);
  const [selectedProductId, setSelectedProductId] = useState('');
  const [instruments, setInstruments] = useState<InstrumentRecord[]>([]);
  const [intervalSeconds, setIntervalSeconds] = useState(3600);
  const [forecastHorizonSeconds, setForecastHorizonSeconds] = useState(86400);
  const [question, setQuestion] = useState('研究未来目标时段的方向、预期价格区间、关键驱动、证据冲突和失效条件');
  const [definitions, setDefinitions] = useState<WorkflowDefinitionSummary[]>([]);
  const [workflowVersionId, setWorkflowVersionId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => { api.workflowDefinitions().then((items) => { setDefinitions(items); setWorkflowVersionId(items.find((item) => item.active)?.publishedVersionId || ''); }).catch(setError); }, []);
  useEffect(() => { const timer = window.setTimeout(() => api.products({ search: productSearch, exchange, limit: 50 }).then((page) => setProducts(page.products)).catch(setError), 250); return () => window.clearTimeout(timer); }, [exchange, productSearch]);
  const selectProduct = async (product: ProductSummary | null) => {
    setSelectedProductId(product?.productId || ''); setInstruments([]); setInstrumentId(''); setSymbol('');
    if (!product) return;
    try {
      const detail = await api.product(product.productId);
      const available = detail.instruments.filter((item) => item.exchange === exchange && item.status === 'ACTIVE');
      setInstruments(available);
      if (available[0]) { setInstrumentId(available[0].instrumentId); setSymbol(available[0].symbol); }
    } catch (cause) { setError(cause); }
  };
  const start = async () => {
    setBusy(true); setError(null);
    try {
      const launch = await api.marketAnalysis({ instrumentId, symbol, exchange, intervalSeconds, forecastHorizonSeconds, question, workflowVersionId: workflowVersionId || null }, crypto.randomUUID());
      onOpenRun(launch);
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
        <TextField select label="实盘行情交易所" value={exchange} onChange={(event) => { setExchange(event.target.value); setSelectedProductId(''); setInstruments([]); setInstrumentId(''); setSymbol(''); }} sx={{ minWidth: 180 }}><MenuItem value="GATE">Gate</MenuItem><MenuItem value="BYBIT">Bybit</MenuItem></TextField>
        <Autocomplete sx={{ minWidth: 240, flex: 1 }} options={products} value={products.find((item) => item.productId === selectedProductId) || null} inputValue={productSearch} onInputChange={(_, value) => setProductSearch(value)} onChange={(_, value) => void selectProduct(value)} isOptionEqualToValue={(left, right) => left.productId === right.productId} getOptionLabel={(item) => `${item.displayName} · ${item.baseAsset}/${item.quoteAsset}`} renderInput={(params) => <TextField {...params} label="规范产品" placeholder="搜索产品" />} />
        <TextField select label="交易所产品" value={instrumentId} onChange={(event) => { const value = event.target.value; const instrument = instruments.find((item) => item.instrumentId === value); setInstrumentId(value); setSymbol(instrument?.symbol || ''); }} sx={{ minWidth: 240 }} disabled={instruments.length === 0}><MenuItem value="">请选择</MenuItem>{instruments.map((item) => <MenuItem key={item.instrumentId} value={item.instrumentId}>{item.symbol} · {item.marketType}</MenuItem>)}</TextField>
        <TextField select label="K 线周期" value={intervalSeconds} onChange={(event) => { const next = Number(event.target.value); setIntervalSeconds(next); setForecastHorizonSeconds((current) => Math.max(current, next)); }} sx={{ minWidth: 150 }}>{[[60, '1 分钟'], [300, '5 分钟'], [900, '15 分钟'], [3600, '1 小时'], [14400, '4 小时'], [86400, '1 天']].map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}</TextField>
        <TextField select label="预测期限" value={forecastHorizonSeconds} onChange={(event) => setForecastHorizonSeconds(Number(event.target.value))} sx={{ minWidth: 150 }}>{[[3600, '未来 1 小时'], [14400, '未来 4 小时'], [86400, '未来 1 天'], [259200, '未来 3 天'], [604800, '未来 7 天'], [2592000, '未来 30 天']].filter(([value]) => Number(value) >= intervalSeconds).map(([value, label]) => <MenuItem key={value} value={value}>{label}</MenuItem>)}</TextField>
        <TextField select label="工作流" value={workflowVersionId} onChange={(event) => setWorkflowVersionId(event.target.value)} fullWidth><MenuItem value="">系统自动选择</MenuItem>{definitions.filter((item) => item.publishedVersionId).map((item) => <MenuItem key={item.definitionId} value={item.publishedVersionId!}>{item.name} v{item.publishedVersionNumber}</MenuItem>)}</TextField>
      </Stack>
      <TextField multiline minRows={5} label="分析目标" value={question} onChange={(event) => setQuestion(event.target.value)} inputProps={{ maxLength: 1500 }} />
      {!definitions.some((item) => item.active && item.publishedVersionId) && <Alert severity="warning">当前没有激活的已发布工作流，请在 AI 工作流中发布并激活后运行。</Alert>}
      <Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || !instrumentId || !symbol || !question.trim()} onClick={() => void start()} sx={{ alignSelf: 'flex-end' }}>{busy ? '正在受理' : '开始走势预测'}</Button>
    </Stack></Paper>
  </Stack>;
}
