import AccessTimeIcon from '@mui/icons-material/AccessTime';
import { Box, Chip, LinearProgress, Paper, Stack, Typography } from '@mui/material';

import type { ResearchForecast } from './types';
import { formatTime } from './ui';

export function ForecastPanel({ forecast }: { forecast: ResearchForecast }) {
  const evaluated = forecast.status === 'EVALUATED';
  const environmentLabel = forecast.environment === 'LIVE' ? '实盘' : '模拟盘';
  const direction = ({ UP: '预期上涨', DOWN: '预期下跌', SIDEWAYS: '预期震荡', UNCERTAIN: '证据不足' } as Record<string, string>)[forecast.direction] || forecast.direction;
  return <Paper variant="outlined" sx={{ p: 2, borderLeft: '4px solid', borderLeftColor: evaluated ? (forecast.directionCorrect ? 'success.main' : 'error.main') : 'primary.main' }}>
    <Stack spacing={1.25}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
        <Box><Typography variant="caption" color="text.secondary">{environmentLabel}结构化走势预测</Typography><Typography variant="h6" fontWeight={800}>{forecast.exchange} · {forecast.environment} · {forecast.symbol} · {direction}</Typography></Box>
        <Stack direction="row" spacing={0.75} alignItems="center"><Chip size="small" label={`置信度 ${(Number(forecast.confidence) * 100).toFixed(0)}%`} /><Chip size="small" color={evaluated ? (forecast.directionCorrect === true ? 'success' : forecast.directionCorrect === false ? 'error' : 'default') : 'info'} label={evaluated ? (forecast.directionCorrect === null ? '主动回避方向' : forecast.directionCorrect ? '方向命中' : '方向未命中') : '等待到期验证'} /></Stack>
      </Stack>
      <Typography>{forecast.thesis}</Typography>
      {forecast.directionProbabilities && <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 1.25 }}>
        <ProbabilityMetric label="上涨" value={forecast.directionProbabilities.up} color="success" />
        <ProbabilityMetric label="震荡" value={forecast.directionProbabilities.sideways} color="warning" />
        <ProbabilityMetric label="下跌" value={forecast.directionProbabilities.down} color="error" />
      </Box>}
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}><Metric label={`${environmentLabel}参考价`} value={forecast.marketReferencePrice} /><Metric label="预期区间" value={forecast.expectedLow === null ? '-' : `${forecast.expectedLow} - ${forecast.expectedHigh}`} /><Metric label="失效价" value={forecast.invalidationPrice} /><Metric label={`到期${environmentLabel}价`} value={forecast.actualPrice} /></Stack>
      <Stack direction="row" spacing={0.75} alignItems="center"><AccessTimeIcon fontSize="small" color="action" /><Typography variant="caption" color="text.secondary">发布 {formatTime(forecast.issuedAt)} · 目标时间 {formatTime(forecast.targetAt)} · K 线 {duration(forecast.intervalSeconds)} · 预测 {duration(forecast.horizonSeconds)}</Typography></Stack>
      {evaluated && <Typography variant="body2" color="text.secondary">实际涨跌幅 {forecast.actualReturn === null ? '-' : `${(Number(forecast.actualReturn) * 100).toFixed(2)}%`} · 区间{forecast.rangeHit ? '命中' : '未命中'} · {forecast.shadowPnlUsdt === null ? '无方向影子仓位' : `${Number(forecast.shadowNotionalUsdt).toFixed(2)} USDT 影子本金盈亏 ${signedMoney(forecast.shadowPnlUsdt)}`} · {formatTime(forecast.evaluatedAt)}</Typography>}
      {forecast.evidenceReferences.length > 0 && <Typography variant="caption" color="text.secondary">证据引用：{forecast.evidenceReferences.join('、')}</Typography>}
    </Stack>
  </Paper>;
}

function Metric({ label, value }: { label: string; value: string | number | null }) { return <Box sx={{ flex: 1 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography fontWeight={700}>{value ?? '-'}</Typography></Box>; }
function ProbabilityMetric({ label, value, color }: { label: string; value: number; color: 'success' | 'warning' | 'error' }) { const percent = Math.max(0, Math.min(100, Number(value) * 100)); return <Box><Stack direction="row" justifyContent="space-between"><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="caption" fontWeight={700}>{percent.toFixed(1)}%</Typography></Stack><LinearProgress variant="determinate" value={percent} color={color} sx={{ mt: 0.5, height: 6, borderRadius: 1 }} /></Box>; }
function duration(seconds: number): string { if (seconds % 86400 === 0) return `${seconds / 86400} 天`; if (seconds % 3600 === 0) return `${seconds / 3600} 小时`; return `${seconds} 秒`; }
function signedMoney(value: number): string { const amount = Number(value); return `${amount >= 0 ? '+' : ''}${amount.toFixed(2)} USDT`; }
