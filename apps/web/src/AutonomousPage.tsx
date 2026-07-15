import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, FormControlLabel, LinearProgress, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import type { AutonomousStatus, OperationsOverview } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function AutonomousPage() {
  const [status, setStatus] = useState<AutonomousStatus | null>(null);
  const [operations, setOperations] = useState<OperationsOverview | null>(null);
  const [summary, setSummary] = useState('执行自动产品研究闭环，筛选当前最值得深入分析的产品');
  const [interval, setIntervalValue] = useState(3600);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');

  const refresh = useCallback(async () => {
    try {
      const [nextStatus, nextOperations] = await Promise.all([api.autonomous(), api.operations()]);
      setStatus(nextStatus);
      setOperations(nextOperations);
      if (nextStatus.schedule) setIntervalValue(nextStatus.schedule.intervalSeconds);
    } catch (cause) { setError(cause); }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), 10000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const trigger = async () => {
    setBusy(true); setError(null); setMessage('');
    try {
      const task = await api.triggerAutonomous(summary, crypto.randomUUID());
      setMessage(`研究任务 ${task.taskId} 已进入持久化队列`);
      await refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };

  const update = async (enabled: boolean, intervalSeconds: number) => {
    if (!status?.schedule) return;
    setBusy(true); setError(null); setMessage('');
    try {
      await api.updateSchedule(status.schedule.scheduleId, { enabled, intervalSeconds, expectedVersion: status.schedule.version });
      setMessage('自动研究调度已更新');
      await refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };

  if (!status || !operations) return <LoadingBlock label="正在读取自动研究状态" />;
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    {message && <Alert severity="success">{message}</Alert>}
    {!status.workerOnline && <Alert severity="error">常驻 Worker 当前没有新鲜心跳，排队任务不会被处理。</Alert>}
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}>
      <StatusMetric label="自动循环" value={status.enabled ? '已启用' : '已停用'} status={status.enabled ? 'READY' : 'WARNING'} />
      <StatusMetric label="Worker" value={status.workerOnline ? '在线' : '离线'} status={status.workerOnline ? 'READY' : 'FAILED'} />
      <StatusMetric label="当前任务" value={status.activeTask ? statusLabel(status.activeTask.status) : '空闲'} status={status.activeTask?.status || 'COMPLETED'} />
      <StatusMetric label="下次运行" value={formatTime(status.schedule?.nextRunAt)} status={status.enabled ? 'READY' : 'WARNING'} />
    </Box>
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}>
        <TextField fullWidth label="本轮研究目标" value={summary} onChange={(event) => setSummary(event.target.value)} inputProps={{ maxLength: 1000 }} />
        <TextField label="间隔（秒）" type="number" value={interval} onChange={(event) => setIntervalValue(Number(event.target.value))} inputProps={{ min: 10, max: 2592000 }} sx={{ width: { lg: 160 } }} />
        <FormControlLabel control={<Switch checked={status.enabled} disabled={busy} onChange={(event) => void update(event.target.checked, interval)} />} label={status.enabled ? '已启用' : '已停用'} />
        <Button variant="outlined" disabled={busy || interval < 10} onClick={() => void update(status.enabled, interval)}>保存调度</Button>
        <Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || !summary.trim()} onClick={() => void trigger()}>立即运行</Button>
      </Stack>
    </Paper>
    {status.activeTask && <Box><SectionTitle title="当前进度" action={<Button size="small" startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新</Button>} /><Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}><Stack direction="row" justifyContent="space-between" spacing={2}><Box><Typography fontWeight={700}>{status.activeTask.payloadSummary}</Typography><Typography variant="caption" color="text.secondary">{status.activeTask.taskId} · 尝试 {status.activeTask.attemptCount}/{status.activeTask.maximumAttempts}</Typography></Box><Chip size="small" color={statusColor(status.activeTask.status)} label={statusLabel(status.activeTask.status)} /></Stack><LinearProgress variant={status.activeTask.status === 'CLAIMED' ? 'indeterminate' : 'determinate'} value={status.activeTask.status === 'PENDING' ? 10 : 60} />{status.activeTask.errorMessage && <Alert severity="error">{status.activeTask.errorCode}: {status.activeTask.errorMessage}</Alert>}</Stack></Paper></Box>}
    <Box><SectionTitle title="最近结论" action={status.latestRun ? <Button href="#review">查看完整证据与辩论</Button> : undefined} /><Paper variant="outlined" sx={{ p: 2 }}>{status.latestRun ? <Stack spacing={1}><Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}><Typography fontWeight={700}>{status.latestRun.requestSummary}</Typography><Chip size="small" color={statusColor(status.latestRun.status)} label={statusLabel(status.latestRun.status)} /></Stack><Typography variant="body2">{status.latestConclusion || (status.latestRun.status === 'COMPLETED' ? '本轮没有形成可审计的主席结论。' : '研究仍在进行，结论尚未生成。')}</Typography><Typography variant="caption" color="text.secondary">{status.latestRun.runId} · {formatTime(status.latestRun.completedAt || status.latestRun.acceptedAt)}</Typography></Stack> : <Typography color="text.secondary">尚无自动研究记录</Typography>}</Paper></Box>
    <Box><SectionTitle title="关联调度" /><Stack spacing={1}>{operations.schedules.filter((item) => item.scheduleId !== status.schedule?.scheduleId).map((schedule) => <Paper key={schedule.scheduleId} variant="outlined" sx={{ px: 2, py: 1.5 }}><Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}><Box><Typography fontWeight={700}>{schedule.displayName}</Typography><Typography variant="caption" color="text.secondary">每 {schedule.intervalSeconds} 秒 · 上次 {formatTime(schedule.lastScheduledAt)}</Typography></Box><Chip size="small" color={schedule.enabled ? 'success' : 'default'} label={schedule.enabled ? '已启用' : '已停用'} /></Stack></Paper>)}</Stack></Box>
  </Stack>;
}

function StatusMetric({ label, value, status }: { label: string; value: string; status: string }) {
  return <Paper variant="outlined" sx={{ p: 1.75 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1} sx={{ mt: .5 }}><Typography fontWeight={800}>{value}</Typography><Chip size="small" color={statusColor(status)} label={statusLabel(status)} /></Stack></Paper>;
}
