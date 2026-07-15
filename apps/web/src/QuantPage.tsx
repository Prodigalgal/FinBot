import CalculateOutlinedIcon from '@mui/icons-material/CalculateOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Paper, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import type { QuantWorkspace, TradeRiskPreview } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatMoney, formatTime, statusColor, statusLabel } from './ui';

interface PreviewForm {
  instrumentId: string; accountId: string; exchange: string; environment: string; symbol: string;
  action: string; confidence: number; entryPrice: number; targetPrice: number; stopPrice: number;
  currentPrice: number; contractSize: number; quantityStep: number; minimumQuantity: number;
  venueMaximumLeverage: number; openPositionCount: number; executionEnabled: boolean;
}

const initialForm: PreviewForm = {
  instrumentId: 'instrument_bybit_btcusdt', accountId: 'account_bybit_demo_default', exchange: 'BYBIT',
  environment: 'DEMO', symbol: 'BTCUSDT', action: 'BUY', confidence: .8, entryPrice: 60000,
  targetPrice: 63000, stopPrice: 58500, currentPrice: 60000, contractSize: 1,
  quantityStep: .001, minimumQuantity: .001, venueMaximumLeverage: 100, openPositionCount: 0,
  executionEnabled: true,
};

export function QuantPage() {
  const [form, setForm] = useState(initialForm);
  const [preview, setPreview] = useState<TradeRiskPreview | null>(null);
  const [workspace, setWorkspace] = useState<QuantWorkspace | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const refresh = useCallback(() => api.quantRuns(100).then(setWorkspace).catch(setError), []);
  useEffect(() => { void refresh(); }, [refresh]);
  const calculate = async () => {
    setBusy(true); setError(null);
    try { setPreview(await api.tradeRiskPreview(form as unknown as Record<string, unknown>)); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const setNumber = (key: keyof PreviewForm, value: string) => setForm((current) => ({ ...current, [key]: Number(value) }));
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}
    <Box><SectionTitle title="仓位与风控预览" /><Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25} flexWrap={{ md: 'wrap' }} useFlexGap>
        <TextField label="标的" value={form.symbol} onChange={(event) => setForm({ ...form, symbol: event.target.value.toUpperCase() })} sx={{ width: { md: 160 } }} />
        <TextField select label="交易所" value={form.exchange} onChange={(event) => { const exchange = event.target.value; setForm({ ...form, exchange, environment: exchange === 'GATE' ? 'TESTNET' : 'DEMO' }); }} sx={{ width: { md: 140 } }}><MenuItem value="GATE">Gate</MenuItem><MenuItem value="BYBIT">Bybit</MenuItem></TextField>
        <TextField select label="方向" value={form.action} onChange={(event) => setForm({ ...form, action: event.target.value })} sx={{ width: { md: 120 } }}><MenuItem value="BUY">买入</MenuItem><MenuItem value="SELL">卖出</MenuItem></TextField>
        <NumberField label="置信度" value={form.confidence} update={(value) => setNumber('confidence', value)} />
        <NumberField label="最新价" value={form.currentPrice} update={(value) => setNumber('currentPrice', value)} />
        <NumberField label="入场参考" value={form.entryPrice} update={(value) => setNumber('entryPrice', value)} />
        <NumberField label="止盈价" value={form.targetPrice} update={(value) => setNumber('targetPrice', value)} />
        <NumberField label="止损价" value={form.stopPrice} update={(value) => setNumber('stopPrice', value)} />
        <NumberField label="合约单位" value={form.contractSize} update={(value) => setNumber('contractSize', value)} />
        <NumberField label="数量步长" value={form.quantityStep} update={(value) => setNumber('quantityStep', value)} />
        <NumberField label="最小数量" value={form.minimumQuantity} update={(value) => setNumber('minimumQuantity', value)} />
        <NumberField label="合约最大杠杆" value={form.venueMaximumLeverage} update={(value) => setNumber('venueMaximumLeverage', value)} />
      </Stack>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1}>
        <FormControlLabel control={<Switch checked={form.executionEnabled} onChange={(event) => setForm({ ...form, executionEnabled: event.target.checked })} />} label={form.executionEnabled ? '按可模拟执行产品计算' : '按仅研究产品预估'} />
        <Button variant="contained" startIcon={<CalculateOutlinedIcon />} disabled={busy} onClick={() => void calculate()}>{busy ? '计算中' : '执行无副作用预览'}</Button>
      </Stack>
    </Stack></Paper></Box>
    {preview && <Paper variant="outlined" sx={{ p: 2, borderColor: preview.status === 'BLOCKED' ? 'warning.main' : 'success.main' }}><Stack spacing={1.5}><Stack direction="row" justifyContent="space-between"><Typography fontWeight={800}>{preview.mode === 'EXECUTION' ? '模拟执行风控结果' : '内部预估结果'}</Typography><Chip color={statusColor(preview.status)} label={statusLabel(preview.status)} /></Stack>{preview.reasons.map((reason) => <Alert key={reason} severity={preview.status === 'BLOCKED' ? 'warning' : 'info'}>{reason}</Alert>)}<Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}><Metric label="数量" value={preview.quantity?.toString() || '-'} /><Metric label="名义价值" value={formatMoney(preview.notionalUsdt)} /><Metric label="实际杠杆" value={preview.leverage === null ? '-' : `${preview.leverage}x`} /><Metric label="初始保证金" value={formatMoney(preview.initialMarginUsdt)} /><Metric label="最大/预估亏损" value={formatMoney(preview.estimatedMaximumLossUsdt)} /><Metric label="预估盈利" value={formatMoney(preview.estimatedProfitUsdt)} /><Metric label="盈亏比" value={preview.riskRewardRatio?.toFixed(3) || '-'} /><Metric label="估算强平价" value={preview.approximateLiquidationPrice?.toString() || '-'} /></Box><Typography variant="caption" color="text.secondary">策略 {preview.policy.version} · 期望 {preview.policy.preferredLeverage}x · 风控上限 {preview.policy.maximumLeverage}x · 合约上限仅作为硬边界</Typography></Stack></Paper>}
    <Box><SectionTitle title="量化研究运行" action={<Button size="small" startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新</Button>} />{workspace ? <Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>时间</TableCell><TableCell>研究问题</TableCell><TableCell>策略</TableCell><TableCell align="right">观测数</TableCell><TableCell>指标</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{workspace.runs.map((run) => <TableRow key={run.researchRunId}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(run.requestedAt)}</TableCell><TableCell><Typography variant="body2" fontWeight={700}>{run.requestSummary}</Typography><Typography variant="caption" color="text.secondary">{run.workflowRunId}</Typography></TableCell><TableCell>{run.strategyId}<br /><Typography variant="caption">{run.strategyVersion}</Typography></TableCell><TableCell align="right">{run.observationCount}</TableCell><TableCell><Typography component="pre" variant="caption" sx={{ m: 0, whiteSpace: 'pre-wrap', maxWidth: 360 }}>{prettyMetrics(run.metricsJson)}</Typography></TableCell><TableCell><Chip size="small" color={statusColor(run.status)} label={statusLabel(run.status)} />{run.errorMessage && <Typography variant="caption" color="error" display="block">{run.errorMessage}</Typography>}</TableCell></TableRow>)}</TableBody></Table>{workspace.runs.length === 0 && <EmptyBlock>尚无量化运行</EmptyBlock>}</Paper> : <LoadingBlock />}</Box>
  </Stack>;
}

function NumberField({ label, value, update }: { label: string; value: number; update: (value: string) => void }) { return <TextField label={label} type="number" value={value} onChange={(event) => update(event.target.value)} sx={{ width: { md: 150 } }} />; }
function Metric({ label, value }: { label: string; value: string }) { return <Box><Typography variant="caption" color="text.secondary">{label}</Typography><Typography fontWeight={800}>{value}</Typography></Box>; }
function prettyMetrics(value: string): string { try { const parsed = JSON.parse(value) as Record<string, { value?: unknown; unit?: unknown }>; return Object.entries(parsed).map(([name, metric]) => `${name}: ${String(metric.value ?? '-')} ${String(metric.unit ?? '')}`).join('\n') || '-'; } catch { return value || '-'; } }
