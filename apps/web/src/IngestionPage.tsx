import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import ScienceOutlinedIcon from '@mui/icons-material/ScienceOutlined';
import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, Link, MenuItem, Paper, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Tooltip, Typography } from '@mui/material';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { api } from './api';
import { SecretTextField } from './SecretTextField';
import type { EvidenceDocument, IngestionWorkspace, SourceHealth, SourceMutation, SourceRecord, TaskRecord } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function IngestionPage() {
  const [workspace, setWorkspace] = useState<IngestionWorkspace | null>(null);
  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [sourceCatalog, setSourceCatalog] = useState<SourceRecord[]>([]);
  const [documents, setDocuments] = useState<EvidenceDocument[]>([]);
  const [sourceHealth, setSourceHealth] = useState<SourceHealth | null>(null);
  const [sourceId, setSourceId] = useState('');
  const [status, setStatus] = useState('');
  const [queryText, setQueryText] = useState('最新市场、宏观、监管和交易所事件');
  const [credentialValue, setCredentialValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [editorSource, setEditorSource] = useState<SourceRecord | 'new' | null>(null);
  const refresh = useCallback(async (preferredSourceId?: string) => {
    try {
      const [nextWorkspace, nextTasks, nextSources] = await Promise.all([api.ingestionWorkspace(150), api.tasks(undefined, 200), api.sources()]);
      setWorkspace(nextWorkspace);
      setSourceCatalog(nextSources);
      setTasks(nextTasks.filter((task) => task.taskType === 'INGESTION'));
      setSourceId((current) => {
        const candidate = preferredSourceId || current;
        return nextWorkspace.sources.some((source) => source.sourceId === candidate)
          ? candidate
          : nextWorkspace.sources[0]?.sourceId || '';
      });
    } catch (cause) { setError(cause); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    let active = true;
    setSourceHealth(null);
    if (!sourceId) { setDocuments([]); return () => { active = false; }; }
    void Promise.allSettled([api.documents(sourceId, 50), api.sourceHealth(sourceId)])
      .then(([documentsResult, healthResult]) => {
        if (!active) return;
        if (documentsResult.status === 'fulfilled') setDocuments(documentsResult.value);
        else setError(documentsResult.reason);
        if (healthResult.status === 'fulfilled') setSourceHealth(healthResult.value);
        else setError(healthResult.reason);
      });
    return () => { active = false; };
  }, [sourceId]);
  const collect = async () => {
    if (!sourceId || !queryText.trim()) return;
    setBusy(true); setError(null); setMessage('');
    try { const task = await api.collectSource(sourceId, queryText, crypto.randomUUID()); setMessage(`采集任务 ${task.taskId} 已受理`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const testSource = async () => {
    if (!sourceId || !queryText.trim()) return;
    setBusy(true); setError(null); setMessage('');
    try {
      const result = await api.testSource(sourceId, queryText.trim());
      setMessage(`在线测试${statusLabel(result.status)}：获取 ${result.fetchedCount}，新增 ${result.insertedCount}，重复 ${result.duplicateCount}${result.errorCode ? ` · ${result.errorCode}` : ''}`);
      await refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const saveSource = async (definition: SourceMutation) => {
    setBusy(true); setError(null); setMessage('');
    try {
      const saved = editorSource === 'new'
        ? await api.createSource(definition)
        : await api.updateSource(editorSource!.sourceId, editorSource!.version, definition);
      setEditorSource(null);
      setMessage(editorSource === 'new' ? '信息源已创建' : '信息源配置已更新');
      await refresh(saved.sourceId);
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const deleteSource = async (source: SourceRecord) => {
    if (!window.confirm(`确认删除“${source.displayName}”？历史采集与证据会保留。`)) return;
    setBusy(true); setError(null); setMessage('');
    try { await api.deleteSource(source.sourceId, source.version); setMessage('信息源已归档，历史证据保持可追溯'); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const setSourceEnabled = async (nextSourceId: string, enabled: boolean, expectedVersion: number) => {
    setBusy(true); setError(null); setMessage('');
    try { await api.setSourceEnabled(nextSourceId, enabled, expectedVersion); setMessage(enabled ? '信息源已启用' : '信息源已停用'); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const putSourceCredential = async (source: IngestionWorkspace['sources'][number]) => {
    if (credentialValue.trim().length < 8) return;
    setBusy(true); setError(null); setMessage('');
    try { await api.putRuntimeSecret('INFORMATION_SOURCE', source.sourceId, 'API_KEY', credentialValue.trim(), source.credentialVersion); setCredentialValue(''); setMessage(`${source.displayName}凭据已热更新`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const clearSourceCredential = async (source: IngestionWorkspace['sources'][number]) => {
    setBusy(true); setError(null); setMessage('');
    try { await api.clearRuntimeSecret('INFORMATION_SOURCE', source.sourceId, 'API_KEY', source.credentialVersion); setCredentialValue(''); setMessage(`${source.displayName}已恢复启动备用凭据`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const runs = useMemo(() => workspace?.recentRuns.filter((run) => (!sourceId || run.sourceId === sourceId) && (!status || run.status === status)) || [], [workspace, sourceId, status]);
  if (!workspace) return <LoadingBlock label="正在读取采集血缘" />;
  const selectedSource = workspace.sources.find((source) => source.sourceId === sourceId);
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}><Metric label="原始证据" value={workspace.rawEvidenceCount} /><Metric label="规范化文档" value={workspace.normalizedDocumentCount} /><Metric label="AI 候选与验证" value={workspace.aiReviewCount} /><Metric label="最终压缩结果" value={workspace.compressionCount} /></Box>
    <Alert severity="info">默认信源目录 {workspace.sourceCatalogVersion} · {workspace.sourceCatalogSize} 项 · manifest {workspace.sourceCatalogManifestHash.slice(0, 12)}…</Alert>
    <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5}>
      <TextField select label="信息源" value={sourceId} onChange={(event) => setSourceId(event.target.value)} sx={{ minWidth: 260 }}>{workspace.sources.map((source) => <MenuItem key={source.sourceId} value={source.sourceId}>{source.displayName} · {source.mode}</MenuItem>)}</TextField>
      <TextField fullWidth label="采集查询" value={queryText} onChange={(event) => setQueryText(event.target.value)} inputProps={{ maxLength: 1000 }} />
      <Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || !sourceId || !queryText.trim()} onClick={() => void collect()}>手动采集</Button>
      <Button startIcon={<ScienceOutlinedIcon />} disabled={busy || !sourceId || !queryText.trim()} onClick={() => void testSource()}>在线测试</Button>
      <Button startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新</Button>
    </Stack></Paper>
    {sourceHealth && <Alert severity={sourceHealth.serviceReady && sourceHealth.egressReady && sourceHealth.channelStatus === 'READY' ? 'success' : sourceHealth.channelStatus === 'DISABLED' ? 'info' : 'warning'}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} useFlexGap flexWrap="wrap" alignItems={{ md: 'center' }}>
        <Typography variant="body2" fontWeight={700}>渠道 {statusLabel(sourceHealth.channelStatus)}</Typography>
        <Chip size="small" color={sourceHealth.serviceReady ? 'success' : 'error'} label={sourceHealth.serviceReady ? '采集器可用' : '采集器不可用'} />
        <Chip size="small" color={sourceHealth.egressReady ? 'success' : 'error'} label={`${sourceHealth.routeType} · ${sourceHealth.egressReady ? '出口可用' : '出口不可用'}`} />
        <Typography variant="caption">{sourceHealth.routeEndpoint} · {sourceHealth.rateLimitStatus} · 最近成功 {formatTime(sourceHealth.lastSuccessAt)}</Typography>
        {sourceHealth.latestErrorCode && <Typography variant="caption" color="error">{sourceHealth.latestErrorCode}{sourceHealth.safeMessage ? `：${sourceHealth.safeMessage}` : ''}</Typography>}
      </Stack>
    </Alert>}
    {selectedSource?.credentialSupported && <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}><Box sx={{ minWidth: { md: 240 } }}><Typography fontWeight={700}>{selectedSource.displayName}凭据</Typography><Typography variant="caption" color="text.secondary">{sourceCredentialLabel(selectedSource.credentialSource)}{selectedSource.credentialFingerprint ? ` · 指纹 ${selectedSource.credentialFingerprint}` : ''}</Typography></Box><SecretTextField fullWidth autoComplete="new-password" label="新 API Key" value={credentialValue} onChange={(event) => setCredentialValue(event.target.value)} helperText="保存后下一次采集立即使用；旧值不会回显" /><Button disabled={busy || credentialValue.trim().length < 8} onClick={() => void putSourceCredential(selectedSource)}>设置凭据</Button><Button color="error" disabled={busy || selectedSource.credentialSource !== 'DATABASE_OVERRIDE'} onClick={() => void clearSourceCredential(selectedSource)}>清除热配置</Button></Stack></Paper>}
    <Box><SectionTitle title="信息源状态" action={<Button startIcon={<AddIcon />} onClick={() => setEditorSource('new')}>新增信源</Button>} /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>信息源</TableCell><TableCell>层级 / 分类</TableCell><TableCell>路由 / 凭据</TableCell><TableCell>启用</TableCell><TableCell align="right">获取 / 新增 / 重复</TableCell><TableCell>最近状态</TableCell><TableCell>最近运行</TableCell><TableCell align="right">操作</TableCell></TableRow></TableHead><TableBody>{workspace.sources.map((source) => { const configuration = sourceCatalog.find((item) => item.sourceId === source.sourceId); return <TableRow key={source.sourceId} hover selected={source.sourceId === sourceId} onClick={() => { setSourceId(source.sourceId); setCredentialValue(''); }} sx={{ cursor: 'pointer' }}><TableCell><Typography variant="body2" fontWeight={700}>{source.displayName}</Typography><Typography variant="caption" color="text.secondary">{source.sourceId}</Typography></TableCell><TableCell>{source.tier} · {source.category}</TableCell><TableCell><Typography variant="body2">{source.outboundRoute || 'DIRECT'}</Typography><Typography variant="caption" color={source.credentialConfigured ? 'text.secondary' : 'error'}>{sourceCredentialLabel(source.credentialSource)}</Typography></TableCell><TableCell><Switch size="small" checked={source.enabled} disabled={busy} inputProps={{ 'aria-label': `${source.displayName}启用状态` }} onClick={(event) => event.stopPropagation()} onChange={(event) => void setSourceEnabled(source.sourceId, event.target.checked, source.version)} /></TableCell><TableCell align="right">{source.fetchedCount} / {source.insertedCount} / {source.duplicateCount}</TableCell><TableCell><Chip size="small" color={statusColor(source.latestStatus)} label={statusLabel(source.latestStatus || 'NO_DATA')} />{source.errorMessage && <Typography variant="caption" color="error" display="block">{source.errorCode}: {source.errorMessage}</Typography>}</TableCell><TableCell>{formatTime(source.lastCollectedAt)}</TableCell><TableCell align="right" sx={{ whiteSpace: 'nowrap' }}><Tooltip title="编辑信源"><span><IconButton size="small" disabled={!configuration || busy} onClick={(event) => { event.stopPropagation(); if (configuration) setEditorSource(configuration); }}><EditOutlinedIcon fontSize="small" /></IconButton></span></Tooltip><Tooltip title="删除信源"><span><IconButton size="small" color="error" disabled={!configuration || busy} onClick={(event) => { event.stopPropagation(); if (configuration) void deleteSource(configuration); }}><DeleteOutlineIcon fontSize="small" /></IconButton></span></Tooltip></TableCell></TableRow>; })}</TableBody></Table></Paper></Box>
    <Box><SectionTitle title="阶段运行与重试" action={<TextField select size="small" label="状态" value={status} onChange={(event) => setStatus(event.target.value)} sx={{ minWidth: 150 }}><MenuItem value="">全部</MenuItem>{['RUNNING', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED'].map((value) => <MenuItem key={value} value={value}>{statusLabel(value)}</MenuItem>)}</TextField>} /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>开始</TableCell><TableCell>来源 / 查询</TableCell><TableCell align="right">获取</TableCell><TableCell align="right">新增</TableCell><TableCell align="right">重复</TableCell><TableCell>状态 / 错误</TableCell></TableRow></TableHead><TableBody>{runs.map((run) => <TableRow key={run.collectionId}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(run.startedAt)}</TableCell><TableCell><Typography variant="body2" fontWeight={700}>{run.sourceName}</Typography><Typography variant="caption" color="text.secondary">{run.query || '-'} · {run.workflowRunId || '手动采集'}</Typography></TableCell><TableCell align="right">{run.fetchedCount}</TableCell><TableCell align="right">{run.insertedCount}</TableCell><TableCell align="right">{run.duplicateCount}</TableCell><TableCell><Chip size="small" color={statusColor(run.status)} label={statusLabel(run.status)} />{run.errorMessage && <Typography variant="caption" color="error" display="block">{run.errorCode}: {run.errorMessage}</Typography>}</TableCell></TableRow>)}</TableBody></Table>{runs.length === 0 && <EmptyBlock>当前筛选没有采集运行</EmptyBlock>}</Paper></Box>
    <Box><SectionTitle title="多 AI 清洗与压缩审计" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>时间</TableCell><TableCell>阶段 / 席位</TableCell><TableCell>文档 / 运行</TableCell><TableCell>结果摘要 / 原文引用</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{workspace.recentAiReviews.map((review) => <TableRow key={review.reviewId}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(review.createdAt)}</TableCell><TableCell><Typography variant="body2" fontWeight={700}>{reviewStageLabel(review.stage)}</Typography><Typography variant="caption" color="text.secondary">{review.nodeId}</Typography></TableCell><TableCell><Typography variant="caption" display="block">{review.documentId}</Typography><Typography variant="caption" color="text.secondary">{review.workflowRunId}</Typography></TableCell><TableCell sx={{ minWidth: 320, maxWidth: 620 }}><Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>{review.summary || review.errorMessage || '-'}</Typography>{review.citations.some((citation) => /^b\d+$/.test(citation)) && <Stack direction="row" spacing={.5} useFlexGap flexWrap="wrap" sx={{ mt: .75 }}>{review.citations.filter((citation) => /^b\d+$/.test(citation)).map((citation) => <Chip key={citation} size="small" variant="outlined" label={citation} />)}</Stack>}{review.errorCode && <Typography variant="caption" color="error">{review.errorCode}</Typography>}</TableCell><TableCell><Chip size="small" color={statusColor(review.status)} label={statusLabel(review.status)} /></TableCell></TableRow>)}</TableBody></Table>{workspace.recentAiReviews.length === 0 && <EmptyBlock>尚无多 AI 证据处理记录</EmptyBlock>}</Paper></Box>
    <Box><SectionTitle title="规范化证据" /><Stack spacing={1}>{documents.map((document) => <Paper key={document.documentId} variant="outlined" sx={{ p: 1.5 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} justifyContent="space-between"><Box sx={{ minWidth: 0 }}><Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={700}>{document.title || '无标题证据'}</Typography><Chip size="small" label={`${document.sourceTier} · ${(Number(document.trustWeight) * 100).toFixed(0)}%`} /></Stack><Typography variant="body2" color="text.secondary" sx={{ mt: .5 }}>{document.excerpt}</Typography><Typography variant="caption" color="text.secondary">{document.category} · {formatTime(document.publishedAt || document.fetchedAt)} · {document.documentId}</Typography></Box>{document.canonicalUrl && <Link href={document.canonicalUrl} target="_blank" rel="noreferrer" sx={{ flexShrink: 0 }}><OpenInNewIcon fontSize="small" /></Link>}</Stack></Paper>)}{documents.length === 0 && <EmptyBlock>该来源尚无规范化证据</EmptyBlock>}</Stack></Box>
    <Box><SectionTitle title="持久化后台任务" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>创建</TableCell><TableCell>摘要</TableCell><TableCell>尝试</TableCell><TableCell>状态</TableCell><TableCell>错误</TableCell></TableRow></TableHead><TableBody>{tasks.slice(0, 50).map((task) => <TableRow key={task.taskId}><TableCell>{formatTime(task.createdAt)}</TableCell><TableCell>{task.payloadSummary}<br /><Typography variant="caption">{task.taskId}</Typography></TableCell><TableCell>{task.attemptCount}/{task.maximumAttempts}</TableCell><TableCell><Chip size="small" color={statusColor(task.status)} label={statusLabel(task.status)} /></TableCell><TableCell><Typography variant="caption" color="error">{task.errorMessage || '-'}</Typography></TableCell></TableRow>)}</TableBody></Table></Paper></Box>
    <SourceEditorDialog open={editorSource !== null} source={editorSource === 'new' ? null : editorSource} busy={busy} onClose={() => setEditorSource(null)} onSave={saveSource} />
  </Stack>;
}

const DEFAULT_SOURCE: SourceMutation = {
  displayName: '', mode: 'RSS', tier: 'T2', category: 'market_news', provider: null,
  trustWeight: 0.7, pollIntervalSeconds: 900, priority: 'P2', assetScope: [],
  feedUrls: [], seedUrls: [], searchQueries: [], endpointBaseUrl: null,
  credentialSupported: false, outboundRoute: 'PUBLIC_DATA', maximumResults: 10,
  maximumScrapeTargets: 3, enabled: true,
};

function SourceEditorDialog({ open, source, busy, onClose, onSave }: { open: boolean; source: SourceRecord | null; busy: boolean; onClose: () => void; onSave: (source: SourceMutation) => Promise<void> }) {
  const [draft, setDraft] = useState<SourceMutation>(DEFAULT_SOURCE);
  const [showFirecrawlChannel, setShowFirecrawlChannel] = useState(false);
  useEffect(() => { if (open) { setDraft(source ? sourceMutation(source) : DEFAULT_SOURCE); setShowFirecrawlChannel(Boolean(source?.mode.startsWith('FIRECRAWL'))); } }, [open, source]);
  const set = <K extends keyof SourceMutation>(key: K, value: SourceMutation[K]) => setDraft((current) => ({ ...current, [key]: value }));
  const firecrawl = draft.mode.startsWith('FIRECRAWL');
  const htmlDocument = draft.mode === 'HTML_DOCUMENT';
  const sitemap = draft.mode === 'SITEMAP';
  const sourceModes = ['RSS', 'HTML_DOCUMENT', 'SEARCH_DISCOVERY', 'JSON_API', 'SITEMAP', 'EXCHANGE_PUBLIC_API', ...(showFirecrawlChannel ? ['FIRECRAWL_SCRAPE', 'FIRECRAWL_SEARCH', 'FIRECRAWL_SEARCH_THEN_SCRAPE'] : [])];
  return <Dialog open={open} onClose={busy ? undefined : onClose} maxWidth="md" fullWidth><DialogTitle>{source ? '编辑信息源' : '新增信息源'}</DialogTitle><DialogContent dividers><Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5, pt: .5 }}>
    <TextField required label="名称" value={draft.displayName} onChange={(event) => set('displayName', event.target.value)} inputProps={{ maxLength: 160 }} />
    <TextField select label="类型" value={draft.mode} onChange={(event) => { const mode = event.target.value; setDraft((current) => ({ ...current, mode, outboundRoute: mode === 'HTML_DOCUMENT' || mode === 'SEARCH_DISCOVERY' ? 'WEB_CRAWL' : mode.startsWith('FIRECRAWL') ? 'FIRECRAWL' : 'PUBLIC_DATA' })); }}>{sourceModes.map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField>
    <TextField select label="证据层级" value={draft.tier} onChange={(event) => set('tier', event.target.value)}>{['T0', 'T1', 'T2', 'T3', 'T4', 'T5'].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField>
    <TextField required label="分类" value={draft.category} onChange={(event) => set('category', event.target.value)} inputProps={{ maxLength: 80 }} />
    <TextField label="Provider 标识" value={draft.provider || ''} onChange={(event) => set('provider', event.target.value || null)} inputProps={{ maxLength: 80 }} />
    <TextField select label="优先级" value={draft.priority} onChange={(event) => set('priority', event.target.value)}>{['P0', 'P1', 'P2', 'P3'].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField>
    <TextField type="number" label="信任权重" value={draft.trustWeight} onChange={(event) => set('trustWeight', Number(event.target.value))} inputProps={{ min: 0, max: 1, step: .05 }} />
    <TextField type="number" label="轮询间隔（秒）" value={draft.pollIntervalSeconds} onChange={(event) => set('pollIntervalSeconds', Number(event.target.value))} inputProps={{ min: 10, max: 2592000 }} />
    <TextField select label="出站路由" value={draft.outboundRoute || ''} onChange={(event) => set('outboundRoute', event.target.value || null)} disabled={firecrawl || htmlDocument}>{['PUBLIC_DATA', 'WEB_CRAWL', 'FIRECRAWL', 'EXCHANGE_GATE', 'EXCHANGE_BYBIT'].map((value) => <MenuItem key={value} value={value}>{value}</MenuItem>)}</TextField>
    <TextField type="url" label="Endpoint Base URL" value={draft.endpointBaseUrl || ''} onChange={(event) => set('endpointBaseUrl', event.target.value || null)} required={!htmlDocument && draft.mode !== 'RSS'} helperText={sitemap ? '仅解析 sitemap.xml 地址，文章正文由后续 HTML_DOCUMENT 来源采集' : undefined} />
    <TextField type="number" label="最大结果数" value={draft.maximumResults} onChange={(event) => set('maximumResults', Number(event.target.value))} inputProps={{ min: 1, max: 100 }} />
    <TextField type="number" label="最大抓取目标" value={draft.maximumScrapeTargets} onChange={(event) => set('maximumScrapeTargets', Number(event.target.value))} inputProps={{ min: 0, max: 20 }} />
    <TextField label="关注产品（逗号或换行）" value={draft.assetScope.join('\n')} onChange={(event) => set('assetScope', splitValues(event.target.value))} multiline minRows={2} />
    <TextField label="RSS Feed URL（每行一个）" value={draft.feedUrls.join('\n')} onChange={(event) => set('feedUrls', splitLines(event.target.value))} multiline minRows={2} required={draft.mode === 'RSS'} />
    <TextField label="Seed URL（每行一个）" value={draft.seedUrls.join('\n')} onChange={(event) => set('seedUrls', splitLines(event.target.value))} multiline minRows={2} required={draft.mode === 'FIRECRAWL_SCRAPE' || htmlDocument} />
    <TextField label="搜索查询（每行一个）" value={draft.searchQueries.join('\n')} onChange={(event) => set('searchQueries', splitLines(event.target.value))} multiline minRows={2} />
    <Stack direction="row" spacing={2} useFlexGap flexWrap="wrap" sx={{ gridColumn: { sm: '1 / -1' } }}><FormControlLabel control={<Switch checked={draft.credentialSupported} onChange={(event) => set('credentialSupported', event.target.checked)} />} label="需要 API Key" /><FormControlLabel control={<Switch checked={draft.enabled} onChange={(event) => set('enabled', event.target.checked)} />} label="创建后启用" /><FormControlLabel control={<Switch checked={showFirecrawlChannel} onChange={(event) => setShowFirecrawlChannel(event.target.checked)} />} label="显示 Firecrawl 渠道" /></Stack>
  </Box></DialogContent><DialogActions><Button onClick={onClose} disabled={busy}>取消</Button><Button variant="contained" disabled={busy || !sourceDraftValid(draft)} onClick={() => void onSave(draft)}>保存</Button></DialogActions></Dialog>;
}

function sourceMutation(source: SourceRecord): SourceMutation { const { sourceId: _sourceId, version: _version, ...definition } = source; return definition; }
function splitLines(value: string): string[] { return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean); }
function splitValues(value: string): string[] { return value.split(/[\r\n,]+/).map((item) => item.trim()).filter(Boolean); }
function sourceDraftValid(source: SourceMutation): boolean { if (!source.displayName.trim() || !source.category.trim()) return false; if (source.mode === 'RSS') return source.feedUrls.length > 0; if (source.mode === 'HTML_DOCUMENT') return source.seedUrls.length > 0; if (!source.endpointBaseUrl) return false; if (source.mode === 'FIRECRAWL_SCRAPE') return source.seedUrls.length > 0; return true; }

function Metric({ label, value }: { label: string; value: number }) { return <Paper variant="outlined" sx={{ p: 1.75 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="h2">{value.toLocaleString('zh-CN')}</Typography></Paper>; }

function reviewStageLabel(stage: string): string { return ({ CLEANING: 'AI 清洗审查', COMPRESSION: '压缩候选', VALIDATION: '独立验证' } as Record<string, string>)[stage] || stage; }

function sourceCredentialLabel(source: IngestionWorkspace['sources'][number]['credentialSource']): string { return ({ DATABASE_OVERRIDE: '后台热配置', ENVIRONMENT_FALLBACK: '启动备用配置', UNCONFIGURED: '凭据未配置', NOT_REQUIRED: '无需独立 Key' } as const)[source]; }
