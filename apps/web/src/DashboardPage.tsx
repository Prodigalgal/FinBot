import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import Inventory2OutlinedIcon from '@mui/icons-material/Inventory2Outlined';
import LanOutlinedIcon from '@mui/icons-material/LanOutlined';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import { Alert, Box, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import type { AccountsOverview, OperationsOverview, PlatformReadiness, ResearchSummary, TradeAutomationSummary } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, MetricStrip, SectionTitle, StatusBadge, CopyableText, formatMoney, formatTime } from './ui';

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
  const historicalFailures = data.operations.taskCounts.FAILED || 0;
  const recentFailures = data.history.filter((run) => run.status === 'FAILED').length;
  return (
    <Stack spacing={2.25}>
      {!data.readiness.ready && <Alert severity="error">系统就绪度 {data.readiness.score}%，存在会阻断完整研究闭环的配置或运行状态。</Alert>}
      {historicalFailures > 0 && <Alert severity={recentFailures > 0 ? 'warning' : 'info'} action={<Button color="inherit" size="small" onClick={() => onNavigate?.('review')}>查看复核</Button>}>历史累计 {historicalFailures} 条失败任务；最近 {data.history.length} 次研究中 {recentFailures} 次失败。该统计不代表当前 Worker 离线。</Alert>}
      <MetricStrip items={[
        { label: '系统就绪度', value: `${data.readiness.score}%`, detail: data.readiness.ready ? '研究闭环可运行' : '存在阻断项', tone: data.readiness.ready ? 'success' : 'error' },
        { label: '账户权益', value: formatMoney(data.accounts.totalEquity, data.accounts.currency), detail: `近 30 天已实现 ${formatMoney(data.accounts.totalRealizedPnl, data.accounts.currency)}` },
        { label: '运行队列', value: String(running), detail: `${data.operations.workers.filter((worker) => worker.status === 'RUNNING').length} 个 Worker 在线` },
        { label: '模拟执行', value: String(data.automations.filter((item) => item.status === 'SUBMITTED').length), detail: '仅 Gate TestNet / Bybit Demo' },
      ]} />
      <Paper variant="outlined" sx={{ px: 2, py: 1.25, boxShadow: '0 1px 3px rgba(15, 23, 42, 0.04)' }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} alignItems={{ sm: 'center' }} spacing={1.25} useFlexGap flexWrap="wrap">
          <Typography variant="caption" sx={{ fontWeight: 700, color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.04em', px: 0.5 }}>
            快捷入口
          </Typography>
          <Button variant="contained" size="small" startIcon={<PlayCircleOutlineIcon />} onClick={() => onNavigate?.('research')}>发起即时研究</Button>
          <Button variant="outlined" size="small" startIcon={<AutorenewIcon />} onClick={() => onNavigate?.('autonomous')}>自动研究</Button>
          <Button variant="outlined" size="small" startIcon={<Inventory2OutlinedIcon />} onClick={() => onNavigate?.('catalog')}>产品与自选</Button>
          <Button variant="outlined" size="small" startIcon={<AccountBalanceWalletOutlinedIcon />} onClick={() => onNavigate?.('trading')}>模拟账户</Button>
          <Button variant="outlined" size="small" startIcon={<LanOutlinedIcon />} onClick={() => onNavigate?.('network')}>网络诊断</Button>
        </Stack>
      </Paper>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: 'minmax(0, 1.5fr) minmax(320px, 0.7fr)' }, gap: 2 }}>
        <Box>
          <SectionTitle title="系统就绪度" subtitle="自动化研究、执行与风控基础设施检查" />
          <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
            {data.readiness.checks.map((check, index) => (
              <Stack key={check.code} direction={{ xs: 'column', sm: 'row' }} spacing={1.25} alignItems={{ sm: 'center' }} sx={{ px: 2, py: 1.35, borderTop: index ? '1px solid' : 0, borderColor: 'divider', transition: 'background-color 0.12s ease', '&:hover': { bgcolor: 'action.hover' } }}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={700}>{check.title}</Typography>
                  <Typography variant="caption" color="text.secondary">{check.detail}</Typography>
                </Box>
                <StatusBadge status={check.status} />
                <Button size="small" variant="outlined" onClick={() => onNavigate?.(check.actionPage)}>处理</Button>
              </Stack>
            ))}
          </Paper>
        </Box>
        <Box>
          <SectionTitle title="最新结论" subtitle="最近一次多 Agent 辩论终局" />
          {data.readiness.latestResearch ? (
            <Paper variant="outlined" sx={{ p: 2, height: 'calc(100% - 46px)', display: 'flex', flexDirection: 'column' }}>
              <Stack spacing={1.5} sx={{ height: '100%' }}>
                <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
                  <Typography variant="subtitle1" fontWeight={700}>{data.readiness.latestResearch.requestSummary}</Typography>
                  <StatusBadge status={data.readiness.latestResearch.status} />
                </Stack>
                <Typography variant="body2" sx={{ flex: 1, color: 'text.secondary', lineHeight: 1.6 }}>
                  {data.readiness.latestResearch.conclusion}
                </Typography>
                <Box sx={{ pt: 1, borderTop: '1px solid', borderColor: 'divider' }}>
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <CopyableText text={data.readiness.latestResearch.runId} display={data.readiness.latestResearch.runId.slice(0, 16) + '...'} />
                    <Typography variant="caption" color="text.secondary">{formatTime(data.readiness.latestResearch.completedAt)}</Typography>
                  </Stack>
                </Box>
              </Stack>
            </Paper>
          ) : (
            <Paper variant="outlined"><EmptyBlock>尚无已完成研究结论</EmptyBlock></Paper>
          )}
        </Box>
      </Box>
      <Box>
        <SectionTitle title="最近研究" subtitle="即时与自动流水线记录" action={<Button size="small" variant="outlined" onClick={() => onNavigate?.('review')}>查看全部</Button>} />
        <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>研究问题 / 编号</TableCell>
                <TableCell>受理时间</TableCell>
                <TableCell align="right">Tokens</TableCell>
                <TableCell align="right">成本</TableCell>
                <TableCell>状态</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.history.map((run) => (
                <TableRow key={run.runId} hover>
                  <TableCell sx={{ minWidth: 240 }}>
                    <Typography variant="body2" fontWeight={700}>{run.requestSummary}</Typography>
                    <Box sx={{ mt: 0.25 }}>
                      <CopyableText text={run.runId} display={run.runId} />
                    </Box>
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(run.acceptedAt)}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600 }}>{(run.inputTokens + run.outputTokens).toLocaleString()}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 600, color: 'text.primary' }}>${Number(run.costUsd).toFixed(4)}</TableCell>
                  <TableCell><StatusBadge status={run.status} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {data.history.length === 0 && <EmptyBlock>暂无研究记录</EmptyBlock>}
        </Paper>
      </Box>
      <Box>
        <SectionTitle title="常驻调度" subtitle="后台定时工作任务与租约状态" />
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5 }}>
          {data.operations.schedules.map((schedule) => (
            <Box key={schedule.scheduleId}>
              <Paper variant="outlined" sx={{ p: 2, transition: 'all 0.15s ease', '&:hover': { borderColor: 'primary.light' } }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2}>
                  <Typography fontWeight={700} variant="body2">{schedule.displayName}</Typography>
                  <StatusBadge status={schedule.enabled ? 'ACTIVE' : 'DISABLED'} label={schedule.enabled ? '已启用' : '已停用'} size="small" />
                </Stack>
                <LinearProgress variant="determinate" value={schedule.enabled ? 100 : 0} color={schedule.enabled ? 'success' : 'inherit'} sx={{ my: 1.5, height: 4, borderRadius: 2 }} />
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  每 {formatInterval(schedule.intervalSeconds)} · 下次 {formatTime(schedule.nextRunAt)}
                </Typography>
              </Paper>
            </Box>
          ))}
        </Box>
      </Box>
    </Stack>
  );
}

function formatInterval(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600} 小时`;
  if (seconds % 60 === 0) return `${seconds / 60} 分钟`;
  return `${seconds} 秒`;
}
