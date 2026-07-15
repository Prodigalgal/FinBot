import RefreshIcon from '@mui/icons-material/Refresh';
import { Accordion, AccordionDetails, AccordionSummary, Box, Button, Chip, Paper, Stack, TextField, Typography } from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type { OperationsReport, ResearchForecast } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function ReportsPage() {
  const [from, setFrom] = useState(localDateTime(new Date(Date.now() - 30 * 86400000)));
  const [to, setTo] = useState(localDateTime(new Date()));
  const [report, setReport] = useState<OperationsReport | null>(null);
  const [forecasts, setForecasts] = useState<ResearchForecast[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const refresh = useCallback(async () => {
    setBusy(true); setError(null);
    try { const [nextReport, nextForecasts] = await Promise.all([api.reports(new Date(from).toISOString(), new Date(to).toISOString()), api.researchForecasts(200)]); setReport(nextReport); setForecasts(nextForecasts); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  }, [from, to]);
  useEffect(() => { void refresh(); }, []);
  const forecastMetrics = useMemo(() => {
    const start = new Date(from).getTime(); const end = new Date(to).getTime();
    const scoped = forecasts.filter((item) => { const issued = new Date(item.issuedAt).getTime(); return issued >= start && issued < end; });
    const directional = scoped.filter((item) => item.status === 'EVALUATED' && item.direction !== 'UNCERTAIN');
    const rangeEvaluated = directional.filter((item) => item.rangeHit !== null);
    return { scoped, directional, directionAccuracy: directional.length ? directional.filter((item) => item.directionCorrect).length / directional.length : null, rangeAccuracy: rangeEvaluated.length ? rangeEvaluated.filter((item) => item.rangeHit).length / rangeEvaluated.length : null };
  }, [forecasts, from, to]);
  const preset = (days: number) => { const end = new Date(); setTo(localDateTime(end)); setFrom(localDateTime(new Date(end.getTime() - days * 86400000))); };
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.25} alignItems={{ lg: 'center' }}><Stack direction="row" spacing={.75}>{[1, 7, 30, 90].map((days) => <Button key={days} size="small" variant={days === 30 ? 'outlined' : 'text'} onClick={() => preset(days)}>{days === 1 ? '24 小时' : `${days} 天`}</Button>)}</Stack><TextField type="datetime-local" label="开始" value={from} onChange={(event) => setFrom(event.target.value)} InputLabelProps={{ shrink: true }} /><TextField type="datetime-local" label="结束" value={to} onChange={(event) => setTo(event.target.value)} InputLabelProps={{ shrink: true }} /><Box sx={{ flex: 1 }} /><Button variant="contained" startIcon={<RefreshIcon />} disabled={busy} onClick={() => void refresh()}>生成报告</Button></Stack></Paper>
    {busy && !report && <LoadingBlock label="正在汇总永久数据仓库" />}
    {report && <ForecastReportSection metrics={forecastMetrics} />}
    {report && <><Typography variant="caption" color="text.secondary">区间 {formatTime(report.fromInclusive)} 至 {formatTime(report.toExclusive)} · 生成于 {formatTime(report.generatedAt)}</Typography>{report.sections.map((section) => <Box key={section.code}><SectionTitle title={section.title} /><Paper variant="outlined" sx={{ overflow: 'hidden' }}><Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: `repeat(${Math.min(5, Math.max(2, section.metrics.length))}, minmax(0, 1fr))` }, borderBottom: '1px solid', borderColor: 'divider' }}>{section.metrics.map((metric) => <Box key={metric.label} sx={{ p: 1.75, borderRight: '1px solid', borderColor: 'divider' }}><Typography variant="caption" color="text.secondary">{metric.label}</Typography><Typography variant="h2">{metric.value} <Typography component="span" variant="caption">{metric.unit}</Typography></Typography></Box>)}</Box>{section.entries.map((entry, index) => <Stack key={entry.referenceId} direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ px: 2, py: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Box sx={{ flex: 1, minWidth: 0 }}><Typography fontWeight={700}>{entry.title}</Typography><Typography variant="body2" color="text.secondary">{entry.summary}</Typography><Typography variant="caption" color="text.secondary">{entry.referenceId} · {formatTime(entry.occurredAt)}</Typography></Box><Chip size="small" color={statusColor(entry.status)} label={statusLabel(entry.status)} sx={{ alignSelf: 'flex-start' }} /></Stack>)}{section.entries.length === 0 && <EmptyBlock>该区间没有异常记录</EmptyBlock>}<Accordion disableGutters elevation={0} square><AccordionSummary expandIcon={<ExpandMoreIcon />}><Typography variant="body2">审计数据</Typography></AccordionSummary><AccordionDetails><Typography component="pre" variant="caption" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', m: 0 }}>{JSON.stringify(section, null, 2)}</Typography></AccordionDetails></Accordion></Paper></Box>)}</>}
  </Stack>;
}

function localDateTime(date: Date): string { const adjusted = new Date(date.getTime() - date.getTimezoneOffset() * 60000); return adjusted.toISOString().slice(0, 16); }

interface ForecastMetrics {
  scoped: ResearchForecast[];
  directional: ResearchForecast[];
  directionAccuracy: number | null;
  rangeAccuracy: number | null;
}

function ForecastReportSection({ metrics }: { metrics: ForecastMetrics }) {
  const summaries: Array<{ label: string; value: string | number }> = [
    { label: '预测总数', value: metrics.scoped.length },
    { label: '已到期验证', value: metrics.scoped.filter((item) => item.status === 'EVALUATED').length },
    { label: '方向准确率', value: metrics.directionAccuracy === null ? '-' : `${(metrics.directionAccuracy * 100).toFixed(1)}%` },
    { label: '区间命中率', value: metrics.rangeAccuracy === null ? '-' : `${(metrics.rangeAccuracy * 100).toFixed(1)}%` },
    { label: '主动回避', value: metrics.scoped.filter((item) => item.direction === 'UNCERTAIN').length },
  ];
  const directionNames: Record<string, string> = { UP: '上涨', DOWN: '下跌', SIDEWAYS: '震荡', UNCERTAIN: '不确定' };
  return <Box>
    <SectionTitle title="走势预测效果" />
    <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(5, minmax(0, 1fr))' } }}>
        {summaries.map((summary, index) => <Box key={summary.label} sx={{ p: 1.75, borderLeft: index ? '1px solid' : 0, borderColor: 'divider' }}><Typography variant="caption" color="text.secondary">{summary.label}</Typography><Typography variant="h5" fontWeight={800}>{summary.value}</Typography></Box>)}
      </Box>
      {metrics.scoped.slice(0, 10).map((item) => <Stack key={item.forecastId} direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ px: 2, py: 1.5, borderTop: '1px solid', borderColor: 'divider' }}>
        <Box sx={{ flex: 1 }}><Typography fontWeight={700}>{item.exchange} · {item.symbol} · {directionNames[item.direction]}</Typography><Typography variant="body2" color="text.secondary">{item.thesis}</Typography><Typography variant="caption" color="text.secondary">目标 {formatTime(item.targetAt)} · 参考价 {item.marketReferencePrice} · 到期价 {item.actualPrice ?? '-'}</Typography></Box>
        <Chip size="small" color={item.status !== 'EVALUATED' ? 'info' : item.directionCorrect === true ? 'success' : item.directionCorrect === false ? 'error' : 'default'} label={item.status !== 'EVALUATED' ? '待验证' : item.directionCorrect === null ? '主动回避' : item.directionCorrect ? '方向命中' : '方向未命中'} sx={{ alignSelf: 'flex-start' }} />
      </Stack>)}
      {metrics.scoped.length === 0 && <EmptyBlock>该区间尚无结构化走势预测</EmptyBlock>}
    </Paper>
  </Box>;
}
