import ReplayIcon from '@mui/icons-material/Replay';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import { Box, Button, Chip, Divider, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import type { ResearchHistoryDetail, ResearchLaunch, ResearchSummary } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function HistoryPage({ onOpenRun }: { onOpenRun?: (launch: ResearchLaunch) => void }) {
  const [runs, setRuns] = useState<ResearchSummary[] | null>(null);
  const [detail, setDetail] = useState<ResearchHistoryDetail | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const refresh = () => api.researchHistory(undefined, 100).then(setRuns).catch(setError);
  useEffect(() => { void refresh(); }, []);
  const select = async (runId: string) => { setBusy(true); setError(null); try { setDetail(await api.researchDetail(runId)); } catch (cause) { setError(cause); } finally { setBusy(false); } };
  const action = async (kind: 'replay' | 'resume') => {
    if (!detail) return;
    setBusy(true); setError(null);
    try {
      const launch = kind === 'replay' ? await api.replayResearch(detail.summary.runId, crypto.randomUUID()) : await api.resumeResearch(detail.summary.runId, crypto.randomUUID());
      onOpenRun?.(launch); refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  if (error && !runs) return <ErrorBlock error={error} />;
  if (!runs) return <LoadingBlock />;
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
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
      <SectionTitle title="节点与错误" />
      <Paper variant="outlined" sx={{ overflow: 'hidden' }}>{detail.checkpoints.map((checkpoint, index) => <Stack key={`${checkpoint.nodeId}-${checkpoint.round}`} direction={{ xs: 'column', md: 'row' }} spacing={2} sx={{ p: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Box sx={{ width: { md: 220 } }}><Typography fontWeight={700}>{checkpoint.displayName}</Typography><Typography variant="caption">第 {checkpoint.round} 轮 · 尝试 {checkpoint.attempt}</Typography></Box><Chip size="small" color={statusColor(checkpoint.status)} label={statusLabel(checkpoint.status)} sx={{ alignSelf: 'flex-start' }} /><Box sx={{ flex: 1 }}><Typography variant="body2">{checkpoint.resultSummary || checkpoint.errorMessage || '-'}</Typography>{checkpoint.errorCode && <Typography variant="caption" color="error">{checkpoint.errorCode}</Typography>}</Box></Stack>)}</Paper>
      <SectionTitle title="多轮辩论" />
      <Stack spacing={1.25}>{detail.agentTurns.map((turn) => <Paper key={turn.messageId} variant="outlined" sx={{ p: 2, borderLeft: turn.messageType === 'CHAIR_VERDICT' ? '4px solid' : undefined, borderColor: turn.messageType === 'CHAIR_VERDICT' ? 'primary.main' : 'divider' }}><Stack direction="row" justifyContent="space-between"><Typography fontWeight={700}>{turn.roleName}</Typography><Typography variant="caption" color="text.secondary">{turn.messageType === 'CHAIR_VERDICT' ? '主席裁决' : `第 ${turn.round} 轮 / 顺序 ${turn.turnIndex}`}</Typography></Stack><Typography sx={{ my: .75 }}>{turn.summary}</Typography><Typography variant="body2" color="text.secondary" sx={{ whiteSpace: 'pre-wrap' }}>{turn.argument}</Typography></Paper>)}</Stack>
      <SectionTitle title="AI 调用审计" />
      <Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>节点</TableCell><TableCell>厂商 / 模型</TableCell><TableCell>思考</TableCell><TableCell>Token</TableCell><TableCell>耗时</TableCell><TableCell>状态</TableCell></TableRow></TableHead><TableBody>{detail.aiInvocations.map((call) => <TableRow key={call.invocationId}><TableCell>{call.nodeId}</TableCell><TableCell>{call.providerProfileId}<br /><Typography variant="caption">{call.modelName}</Typography></TableCell><TableCell>{call.reasoningEffort}</TableCell><TableCell>{call.inputTokens} / {call.outputTokens}</TableCell><TableCell>{call.latencyMilliseconds === null ? '-' : `${call.latencyMilliseconds} ms`}</TableCell><TableCell><Chip size="small" color={statusColor(call.status)} label={statusLabel(call.status)} /></TableCell></TableRow>)}</TableBody></Table></Paper>
    </>}
  </Stack>;
}

function Summary({ label, value }: { label: string; value: string }) { return <Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" fontWeight={700} sx={{ wordBreak: 'break-all' }}>{value}</Typography></Box>; }
