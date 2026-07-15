import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import ReplayIcon from '@mui/icons-material/Replay';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import SaveIcon from '@mui/icons-material/Save';
import { Alert, Box, Button, Chip, Divider, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { ApiError, api } from './api';
import { ResearchTurnCard, orderedResearchTurns } from './ResearchTurnCard';
import { TradingExecutionDetail } from './TradingExecutionDetail';
import { ForecastPanel } from './ForecastPanel';
import { ResearchCasePanel } from './ResearchCasePanel';
import type { ResearchCase, ResearchComparison, ResearchFeedback, ResearchForecast, ResearchHistoryDetail, ResearchLaunch, ResearchSummary, TradeAutomationDetail } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function HistoryPage({ onOpenRun }: { onOpenRun?: (launch: ResearchLaunch) => void }) {
  const [runs, setRuns] = useState<ResearchSummary[] | null>(null);
  const [detail, setDetail] = useState<ResearchHistoryDetail | null>(null);
  const [automation, setAutomation] = useState<TradeAutomationDetail | null>(null);
  const [forecast, setForecast] = useState<ResearchForecast | null>(null);
  const [researchCase, setResearchCase] = useState<ResearchCase | null>(null);
  const [feedback, setFeedback] = useState<ResearchFeedback[]>([]);
  const [rating, setRating] = useState<ResearchFeedback['rating']>('HELPFUL');
  const [effectiveness, setEffectiveness] = useState<ResearchFeedback['effectiveness']>('UNKNOWN');
  const [note, setNote] = useState('');
  const [leftRunId, setLeftRunId] = useState('');
  const [rightRunId, setRightRunId] = useState('');
  const [comparison, setComparison] = useState<ResearchComparison | null>(null);
  const [resumeNodeId, setResumeNodeId] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const refresh = () => Promise.all([api.researchHistory(undefined, 100), api.researchFeedback(100)]).then(([items, savedFeedback]) => { setRuns(items); setFeedback(savedFeedback); setLeftRunId((current) => current || items[1]?.runId || items[0]?.runId || ''); setRightRunId((current) => current || items[0]?.runId || ''); }).catch(setError);
  useEffect(() => { void refresh(); }, []);
  const select = async (runId: string) => { setBusy(true); setError(null); try { const nextCase = await optionalResearchCase(runId); const demoRunId = nextCase?.segments.find((segment) => segment.segmentType === 'DEMO_AUTOTRADE')?.workflowRunId || runId; const [next, execution, researchForecast] = await Promise.all([api.researchDetail(runId), optionalAutomation(demoRunId), optionalForecast(runId)]); setResearchCase(nextCase); setDetail(next); setAutomation(execution); setForecast(researchForecast); const saved = feedback.find((item) => item.workflowRunId === runId); setRating(saved?.rating || 'HELPFUL'); setEffectiveness(saved?.effectiveness || 'UNKNOWN'); setNote(saved?.note || ''); setResumeNodeId(next.checkpoints.find((item) => item.status === 'FAILED')?.nodeId || ''); } catch (cause) { setError(cause); } finally { setBusy(false); } };
  const action = async (kind: 'replay' | 'resume') => {
    if (!detail) return;
    setBusy(true); setError(null);
    try {
      const launch = kind === 'replay' ? await api.replayResearch(detail.summary.runId, crypto.randomUUID()) : await api.resumeResearch(detail.summary.runId, crypto.randomUUID(), resumeNodeId || undefined);
      onOpenRun?.(launch); refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const compare = async () => { if (!leftRunId || !rightRunId || leftRunId === rightRunId) return; setBusy(true); setError(null); try { setComparison(await api.compareResearch(leftRunId, rightRunId)); } catch (cause) { setError(cause); } finally { setBusy(false); } };
  const saveFeedback = async () => { if (!detail) return; setBusy(true); setError(null); try { const current = feedback.find((item) => item.workflowRunId === detail.summary.runId); const saved = await api.saveResearchFeedback(detail.summary.runId, { rating, effectiveness, note, expectedVersion: current?.version ?? null }); setFeedback((items) => [...items.filter((item) => item.workflowRunId !== saved.workflowRunId), saved]); } catch (cause) { setError(cause); } finally { setBusy(false); } };
  if (error && !runs) return <ErrorBlock error={error} />;
  if (!runs) return <LoadingBlock />;
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.25} alignItems={{ lg: 'center' }}><TextField select label="基准运行" value={leftRunId} onChange={(event) => setLeftRunId(event.target.value)} fullWidth>{runs.map((run) => <MenuItem key={run.runId} value={run.runId}>{run.requestSummary} · {formatTime(run.acceptedAt)}</MenuItem>)}</TextField><TextField select label="对比运行" value={rightRunId} onChange={(event) => setRightRunId(event.target.value)} fullWidth>{runs.map((run) => <MenuItem key={run.runId} value={run.runId}>{run.requestSummary} · {formatTime(run.acceptedAt)}</MenuItem>)}</TextField><Button variant="outlined" startIcon={<CompareArrowsIcon />} disabled={leftRunId === rightRunId || busy} onClick={() => void compare()}>运行对比</Button></Stack></Paper>
    {comparison && <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}><Stack direction={{ xs: 'column', md: 'row' }} divider={<Divider orientation="vertical" flexItem />} spacing={2}><Summary label="Token 差异（右 - 左）" value={`${comparison.inputTokenDelta + comparison.outputTokenDelta}`} /><Summary label="成本差异" value={`$${Number(comparison.costDeltaUsd).toFixed(6)}`} /><Summary label="耗时差异" value={comparison.durationDeltaSeconds === null ? '-' : `${comparison.durationDeltaSeconds} 秒`} /><Summary label="变化节点" value={String(comparison.nodes.filter((node) => node.changed).length)} /></Stack><Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5 }}><Box><Typography variant="caption" color="text.secondary">基准结论</Typography><Typography>{comparison.leftConclusion}</Typography></Box><Box><Typography variant="caption" color="text.secondary">对比结论</Typography><Typography>{comparison.rightConclusion}</Typography></Box></Box>{comparison.nodes.filter((node) => node.changed).map((node) => <Alert key={`${node.nodeId}-${node.round}`} severity="info">{node.nodeId} 第 {node.round} 轮：{statusLabel(node.leftStatus)} → {statusLabel(node.rightStatus)}</Alert>)}</Stack></Paper>}
    <Paper variant="outlined" sx={{ overflow: 'auto' }}>
      <Table size="small"><TableHead><TableRow><TableCell>时间</TableCell><TableCell>研究问题</TableCell><TableCell>类型</TableCell><TableCell>AI 用量</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>
        {runs.map((run) => <TableRow key={run.runId} hover selected={detail?.summary.runId === run.runId} onClick={() => void select(run.runId)} sx={{ cursor: 'pointer' }}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(run.acceptedAt)}</TableCell><TableCell><Typography variant="body2" fontWeight={700}>{run.requestSummary}</Typography><Typography variant="caption" color="text.secondary">{run.runId}</Typography></TableCell><TableCell>{run.workflowType === 'SCHEDULED_RESEARCH' ? '定时' : '即时'}</TableCell><TableCell>{run.inputTokens + run.outputTokens} tokens<br /><Typography variant="caption">${Number(run.costUsd).toFixed(4)}</Typography></TableCell><TableCell><Chip size="small" color={statusColor(run.status)} label={statusLabel(run.status)} /></TableCell></TableRow>)}
      </TableBody></Table>
      {runs.length === 0 && <EmptyBlock />}
    </Paper>
    {busy && <LoadingBlock label="正在读取完整审计链" />}
    {detail && !busy && <>
      <SectionTitle title="运行详情" action={<Stack direction="row" spacing={1}><Button startIcon={<ReplayIcon />} onClick={() => void action('replay')}>重放</Button>{detail.summary.status === 'FAILED' && <Button variant="contained" startIcon={<RestartAltIcon />} onClick={() => void action('resume')}>从失败点续跑</Button>}</Stack>} />
      <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} divider={<Divider orientation="vertical" flexItem />} spacing={2}><Summary label="工作流版本" value={detail.summary.workflowVersionId} /><Summary label="开始" value={formatTime(detail.summary.startedAt || detail.summary.acceptedAt)} /><Summary label="完成" value={formatTime(detail.summary.completedAt)} /><Summary label="成本" value={`$${Number(detail.summary.costUsd).toFixed(6)}`} /></Stack></Paper>
      {researchCase && <ResearchCasePanel researchCase={researchCase} />}
      {forecast && <ForecastPanel forecast={forecast} />}
      {detail.summary.status === 'FAILED' && <TextField select label="失败 checkpoint" value={resumeNodeId} onChange={(event) => setResumeNodeId(event.target.value)}>{detail.checkpoints.filter((item) => item.status === 'FAILED').map((item) => <MenuItem key={`${item.nodeId}-${item.round}`} value={item.nodeId}>{item.displayName} · 第 {item.round} 轮 · {item.errorCode}</MenuItem>)}</TextField>}
      <SectionTitle title="人工反馈与效果" />
      <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25}><TextField select label="结果质量" value={rating} onChange={(event) => setRating(event.target.value as ResearchFeedback['rating'])} sx={{ minWidth: 170 }}><MenuItem value="HELPFUL">有帮助</MenuItem><MenuItem value="NEUTRAL">一般</MenuItem><MenuItem value="NOT_HELPFUL">无帮助</MenuItem></TextField><TextField select label="实际效果" value={effectiveness} onChange={(event) => setEffectiveness(event.target.value as ResearchFeedback['effectiveness'])} sx={{ minWidth: 170 }}><MenuItem value="UNKNOWN">未知</MenuItem><MenuItem value="PENDING">待观察</MenuItem><MenuItem value="WIN">方向正确</MenuItem><MenuItem value="LOSS">方向错误</MenuItem><MenuItem value="NO_TRADE">未交易</MenuItem></TextField><TextField fullWidth label="复核备注" value={note} onChange={(event) => setNote(event.target.value)} inputProps={{ maxLength: 2000 }} /><Button startIcon={<SaveIcon />} variant="contained" disabled={busy} onClick={() => void saveFeedback()}>保存反馈</Button></Stack><Typography variant="caption" color="text.secondary">反馈用于效果评估，不阻塞 TestNet / Demo 自动执行。</Typography></Paper>
      <SectionTitle title="节点与错误" />
      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>{detail.checkpoints.map((checkpoint, index) => <Stack key={`${checkpoint.nodeId}-${checkpoint.round}`} direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ p: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Box sx={{ width: { md: 220 } }}><Typography fontWeight={700}>{checkpoint.displayName}</Typography><Typography variant="caption">第 {checkpoint.round} 轮 · 尝试 {checkpoint.attempt}</Typography></Box><Chip size="small" color={statusColor(checkpoint.status)} label={statusLabel(checkpoint.status)} sx={{ alignSelf: 'flex-start' }} /><Box sx={{ flex: 1 }}><Typography variant="body2">{checkpoint.resultSummary || checkpoint.errorMessage || '-'}</Typography>{checkpoint.errorCode && <Typography variant="caption" color="error">{checkpoint.errorCode}</Typography>}</Box></Stack>)}</Paper>
      <SectionTitle title="多轮辩论" />
      <Stack spacing={1.25}>{orderedResearchTurns(detail.agentTurns).map((turn) => <ResearchTurnCard key={turn.messageId} turn={turn} />)}</Stack>
      {automation && <TradingExecutionDetail detail={automation} />}
      <SectionTitle title="AI 调用审计" />
      <Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>节点</TableCell><TableCell>厂商 / 模型</TableCell><TableCell>思考</TableCell><TableCell>Token</TableCell><TableCell>耗时</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{detail.aiInvocations.map((call) => <TableRow key={call.invocationId}><TableCell>{call.nodeId}</TableCell><TableCell>{call.providerProfileId}<br /><Typography variant="caption">{call.modelName}</Typography></TableCell><TableCell>{call.reasoningEffort}</TableCell><TableCell>{call.inputTokens} / {call.outputTokens}</TableCell><TableCell>{call.latencyMilliseconds === null ? '-' : `${call.latencyMilliseconds} ms`}</TableCell><TableCell><Chip size="small" color={statusColor(call.status)} label={statusLabel(call.status)} /></TableCell></TableRow>)}</TableBody></Table></Paper>
    </>}
  </Stack>;
}

function Summary({ label, value }: { label: string; value: string }) { return <Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" fontWeight={700} sx={{ wordBreak: 'break-all' }}>{value}</Typography></Box>; }
async function optionalAutomation(runId: string): Promise<TradeAutomationDetail | null> { try { return await api.tradeAutomation(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }
async function optionalForecast(runId: string): Promise<ResearchForecast | null> { try { return await api.researchForecast(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }
async function optionalResearchCase(runId: string): Promise<ResearchCase | null> { try { return await api.researchCase(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }
