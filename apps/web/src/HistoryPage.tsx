import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import ReplayIcon from '@mui/icons-material/Replay';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import SaveIcon from '@mui/icons-material/Save';
import { Alert, Box, Button, Divider, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { ApiError, api } from './api';
import { ResearchTurnCard, orderedResearchTurns } from './ResearchTurnCard';
import { TradingExecutionDetail } from './TradingExecutionDetail';
import { ForecastPanel } from './ForecastPanel';
import { ResearchCasePanel } from './ResearchCasePanel';
import { DebateProtocolPanel } from './DebateProtocolPanel';
import type { ResearchCase, ResearchComparison, ResearchFeedback, ResearchForecast, ResearchHistoryDetail, ResearchLaunch, ResearchSummary, TradeAutomationDetail } from './types';
import { CopyableText, EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, StatusBadge, formatTime, statusLabel } from './ui';

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

  const refresh = () => Promise.all([api.researchHistory(undefined, 100), api.researchFeedback(100)]).then(([items, savedFeedback]) => {
    setRuns(items);
    setFeedback(savedFeedback);
    setLeftRunId((current) => current || items[1]?.runId || items[0]?.runId || '');
    setRightRunId((current) => current || items[0]?.runId || '');
  }).catch(setError);

  useEffect(() => { void refresh(); }, []);

  const select = async (runId: string) => {
    setBusy(true); setError(null);
    try {
      const nextCase = await optionalResearchCase(runId);
      const demoRunId = nextCase?.segments.find((segment) => segment.segmentType === 'DEMO_AUTOTRADE')?.workflowRunId || runId;
      const [next, execution, researchForecast] = await Promise.all([api.researchDetail(runId), optionalAutomation(demoRunId), optionalForecast(runId)]);
      setResearchCase(nextCase); setDetail(next); setAutomation(execution); setForecast(researchForecast);
      const saved = feedback.find((item) => item.workflowRunId === runId);
      setRating(saved?.rating || 'HELPFUL'); setEffectiveness(saved?.effectiveness || 'UNKNOWN'); setNote(saved?.note || '');
      setResumeNodeId(next.checkpoints.find((item) => item.status === 'FAILED')?.nodeId || '');
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };

  const action = async (kind: 'replay' | 'resume') => {
    if (!detail) return;
    setBusy(true); setError(null);
    try {
      const launch = kind === 'replay' ? await api.replayResearch(detail.summary.runId, crypto.randomUUID()) : await api.resumeResearch(detail.summary.runId, crypto.randomUUID(), resumeNodeId || undefined);
      onOpenRun?.(launch); refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };

  const compare = async () => {
    if (!leftRunId || !rightRunId || leftRunId === rightRunId) return;
    setBusy(true); setError(null);
    try { setComparison(await api.compareResearch(leftRunId, rightRunId)); } catch (cause) { setError(cause); } finally { setBusy(false); }
  };

  const saveFeedback = async () => {
    if (!detail) return;
    setBusy(true); setError(null);
    try {
      const current = feedback.find((item) => item.workflowRunId === detail.summary.runId);
      const saved = await api.saveResearchFeedback(detail.summary.runId, { rating, effectiveness, note, expectedVersion: current?.version ?? null });
      setFeedback((items) => [...items.filter((item) => item.workflowRunId !== saved.workflowRunId), saved]);
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };

  if (error && !runs) return <ErrorBlock error={error} />;
  if (!runs) return <LoadingBlock />;

  return (
    <Stack spacing={2.5}>
      {error !== null && <ErrorBlock error={error} />}
      <Paper variant="outlined" sx={{ p: 2.25, borderRadius: '10px' }}>
        <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}>
          <TextField select size="small" label="基准运行" value={leftRunId} onChange={(event) => setLeftRunId(event.target.value)} fullWidth>
            {runs.map((run) => <MenuItem key={run.runId} value={run.runId}>{run.requestSummary} · {formatTime(run.acceptedAt)}</MenuItem>)}
          </TextField>
          <TextField select size="small" label="对比运行" value={rightRunId} onChange={(event) => setRightRunId(event.target.value)} fullWidth>
            {runs.map((run) => <MenuItem key={run.runId} value={run.runId}>{run.requestSummary} · {formatTime(run.acceptedAt)}</MenuItem>)}
          </TextField>
          <Button variant="outlined" startIcon={<CompareArrowsIcon />} disabled={leftRunId === rightRunId || busy} onClick={() => void compare()} sx={{ whiteSpace: 'nowrap', minHeight: 40, px: 2.5 }}>
            运行对比
          </Button>
        </Stack>
      </Paper>

      {comparison && (
        <Paper variant="outlined" sx={{ p: 2.25, borderRadius: '10px' }}>
          <Stack spacing={1.75}>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' }, gap: 1.5 }}>
              <Summary label="Token 差异（右 - 左）" value={`${comparison.inputTokenDelta + comparison.outputTokenDelta}`} />
              <Summary label="成本差异" value={`$${Number(comparison.costDeltaUsd).toFixed(6)}`} />
              <Summary label="耗时差异" value={comparison.durationDeltaSeconds === null ? '-' : `${comparison.durationDeltaSeconds} 秒`} />
              <Summary label="变化节点数" value={String(comparison.nodes.filter((node) => node.changed).length)} />
            </Box>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, gap: 1.5, mt: 0.5 }}>
              <Box sx={{ p: 1.75, borderRadius: '8px', bgcolor: 'surfaceMuted', border: '1px solid', borderColor: 'divider' }}>
                <Typography variant="caption" sx={{ fontWeight: 700, color: 'text.secondary', display: 'block', mb: 0.5 }}>基准结论</Typography>
                <Typography variant="body2">{comparison.leftConclusion}</Typography>
              </Box>
              <Box sx={{ p: 1.75, borderRadius: '8px', bgcolor: 'surfaceMuted', border: '1px solid', borderColor: 'divider' }}>
                <Typography variant="caption" sx={{ fontWeight: 700, color: 'text.secondary', display: 'block', mb: 0.5 }}>对比结论</Typography>
                <Typography variant="body2">{comparison.rightConclusion}</Typography>
              </Box>
            </Box>
            {comparison.nodes.filter((node) => node.changed).map((node) => (
              <Alert key={`${node.nodeId}-${node.round}`} severity="info" sx={{ borderRadius: '8px' }}>
                {node.nodeId} 第 {node.round} 轮：{statusLabel(node.leftStatus)} → {statusLabel(node.rightStatus)}
              </Alert>
            ))}
          </Stack>
        </Paper>
      )}

      <Paper variant="outlined" sx={{ overflow: 'auto', borderRadius: '10px' }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>时间</TableCell>
              <TableCell>研究问题 / Run ID</TableCell>
              <TableCell>类型</TableCell>
              <TableCell>AI 用量与成本</TableCell>
              <TableCell>状态</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {runs.map((run) => (
              <TableRow
                key={run.runId}
                hover
                selected={detail?.summary.runId === run.runId}
                onClick={() => void select(run.runId)}
                sx={{
                  cursor: 'pointer',
                  '&.Mui-selected': {
                    bgcolor: 'rgba(29, 78, 216, 0.05) !important',
                    borderLeft: '3px solid #1d4ed8',
                  },
                }}
              >
                <TableCell sx={{ whiteSpace: 'nowrap', fontFeatureSettings: '"tnum"', fontSize: '0.82rem' }}>
                  {formatTime(run.acceptedAt)}
                </TableCell>
                <TableCell>
                  <Typography variant="body2" fontWeight={700} sx={{ mb: 0.25 }}>{run.requestSummary}</Typography>
                  <CopyableText text={run.runId} />
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap', fontSize: '0.82rem' }}>
                  {run.workflowType === 'SCHEDULED_RESEARCH' ? '定时任务' : '即时发起'}
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap', fontFeatureSettings: '"tnum"' }}>
                  <Typography variant="body2" sx={{ fontFeatureSettings: '"tnum"', fontWeight: 600 }}>
                    {run.inputTokens + run.outputTokens} tokens
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ fontFeatureSettings: '"tnum"' }}>
                    ${Number(run.costUsd).toFixed(4)}
                  </Typography>
                </TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  <StatusBadge status={run.status} size="small" />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {runs.length === 0 && <EmptyBlock />}
      </Paper>

      {busy && <LoadingBlock label="正在读取完整审计链" />}

      {detail && !busy && <>
        <SectionTitle
          title="运行详情与审计链"
          action={
            <Stack direction="row" spacing={1}>
              <Button size="small" startIcon={<ReplayIcon />} onClick={() => void action('replay')}>重放</Button>
              {detail.summary.status === 'FAILED' && (
                <Button size="small" variant="contained" startIcon={<RestartAltIcon />} onClick={() => void action('resume')}>
                  从失败点续跑
                </Button>
              )}
            </Stack>
          }
        />
        <Paper variant="outlined" sx={{ p: 2, borderRadius: '10px' }}>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' }, gap: 1.5 }}>
            <Summary label="工作流版本" value={detail.summary.workflowVersionId} />
            <Summary label="发起时间" value={formatTime(detail.summary.startedAt || detail.summary.acceptedAt)} />
            <Summary label="完成时间" value={formatTime(detail.summary.completedAt)} />
            <Summary label="总成本 (USD)" value={`$${Number(detail.summary.costUsd).toFixed(6)}`} />
          </Box>
        </Paper>

        {researchCase && <ResearchCasePanel researchCase={researchCase} />}
        {forecast && <ForecastPanel forecast={forecast} />}
        {detail.debateProtocol && (
          <>
            <SectionTitle title="双盲辩论协议" />
            <DebateProtocolPanel trace={detail.debateProtocol} />
          </>
        )}
        {detail.summary.status === 'FAILED' && (
          <TextField
            select
            label="失败 Checkpoint 恢复点"
            value={resumeNodeId}
            onChange={(event) => setResumeNodeId(event.target.value)}
            helperText="选择中断的节点直接恢复状态，避免全流程重新调用"
          >
            {detail.checkpoints.filter((item) => item.status === 'FAILED').map((item) => (
              <MenuItem key={`${item.nodeId}-${item.round}`} value={item.nodeId}>
                {item.displayName} · 第 {item.round} 轮 · {item.errorCode}
              </MenuItem>
            ))}
          </TextField>
        )}

        <SectionTitle title="人工复核与标注" />
        <Paper variant="outlined" sx={{ p: 2.25, borderRadius: '10px' }}>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems="center">
            <TextField select size="small" label="结果质量" value={rating} onChange={(event) => setRating(event.target.value as ResearchFeedback['rating'])} sx={{ minWidth: 160 }}>
              <MenuItem value="HELPFUL">有帮助</MenuItem>
              <MenuItem value="NEUTRAL">一般</MenuItem>
              <MenuItem value="NOT_HELPFUL">无帮助</MenuItem>
            </TextField>
            <TextField select size="small" label="实际效果" value={effectiveness} onChange={(event) => setEffectiveness(event.target.value as ResearchFeedback['effectiveness'])} sx={{ minWidth: 160 }}>
              <MenuItem value="UNKNOWN">未知</MenuItem>
              <MenuItem value="PENDING">待观察</MenuItem>
              <MenuItem value="WIN">方向正确 (WIN)</MenuItem>
              <MenuItem value="LOSS">方向错误 (LOSS)</MenuItem>
              <MenuItem value="NO_TRADE">未交易 (NO_TRADE)</MenuItem>
            </TextField>
            <TextField fullWidth size="small" label="复核备注" value={note} onChange={(event) => setNote(event.target.value)} inputProps={{ maxLength: 2000 }} />
            <Button startIcon={<SaveIcon />} variant="contained" disabled={busy} onClick={() => void saveFeedback()} sx={{ whiteSpace: 'nowrap', minHeight: 40, px: 2.5 }}>
              保存反馈
            </Button>
          </Stack>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            人工标注仅用于投研质量打分与策略自迭代，不阻断自动化流水线执行。
          </Typography>
        </Paper>

        <SectionTitle title="节点流水线 Checkpoints" />
        <Paper variant="outlined" sx={{ overflow: 'hidden', borderRadius: '10px' }}>
          {detail.checkpoints.map((checkpoint, index) => (
            <Stack
              key={`${checkpoint.nodeId}-${checkpoint.round}`}
              direction={{ xs: 'column', md: 'row' }}
              spacing={2}
              alignItems={{ md: 'center' }}
              sx={{
                p: 1.75,
                borderTop: index ? '1px solid' : 0,
                borderColor: 'divider',
                transition: 'background-color 0.15s ease',
                '&:hover': { bgcolor: 'rgba(15, 23, 42, 0.015)' },
              }}
            >
              <Box sx={{ width: { md: 220 }, flexShrink: 0 }}>
                <Typography fontWeight={700} sx={{ fontSize: '0.88rem' }}>{checkpoint.displayName}</Typography>
                <Typography variant="caption" color="text.secondary">第 {checkpoint.round} 轮 · 尝试 #{checkpoint.attempt}</Typography>
              </Box>
              <StatusBadge status={checkpoint.status} size="small" />
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" sx={{ fontSize: '0.85rem' }}>{checkpoint.resultSummary || checkpoint.errorMessage || '-'}</Typography>
                {checkpoint.errorCode && <Typography variant="caption" color="error" display="block" sx={{ mt: 0.25 }}>{checkpoint.errorCode}</Typography>}
              </Box>
            </Stack>
          ))}
        </Paper>

        <SectionTitle title="多轮博弈记录" />
        <Stack spacing={1.5}>
          {orderedResearchTurns(detail.agentTurns).map((turn) => <ResearchTurnCard key={turn.messageId} turn={turn} />)}
        </Stack>

        {automation && <TradingExecutionDetail detail={automation} />}

        <SectionTitle title="AI 模型调用审计" />
        <Paper variant="outlined" sx={{ overflow: 'auto', borderRadius: '10px' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>节点</TableCell>
                <TableCell>厂商 / 模型</TableCell>
                <TableCell>思考强度</TableCell>
                <TableCell>Token (入 / 出)</TableCell>
                <TableCell>调用耗时</TableCell>
                <TableCell>状态</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {detail.aiInvocations.map((call) => (
                <TableRow key={call.invocationId} sx={{ '&:hover': { bgcolor: 'rgba(15, 23, 42, 0.015)' } }}>
                  <TableCell sx={{ fontWeight: 600, fontSize: '0.82rem' }}>{call.nodeId}</TableCell>
                  <TableCell>
                    <Typography variant="body2" fontWeight={600} sx={{ fontSize: '0.82rem' }}>{call.providerProfileId}</Typography>
                    <Typography variant="caption" color="text.secondary">{call.modelName}</Typography>
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.82rem' }}>{call.reasoningEffort}</TableCell>
                  <TableCell sx={{ fontFeatureSettings: '"tnum"', fontFamily: 'Roboto Mono, monospace', fontSize: '0.82rem' }}>
                    {call.inputTokens} / {call.outputTokens}
                  </TableCell>
                  <TableCell sx={{ fontFeatureSettings: '"tnum"', fontSize: '0.82rem' }}>
                    {call.latencyMilliseconds === null ? '-' : `${call.latencyMilliseconds} ms`}
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={call.status} size="small" />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      </>}
    </Stack>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ p: 1.5, borderRadius: '8px', bgcolor: 'surfaceMuted', border: '1px solid', borderColor: 'divider' }}>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.25 }}>
        {label}
      </Typography>
      <Typography variant="body2" fontWeight={800} sx={{ wordBreak: 'break-all', fontFeatureSettings: '"tnum"' }}>
        {value}
      </Typography>
    </Box>
  );
}

async function optionalAutomation(runId: string): Promise<TradeAutomationDetail | null> {
  try { return await api.tradeAutomation(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; }
}

async function optionalForecast(runId: string): Promise<ResearchForecast | null> {
  try { return await api.researchForecast(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; }
}

async function optionalResearchCase(runId: string): Promise<ResearchCase | null> {
  try { return await api.researchCase(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; }
}

