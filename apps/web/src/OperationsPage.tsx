import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, FormControlLabel, Paper, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import type { EvidenceDocument, OperationsOverview, SourceRecord, TaskRecord } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function OperationsPage() {
  const [overview, setOverview] = useState<OperationsOverview | null>(null);
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [sources, setSources] = useState<SourceRecord[]>([]);
  const [documents, setDocuments] = useState<EvidenceDocument[]>([]);
  const [selectedSource, setSelectedSource] = useState('');
  const [queryText, setQueryText] = useState('最新市场相关信息');
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    setLoading(true); setError(null);
    try { const [state, taskList, sourceList] = await Promise.all([api.operations(), api.tasks(undefined, 100), api.sources(false)]); setOverview(state); setTasks(taskList); setSources(sourceList); setSelectedSource((current) => current || sourceList[0]?.sourceId || ''); } catch (cause) { setError(cause); } finally { setLoading(false); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  const updateSchedule = async (scheduleId: string, enabled: boolean, intervalSeconds: number, version: number) => {
    try { await api.updateSchedule(scheduleId, { enabled, intervalSeconds, expectedVersion: version }); await refresh(); } catch (cause) { setError(cause); }
  };
  const collect = async () => { if (!selectedSource) return; try { await api.collectSource(selectedSource, queryText, crypto.randomUUID()); await refresh(); } catch (cause) { setError(cause); } };
  const loadDocuments = async (sourceId: string) => { setSelectedSource(sourceId); try { setDocuments(await api.documents(sourceId, 30)); } catch (cause) { setError(cause); } };
  if (error !== null && !overview) return <ErrorBlock error={error} />;
  if (loading && !overview) return <LoadingBlock />;
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}
    {overview && <><SectionTitle title="常驻服务与调度" action={<Button startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新</Button>} /><Stack spacing={1.25}>{overview.schedules.map((schedule) => <Paper key={schedule.scheduleId} variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{schedule.displayName}</Typography><Typography variant="caption" color="text.secondary">{schedule.taskType} · 下次 {formatTime(schedule.nextRunAt)} · 上次 {formatTime(schedule.lastScheduledAt)}</Typography></Box><TextField label="间隔（秒）" type="number" defaultValue={schedule.intervalSeconds} onBlur={(event) => { const seconds = Number(event.target.value); if (Number.isInteger(seconds) && seconds !== schedule.intervalSeconds) void updateSchedule(schedule.scheduleId, schedule.enabled, seconds, schedule.version); }} sx={{ width: 140 }} /><FormControlLabel control={<Switch checked={schedule.enabled} onChange={(event) => void updateSchedule(schedule.scheduleId, event.target.checked, schedule.intervalSeconds, schedule.version)} />} label={schedule.enabled ? '已启用' : '已停用'} /></Stack></Paper>)}</Stack>
      <SectionTitle title="Worker" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>实例</TableCell><TableCell>状态</TableCell><TableCell>启动</TableCell><TableCell>心跳</TableCell></TableRow></TableHead><TableBody>{overview.workers.map((worker) => <TableRow key={worker.workerId}><TableCell>{worker.instanceName}<br /><Typography variant="caption">{worker.workerId}</Typography></TableCell><TableCell><Chip size="small" color={statusColor(worker.status)} label={statusLabel(worker.status)} /></TableCell><TableCell>{formatTime(worker.startedAt)}</TableCell><TableCell>{formatTime(worker.heartbeatAt)}</TableCell></TableRow>)}</TableBody></Table></Paper>
      <SectionTitle title="历史迁移核对" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>来源</TableCell><TableCell>表</TableCell><TableCell>源行数</TableCell><TableCell>归档行数</TableCell><TableCell>强类型转换</TableCell><TableCell>状态</TableCell><TableCell>完成时间</TableCell></TableRow></TableHead><TableBody>{overview.legacyImports.map((item) => <TableRow key={item.importId}><TableCell>{item.sourceName}<br /><Typography variant="caption" color="text.secondary">SHA256 {item.sourceSha256.slice(0, 12)}…</Typography></TableCell><TableCell>{item.sourceTableCount}</TableCell><TableCell>{item.sourceRowCount}</TableCell><TableCell>{item.archivedRowCount}</TableCell><TableCell>{item.transformedRowCount}</TableCell><TableCell><Chip size="small" color={statusColor(item.status)} label={statusLabel(item.status)} />{item.errorSummary && <Typography variant="caption" color="error" display="block">{item.errorSummary}</Typography>}</TableCell><TableCell>{formatTime(item.completedAt)}</TableCell></TableRow>)}</TableBody></Table>{overview.legacyImports.length === 0 && <EmptyBlock>尚未执行旧系统历史导入</EmptyBlock>}</Paper></>}
    <SectionTitle title="后台任务" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>创建</TableCell><TableCell>类型</TableCell><TableCell>摘要</TableCell><TableCell>尝试</TableCell><TableCell>状态</TableCell><TableCell>错误</TableCell></TableRow></TableHead><TableBody>{tasks.map((task) => <TableRow key={task.taskId}><TableCell>{formatTime(task.createdAt)}</TableCell><TableCell>{task.taskType}</TableCell><TableCell>{task.payloadSummary}</TableCell><TableCell>{task.attemptCount}/{task.maximumAttempts}</TableCell><TableCell><Chip size="small" color={statusColor(task.status)} label={statusLabel(task.status)} /></TableCell><TableCell><Typography variant="caption" color="error">{task.errorCode ? `${task.errorCode}: ${task.errorMessage}` : '-'}</Typography></TableCell></TableRow>)}</TableBody></Table></Paper>
    <SectionTitle title="信息源与证据" /><Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField select SelectProps={{ native: true }} value={selectedSource} onChange={(event) => void loadDocuments(event.target.value)} sx={{ minWidth: 260 }}>{sources.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.displayName} · {source.mode}</option>)}</TextField><TextField fullWidth value={queryText} onChange={(event) => setQueryText(event.target.value)} label="采集查询" /><Button variant="contained" onClick={() => void collect()}>提交采集</Button></Stack></Paper>
    <Stack spacing={1}>{documents.map((document) => <Paper key={document.documentId} variant="outlined" sx={{ p: 1.5 }}><Stack direction="row" justifyContent="space-between"><Typography fontWeight={700}>{document.title}</Typography><Chip size="small" label={`${document.sourceTier} · ${Number(document.trustWeight).toFixed(2)}`} /></Stack><Typography variant="body2" color="text.secondary" sx={{ mt: .5 }}>{document.excerpt}</Typography><Typography variant="caption" color="text.secondary">{formatTime(document.publishedAt || document.fetchedAt)} · {document.canonicalUrl || document.evidenceId}</Typography></Paper>)}{selectedSource && documents.length === 0 && <EmptyBlock>选择信息源后显示最近证据</EmptyBlock>}</Stack>
  </Stack>;
}
