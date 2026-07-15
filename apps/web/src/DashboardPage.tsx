import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined';
import { Alert, Box, Button, Card, CardContent, Chip, LinearProgress, Paper, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';

import { api } from './api';
import type { AccountsOverview, OperationsOverview, PlatformReadiness, ResearchSummary, TradeAutomationSummary } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle, formatMoney, formatTime, statusColor, statusLabel } from './ui';

export function DashboardPage({ onNavigate }: { onNavigate?: (page: string) => void }) {
  const [data, setData] = useState<{ readiness: PlatformReadiness; operations: OperationsOverview; accounts: AccountsOverview; history: ResearchSummary[]; automations: TradeAutomationSummary[] } | null>(null);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    Promise.all([api.readiness(), api.operations(), api.accounts('DAYS_30'), api.researchHistory(undefined, 8), api.tradeAutomations(8)])
      .then(([readiness, operations, accounts, history, automations]) => setData({ readiness, operations, accounts, history, automations }))
      .catch(setError);
  }, []);
  if (error) return <ErrorBlock error={error} />;
  if (!data) return <LoadingBlock label="正在汇总运行状态" />;
  const running = (data.operations.taskCounts.CLAIMED || 0) + (data.operations.taskCounts.PENDING || 0);
  const failures = (data.operations.taskCounts.FAILED || 0) + data.history.filter((run) => run.status === 'FAILED').length;
  return (
    <Stack spacing={3}>
      {!data.readiness.ready && <Alert severity="error">系统就绪度 {data.readiness.score}%，存在会阻断完整研究闭环的配置或运行状态。</Alert>}
      {failures > 0 && <Alert severity="warning">当前有 {failures} 条失败记录，可在“复核与效果”或“运行报告”查看错误详情。</Alert>}
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(4, minmax(0, 1fr))' }, gap: 1.5 }}>
        <Metric title="系统就绪度" value={`${data.readiness.score}%`} detail={data.readiness.ready ? '研究闭环可运行' : '存在阻断项'} icon={<AutorenewIcon />} />
        <Metric title="账户权益" value={formatMoney(data.accounts.totalEquity, data.accounts.currency)} detail={`近 30 天已实现 ${formatMoney(data.accounts.totalRealizedPnl, data.accounts.currency)}`} icon={<AccountBalanceWalletOutlinedIcon />} />
        <Metric title="运行队列" value={String(running)} detail={`${data.operations.workers.filter((worker) => worker.status === 'RUNNING').length} 个 Worker 在线`} icon={<AutorenewIcon />} />
        <Metric title="模拟执行" value={String(data.automations.filter((item) => item.status === 'SUBMITTED').length)} detail="仅 Gate TestNet / Bybit Demo" icon={<SmartToyOutlinedIcon />} />
      </Box>
      <Box><SectionTitle title="快速操作" /><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}><Button variant="contained" onClick={() => onNavigate?.('research')}>发起即时研究</Button><Button onClick={() => onNavigate?.('autonomous')}>自动研究</Button><Button onClick={() => onNavigate?.('catalog')}>产品与自选</Button><Button onClick={() => onNavigate?.('trading')}>模拟账户</Button><Button onClick={() => onNavigate?.('network')}>网络诊断</Button></Stack></Box>
      <Box><SectionTitle title="系统就绪度" /><Paper variant="outlined" sx={{ overflow: 'hidden' }}>{data.readiness.checks.map((check, index) => <Stack key={check.code} direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }} sx={{ px: 2, py: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{check.title}</Typography><Typography variant="body2" color="text.secondary">{check.detail}</Typography></Box><Chip size="small" color={statusColor(check.status)} label={statusLabel(check.status)} /><Button size="small" onClick={() => onNavigate?.(check.actionPage)}>处理</Button></Stack>)}</Paper></Box>
      {data.readiness.latestResearch && <Box><SectionTitle title="最新结论" /><Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between"><Box><Typography fontWeight={800}>{data.readiness.latestResearch.requestSummary}</Typography><Typography sx={{ mt: .75 }}>{data.readiness.latestResearch.conclusion}</Typography><Typography variant="caption" color="text.secondary">{data.readiness.latestResearch.runId} · {formatTime(data.readiness.latestResearch.completedAt)}</Typography></Box><Chip size="small" color={statusColor(data.readiness.latestResearch.status)} label={statusLabel(data.readiness.latestResearch.status)} sx={{ alignSelf: 'flex-start' }} /></Stack></Paper></Box>}
      <Box>
        <SectionTitle title="研究进度" />
        <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
          {data.history.map((run, index) => (
            <Stack key={run.runId} direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} sx={{ px: 2, py: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}>
              <Box sx={{ minWidth: 0, flex: 1 }}><Typography fontWeight={700} noWrap>{run.requestSummary}</Typography><Typography variant="caption" color="text.secondary">{run.runId} · {formatTime(run.acceptedAt)}</Typography></Box>
              <Typography variant="body2" color="text.secondary">{run.inputTokens + run.outputTokens} tokens · ${Number(run.costUsd).toFixed(4)}</Typography>
              <Chip size="small" color={statusColor(run.status)} label={statusLabel(run.status)} />
            </Stack>
          ))}
          {data.history.length === 0 && <Box sx={{ p: 3, color: 'text.secondary' }}>暂无研究记录</Box>}
        </Paper>
      </Box>
      <Box>
        <SectionTitle title="常驻调度" />
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5 }}>
          {data.operations.schedules.map((schedule) => (
            <Box key={schedule.scheduleId}>
              <Paper variant="outlined" sx={{ p: 2 }}>
                <Stack direction="row" justifyContent="space-between" spacing={2}><Typography fontWeight={700}>{schedule.displayName}</Typography><Chip size="small" color={schedule.enabled ? 'success' : 'default'} label={schedule.enabled ? '已启用' : '已停用'} /></Stack>
                <LinearProgress variant="determinate" value={schedule.enabled ? 100 : 0} color={schedule.enabled ? 'success' : 'inherit'} sx={{ my: 1.5 }} />
                <Typography variant="body2" color="text.secondary">每 {formatInterval(schedule.intervalSeconds)} · 下次 {formatTime(schedule.nextRunAt)}</Typography>
              </Paper>
            </Box>
          ))}
        </Box>
      </Box>
    </Stack>
  );
}

function Metric({ title, value, detail, icon }: { title: string; value: string; detail: string; icon: ReactNode }) {
  return <Card><CardContent><Stack direction="row" justifyContent="space-between" spacing={2}><Box><Typography variant="caption" color="text.secondary">{title}</Typography><Typography variant="h2" sx={{ mt: .5 }}>{value}</Typography><Typography variant="caption" color="text.secondary">{detail}</Typography></Box><Box sx={{ color: 'primary.main' }}>{icon}</Box></Stack></CardContent></Card>;
}

function formatInterval(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600} 小时`;
  if (seconds % 60 === 0) return `${seconds / 60} 分钟`;
  return `${seconds} 秒`;
}
