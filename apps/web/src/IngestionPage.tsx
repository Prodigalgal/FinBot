import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, Link, MenuItem, Paper, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type { EvidenceDocument, IngestionWorkspace, TaskRecord } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function IngestionPage() {
  const [workspace, setWorkspace] = useState<IngestionWorkspace | null>(null);
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [documents, setDocuments] = useState<EvidenceDocument[]>([]);
  const [sourceId, setSourceId] = useState('');
  const [status, setStatus] = useState('');
  const [queryText, setQueryText] = useState('最新市场、宏观、监管和交易所事件');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const refresh = useCallback(async () => {
    try {
      const [nextWorkspace, nextTasks] = await Promise.all([api.ingestionWorkspace(150), api.tasks(undefined, 200)]);
      setWorkspace(nextWorkspace);
      setTasks(nextTasks.filter((task) => task.taskType === 'INGESTION'));
      setSourceId((current) => current || nextWorkspace.sources[0]?.sourceId || '');
    } catch (cause) { setError(cause); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => { if (sourceId) api.documents(sourceId, 50).then(setDocuments).catch(setError); else setDocuments([]); }, [sourceId]);
  const collect = async () => {
    if (!sourceId || !queryText.trim()) return;
    setBusy(true); setError(null); setMessage('');
    try { const task = await api.collectSource(sourceId, queryText, crypto.randomUUID()); setMessage(`采集任务 ${task.taskId} 已受理`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const setSourceEnabled = async (nextSourceId: string, enabled: boolean, expectedVersion: number) => {
    setBusy(true); setError(null); setMessage('');
    try { await api.setSourceEnabled(nextSourceId, enabled, expectedVersion); setMessage(enabled ? '信息源已启用' : '信息源已停用'); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const runs = useMemo(() => workspace?.recentRuns.filter((run) => (!sourceId || run.sourceId === sourceId) && (!status || run.status === status)) || [], [workspace, sourceId, status]);
  if (!workspace) return <LoadingBlock label="正在读取采集血缘" />;
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 1.25 }}><Metric label="原始证据" value={workspace.rawEvidenceCount} /><Metric label="规范化文档" value={workspace.normalizedDocumentCount} /><Metric label="AI 压缩记录" value={workspace.compressionCount} /></Box>
    <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5}>
      <TextField select label="信息源" value={sourceId} onChange={(event) => setSourceId(event.target.value)} sx={{ minWidth: 260 }}>{workspace.sources.map((source) => <MenuItem key={source.sourceId} value={source.sourceId}>{source.displayName} · {source.mode}</MenuItem>)}</TextField>
      <TextField fullWidth label="采集查询" value={queryText} onChange={(event) => setQueryText(event.target.value)} inputProps={{ maxLength: 1000 }} />
      <Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || !sourceId || !queryText.trim()} onClick={() => void collect()}>手动采集</Button>
      <Button startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新</Button>
    </Stack></Paper>
    <Box><SectionTitle title="信息源状态" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>信息源</TableCell><TableCell>层级 / 分类</TableCell><TableCell>路由 / 凭据</TableCell><TableCell>启用</TableCell><TableCell align="right">获取 / 新增 / 重复</TableCell><TableCell>最近状态</TableCell><TableCell>最近运行</TableCell></TableRow></TableHead><TableBody>{workspace.sources.map((source) => <TableRow key={source.sourceId} hover selected={source.sourceId === sourceId} onClick={() => setSourceId(source.sourceId)} sx={{ cursor: 'pointer' }}><TableCell><Typography variant="body2" fontWeight={700}>{source.displayName}</Typography><Typography variant="caption" color="text.secondary">{source.sourceId}</Typography></TableCell><TableCell>{source.tier} · {source.category}</TableCell><TableCell><Typography variant="body2">{source.outboundRoute || 'DIRECT'}</Typography><Typography variant="caption" color={source.credentialConfigured ? 'text.secondary' : 'error'}>{source.credentialEnvironment ? `${source.credentialEnvironment} · ${source.credentialConfigured ? '已注入' : '未注入'}` : '无需独立 Key'}</Typography></TableCell><TableCell><Switch size="small" checked={source.enabled} disabled={busy} inputProps={{ 'aria-label': `${source.displayName}启用状态` }} onClick={(event) => event.stopPropagation()} onChange={(event) => void setSourceEnabled(source.sourceId, event.target.checked, source.version)} /></TableCell><TableCell align="right">{source.fetchedCount} / {source.insertedCount} / {source.duplicateCount}</TableCell><TableCell><Chip size="small" color={statusColor(source.latestStatus)} label={statusLabel(source.latestStatus || 'NO_DATA')} />{source.errorMessage && <Typography variant="caption" color="error" display="block">{source.errorCode}: {source.errorMessage}</Typography>}</TableCell><TableCell>{formatTime(source.lastCollectedAt)}</TableCell></TableRow>)}</TableBody></Table></Paper></Box>
    <Box><SectionTitle title="阶段运行与重试" action={<TextField select size="small" label="状态" value={status} onChange={(event) => setStatus(event.target.value)} sx={{ minWidth: 150 }}><MenuItem value="">全部</MenuItem>{['RUNNING', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED'].map((value) => <MenuItem key={value} value={value}>{statusLabel(value)}</MenuItem>)}</TextField>} /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>开始</TableCell><TableCell>来源 / 查询</TableCell><TableCell align="right">获取</TableCell><TableCell align="right">新增</TableCell><TableCell align="right">重复</TableCell><TableCell>状态 / 错误</TableCell></TableRow></TableHead><TableBody>{runs.map((run) => <TableRow key={run.collectionId}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(run.startedAt)}</TableCell><TableCell><Typography variant="body2" fontWeight={700}>{run.sourceName}</Typography><Typography variant="caption" color="text.secondary">{run.query || '-'} · {run.workflowRunId || '手动采集'}</Typography></TableCell><TableCell align="right">{run.fetchedCount}</TableCell><TableCell align="right">{run.insertedCount}</TableCell><TableCell align="right">{run.duplicateCount}</TableCell><TableCell><Chip size="small" color={statusColor(run.status)} label={statusLabel(run.status)} />{run.errorMessage && <Typography variant="caption" color="error" display="block">{run.errorCode}: {run.errorMessage}</Typography>}</TableCell></TableRow>)}</TableBody></Table>{runs.length === 0 && <EmptyBlock>当前筛选没有采集运行</EmptyBlock>}</Paper></Box>
    <Box><SectionTitle title="规范化证据" /><Stack spacing={1}>{documents.map((document) => <Paper key={document.documentId} variant="outlined" sx={{ p: 1.5 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} justifyContent="space-between"><Box sx={{ minWidth: 0 }}><Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={700}>{document.title || '无标题证据'}</Typography><Chip size="small" label={`${document.sourceTier} · ${(Number(document.trustWeight) * 100).toFixed(0)}%`} /></Stack><Typography variant="body2" color="text.secondary" sx={{ mt: .5 }}>{document.excerpt}</Typography><Typography variant="caption" color="text.secondary">{document.category} · {formatTime(document.publishedAt || document.fetchedAt)} · {document.documentId}</Typography></Box>{document.canonicalUrl && <Link href={document.canonicalUrl} target="_blank" rel="noreferrer" sx={{ flexShrink: 0 }}><OpenInNewIcon fontSize="small" /></Link>}</Stack></Paper>)}{documents.length === 0 && <EmptyBlock>该来源尚无规范化证据</EmptyBlock>}</Stack></Box>
    <Box><SectionTitle title="持久化后台任务" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>创建</TableCell><TableCell>摘要</TableCell><TableCell>尝试</TableCell><TableCell>状态</TableCell><TableCell>错误</TableCell></TableRow></TableHead><TableBody>{tasks.slice(0, 50).map((task) => <TableRow key={task.taskId}><TableCell>{formatTime(task.createdAt)}</TableCell><TableCell>{task.payloadSummary}<br /><Typography variant="caption">{task.taskId}</Typography></TableCell><TableCell>{task.attemptCount}/{task.maximumAttempts}</TableCell><TableCell><Chip size="small" color={statusColor(task.status)} label={statusLabel(task.status)} /></TableCell><TableCell><Typography variant="caption" color="error">{task.errorMessage || '-'}</Typography></TableCell></TableRow>)}</TableBody></Table></Paper></Box>
  </Stack>;
}

function Metric({ label, value }: { label: string; value: number }) { return <Paper variant="outlined" sx={{ p: 1.75 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="h2">{value.toLocaleString('zh-CN')}</Typography></Paper>; }
