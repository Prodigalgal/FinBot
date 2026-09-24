import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { Alert, Box, Button, Divider, LinearProgress, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useMemo, useRef, useState } from 'react';

import { ApiError, api } from './api';
import { ResearchTurnCard, orderedResearchTurns } from './ResearchTurnCard';
import { TradingExecutionDetail } from './TradingExecutionDetail';
import { ForecastPanel } from './ForecastPanel';
import { DebateProtocolPanel } from './DebateProtocolPanel';
import { ResearchCasePanel } from './ResearchCasePanel';
import type { ResearchCase, ResearchForecast, ResearchHistoryDetail, ResearchLaunch, TaskRecord, TradeAutomationDetail, WorkflowDefinitionSummary, WorkflowEvent, WorkflowRun } from './types';
import { CopyableText, ErrorBlock, SectionTitle, StatusBadge, formatTime, statusLabel } from './ui';

const eventTypes = ['workflow.accepted', 'workflow.stage.started', 'workflow.progressed', 'workflow.ai.text.delta', 'workflow.agent.message', 'workflow.completed', 'workflow.failed'];
const previewStages = ['信息收集', 'AI 清洗', '多 Agent 压缩', '多轮辩论', '走势预测', '模拟验证', '生成报告'];

export function ResearchPage({ initialQuestion, initialLaunch }: { initialQuestion?: string; initialLaunch?: ResearchLaunch | null }) {
  const [question, setQuestion] = useState(initialQuestion || '分析当前默认自选产品的市场方向、主要证据、反方风险和可执行的模拟交易建议');
  const [workflows, setWorkflows] = useState<WorkflowDefinitionSummary[]>([]);
  const [workflowVersionId, setWorkflowVersionId] = useState('');
  const [demoWorkflowVersionId, setDemoWorkflowVersionId] = useState('');
  const [launch, setLaunch] = useState<ResearchLaunch | null>(initialLaunch || null);
  const [run, setRun] = useState<WorkflowRun | null>(null);
  const [task, setTask] = useState<TaskRecord | null>(null);
  const [events, setEvents] = useState<Array<{ type: string; event: WorkflowEvent }>>([]);
  const [demoEvents, setDemoEvents] = useState<Array<{ type: string; event: WorkflowEvent }>>([]);
  const [detail, setDetail] = useState<ResearchHistoryDetail | null>(null);
  const [demoDetail, setDemoDetail] = useState<ResearchHistoryDetail | null>(null);
  const [researchCase, setResearchCase] = useState<ResearchCase | null>(null);
  const [automation, setAutomation] = useState<TradeAutomationDetail | null>(null);
  const [forecast, setForecast] = useState<ResearchForecast | null>(null);
  const [demoForecast, setDemoForecast] = useState<ResearchForecast | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const sourceRef = useRef<EventSource | null>(null);
  const demoSourceRef = useRef<EventSource | null>(null);

  useEffect(() => { if (initialQuestion) setQuestion(initialQuestion); }, [initialQuestion]);
  useEffect(() => { if (initialLaunch) setLaunch(initialLaunch); }, [initialLaunch]);
  useEffect(() => {
    api.workflowDefinitions().then((items) => {
      const published = items.filter((item) => item.publishedVersionId !== null);
      setWorkflows(published);
      setWorkflowVersionId((current) => current || published.find((item) => item.active)?.publishedVersionId || published[0]?.publishedVersionId || '');
    }).catch(setError);
  }, []);

  const start = async () => {
    setBusy(true); setError(null); setEvents([]); setDemoEvents([]); setDetail(null); setDemoDetail(null); setResearchCase(null); setAutomation(null); setForecast(null); setDemoForecast(null); setRun(null); setTask(null);
    try {
      const launched = await api.instantResearch(
        question.trim(),
        workflowVersionId || null,
        demoWorkflowVersionId || null,
        crypto.randomUUID(),
      );
      setLaunch(launched);
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };

  useEffect(() => {
    if (!launch) return;
    sourceRef.current?.close();
    const source = new EventSource(api.workflowEventsUrl(launch.runId), { withCredentials: true });
    sourceRef.current = source;
    eventTypes.forEach((type) => source.addEventListener(type, (raw) => {
      const message = raw as MessageEvent<string>;
      try {
        const event = JSON.parse(message.data) as WorkflowEvent;
        setEvents((current) => current.some((item) => item.event.sequence === event.sequence) ? current : [...current, { type, event }]);
      } catch { /* Ignore malformed transport events. */ }
    }));
    source.onerror = () => { /* EventSource performs bounded server-directed reconnects. */ };
    return () => source.close();
  }, [launch]);

  const demoRunId = researchCase?.segments.find((segment) => segment.segmentType === 'DEMO_AUTOTRADE')?.workflowRunId || null;

  useEffect(() => {
    demoSourceRef.current?.close();
    if (!demoRunId) return;
    const source = new EventSource(api.workflowEventsUrl(demoRunId), { withCredentials: true });
    demoSourceRef.current = source;
    eventTypes.forEach((type) => source.addEventListener(type, (raw) => {
      const message = raw as MessageEvent<string>;
      try {
        const event = JSON.parse(message.data) as WorkflowEvent;
        setDemoEvents((current) => current.some((item) => item.event.sequence === event.sequence) ? current : [...current, { type, event }]);
      } catch { /* Ignore malformed transport events. */ }
    }));
    source.onerror = () => { /* EventSource performs bounded server-directed reconnects. */ };
    return () => source.close();
  }, [demoRunId]);

  useEffect(() => {
    if (!launch) return;
    let active = true;
    let terminal = false;
    let timer: number | undefined;
    const refresh = async () => {
      if (terminal) return;
      try {
        const [workflow, backgroundTask, nextCase] = await Promise.all([api.workflow(launch.runId), api.task(launch.taskId), optionalResearchCase(launch.runId)]);
        if (!active) return;
        setRun(workflow); setTask(backgroundTask); setResearchCase(nextCase);
        if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(backgroundTask.status)) {
          terminal = true;
          if (timer !== undefined) window.clearInterval(timer);
          sourceRef.current?.close();
          demoSourceRef.current?.close();
          const finalCase = nextCase || await optionalResearchCase(launch.runId);
          const demoRunId = finalCase?.segments.find((segment) => segment.segmentType === 'DEMO_AUTOTRADE')?.workflowRunId || null;
          const [researchDetail, executionDetail, researchForecast, nextDemoDetail, nextDemoForecast] = await Promise.all([
            api.researchDetail(launch.runId), optionalAutomation(demoRunId || launch.runId), optionalForecast(launch.runId),
            demoRunId ? optionalResearchDetail(demoRunId) : Promise.resolve(null), demoRunId ? optionalForecast(demoRunId) : Promise.resolve(null),
          ]);
          setResearchCase(finalCase); setDetail(researchDetail); setDemoDetail(nextDemoDetail); setAutomation(executionDetail); setForecast(researchForecast); setDemoForecast(nextDemoForecast);
        }
      } catch (cause) { if (active) setError(cause); }
    };
    void refresh();
    timer = window.setInterval(() => void refresh(), 3000);
    return () => { active = false; if (timer !== undefined) window.clearInterval(timer); };
  }, [launch]);

  const progress = useMemo(() => events.reduce((maximum, item) => {
    const value = item.event.percentage;
    return typeof value === 'number' ? Math.max(maximum, value) : maximum;
  }, run?.status === 'COMPLETED' ? 100 : 0), [events, run]);

  const refreshDetails = async () => {
    if (!launch) return;
    const currentCase = await optionalResearchCase(launch.runId);
    const currentDemoRunId = currentCase?.segments.find((segment) => segment.segmentType === 'DEMO_AUTOTRADE')?.workflowRunId || null;
    const [researchDetail, executionDetail, researchForecast, nextDemoDetail, nextDemoForecast] = await Promise.all([
      api.researchDetail(launch.runId),
      optionalAutomation(currentDemoRunId || launch.runId),
      optionalForecast(launch.runId),
      currentDemoRunId ? optionalResearchDetail(currentDemoRunId) : Promise.resolve(null),
      currentDemoRunId ? optionalForecast(currentDemoRunId) : Promise.resolve(null),
    ]);
    setResearchCase(currentCase); setDetail(researchDetail); setDemoDetail(nextDemoDetail); setAutomation(executionDetail); setForecast(researchForecast); setDemoForecast(nextDemoForecast);
  };

  return (
    <Stack spacing={2.5}>
      <Paper variant="outlined" sx={{ p: 2.5, borderRadius: '10px' }}>
        <Stack spacing={2}>
          <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={2}>
            <Box>
              <Typography variant="subtitle1" fontWeight={800}>发起智能投研</Typography>
              <Typography variant="caption" color="text.secondary">多智能体双盲辩论 · 量化指标验证 · 模拟盘执行</Typography>
            </Box>
            <Typography variant="caption" color="text.secondary" sx={{ fontFeatureSettings: '"tnum"' }}>{question.length} / 2000</Typography>
          </Stack>
          <TextField multiline minRows={3} value={question} onChange={(event) => setQuestion(event.target.value)} label="研究问题" inputProps={{ maxLength: 2000 }} />
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'minmax(0, 1fr) minmax(0, 1fr) auto' }, gap: 1.5, alignItems: 'stretch' }}>
            <TextField select size="small" label="实盘研究工作流" value={workflowVersionId} onChange={(event) => setWorkflowVersionId(event.target.value)} sx={{ minWidth: 0, '& .MuiOutlinedInput-root': { minHeight: 44 } }}><MenuItem value="">系统默认工作流</MenuItem>{workflows.map((workflow) => <MenuItem key={workflow.definitionId} value={workflow.publishedVersionId || ''}>{workflow.name} · v{workflow.publishedVersionNumber}{workflow.active ? ' · 已激活' : ''}</MenuItem>)}</TextField>
            <TextField select size="small" label="模拟验证工作流" value={demoWorkflowVersionId} onChange={(event) => setDemoWorkflowVersionId(event.target.value)} sx={{ minWidth: 0, '& .MuiOutlinedInput-root': { minHeight: 44 } }}><MenuItem value="">与实盘工作流相同</MenuItem>{workflows.map((workflow) => <MenuItem key={workflow.definitionId} value={workflow.publishedVersionId || ''}>{workflow.name} · v{workflow.publishedVersionNumber}</MenuItem>)}</TextField>
            <Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || question.trim().length === 0} onClick={() => void start()} sx={{ minHeight: 44, px: 3, fontWeight: 700, whiteSpace: 'nowrap' }}>{busy ? '正在受理' : '发起研究'}</Button>
          </Box>
        </Stack>
      </Paper>
      {error !== null && <ErrorBlock error={error} />}
      {!launch && <ResearchProcessPreview />}
      {launch && <>
        <Paper variant="outlined" sx={{ p: 2.25, borderRadius: '10px', bgcolor: 'rgba(29, 78, 216, 0.015)' }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }}>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography fontWeight={800} sx={{ fontSize: '1rem', mb: 0.5 }}>{run?.requestSummary || question}</Typography>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="caption" color="text.secondary">Run ID:</Typography>
                <CopyableText text={launch.runId} />
                <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>· 任务 ID:</Typography>
                <CopyableText text={launch.taskId} />
              </Stack>
            </Box>
            <Stack direction="row" spacing={1} alignItems="center" flexShrink={0}>
              <StatusBadge status={run?.status || launch.workflowStatus} />
              <StatusBadge status={task?.status || launch.taskStatus} label={`任务 ${statusLabel(task?.status || launch.taskStatus)}`} />
            </Stack>
          </Stack>
          <Box sx={{ mt: 2 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 0.5 }}>
              <Typography variant="caption" color="text.secondary">流水线进度</Typography>
              <Typography variant="caption" fontWeight={700} sx={{ fontFeatureSettings: '"tnum"' }}>{Math.round(progress)}%</Typography>
            </Stack>
            <LinearProgress
              variant="determinate"
              value={progress}
              sx={{
                height: 8,
                borderRadius: '4px',
                bgcolor: 'rgba(15, 23, 42, 0.06)',
                '& .MuiLinearProgress-bar': {
                  borderRadius: '4px',
                  bgcolor: progress === 100 ? '#16a34a' : 'primary.main',
                },
              }}
            />
          </Box>
          {task?.errorMessage && <Alert severity="error" sx={{ mt: 2, borderRadius: '8px' }}>{task.errorCode}: {task.errorMessage}</Alert>}
        </Paper>
        {researchCase && <ResearchCasePanel researchCase={researchCase} />}
        <Box>
          <SectionTitle title="实时阶段" action={<Button size="small" startIcon={<RefreshIcon />} onClick={() => void refreshDetails().catch(setError)}>刷新详情</Button>} />
          <Typography variant="subtitle2" sx={{ mb: 1, fontWeight: 700, fontSize: '0.85rem' }}>实盘研究事件流</Typography>
          <Paper variant="outlined" sx={{ overflow: 'hidden', borderRadius: '10px' }}>
            {events.map(({ type, event }, index) => (
              <Stack
                key={event.sequence}
                direction={{ xs: 'column', sm: 'row' }}
                spacing={2}
                alignItems={{ sm: 'center' }}
                sx={{
                  px: 2,
                  py: 1.25,
                  borderTop: index ? '1px solid' : 0,
                  borderColor: 'divider',
                  transition: 'background-color 0.15s ease',
                  '&:hover': { bgcolor: 'rgba(15, 23, 42, 0.015)' },
                }}
              >
                <Box
                  sx={{
                    width: 44,
                    py: 0.25,
                    textAlign: 'center',
                    borderRadius: '4px',
                    bgcolor: 'surfaceMuted',
                    fontSize: '0.72rem',
                    fontWeight: 700,
                    fontFeatureSettings: '"tnum"',
                    color: 'text.secondary',
                    flexShrink: 0,
                  }}
                >
                  #{event.sequence}
                </Box>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography fontWeight={700} sx={{ fontSize: '0.88rem' }}>{eventTitle(type, event)}</Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ fontSize: '0.82rem', mt: 0.25 }}>{eventSummary(event)}</Typography>
                </Box>
                <Typography variant="caption" color="text.secondary" sx={{ fontFeatureSettings: '"tnum"', flexShrink: 0 }}>
                  {formatTime(event.occurredAt)}
                </Typography>
              </Stack>
            ))}
            {events.length === 0 && <Box sx={{ p: 4, textAlign: 'center', color: 'text.secondary', fontSize: '0.85rem' }}>等待流水线事件推送中...</Box>}
          </Paper>
          {demoRunId && <>
            <Typography variant="subtitle2" sx={{ mt: 2.5, mb: 1, fontWeight: 700, fontSize: '0.85rem' }}>模拟盘独立分析与执行事件</Typography>
            <Paper variant="outlined" sx={{ overflow: 'hidden', borderRadius: '10px' }}>
              {demoEvents.map(({ type, event }, index) => (
                <Stack
                  key={event.sequence}
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={2}
                  alignItems={{ sm: 'center' }}
                  sx={{
                    px: 2,
                    py: 1.25,
                    borderTop: index ? '1px solid' : 0,
                    borderColor: 'divider',
                    transition: 'background-color 0.15s ease',
                    '&:hover': { bgcolor: 'rgba(15, 23, 42, 0.015)' },
                  }}
                >
                  <Box
                    sx={{
                      width: 44,
                      py: 0.25,
                      textAlign: 'center',
                      borderRadius: '4px',
                      bgcolor: 'surfaceMuted',
                      fontSize: '0.72rem',
                      fontWeight: 700,
                      fontFeatureSettings: '"tnum"',
                      color: 'text.secondary',
                      flexShrink: 0,
                    }}
                  >
                    #{event.sequence}
                  </Box>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography fontWeight={700} sx={{ fontSize: '0.88rem' }}>{eventTitle(type, event)}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ fontSize: '0.82rem', mt: 0.25 }}>{eventSummary(event)}</Typography>
                  </Box>
                  <Typography variant="caption" color="text.secondary" sx={{ fontFeatureSettings: '"tnum"', flexShrink: 0 }}>
                    {formatTime(event.occurredAt)}
                  </Typography>
                </Stack>
              ))}
              {demoEvents.length === 0 && <Box sx={{ p: 4, textAlign: 'center', color: 'text.secondary', fontSize: '0.85rem' }}>模拟分支已创建，等待流水线事件推送...</Box>}
            </Paper>
          </>}
        </Box>
        {detail && <ResearchResult detail={detail} demoDetail={demoDetail} automation={automation} forecast={forecast} demoForecast={demoForecast} />}
      </>}
    </Stack>
  );
}

function ResearchProcessPreview() {
  return (
    <Box>
      <SectionTitle title="投研流水线架构" />
      <Paper variant="outlined" sx={{ p: 2.5, borderRadius: '10px' }}>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)', lg: 'repeat(7, 1fr)' },
            gap: 1.5,
          }}
        >
          {previewStages.map((stage, index) => (
            <Box
              key={stage}
              sx={{
                p: 1.5,
                borderRadius: '8px',
                bgcolor: index === 0 ? 'rgba(29, 78, 216, 0.05)' : 'surfaceMuted',
                border: '1px solid',
                borderColor: index === 0 ? 'rgba(29, 78, 216, 0.3)' : 'divider',
                position: 'relative',
              }}
            >
              <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 0.75 }}>
                <Box
                  sx={{
                    width: 22,
                    height: 22,
                    borderRadius: '50%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: '0.72rem',
                    fontWeight: 800,
                    bgcolor: index === 0 ? 'primary.main' : 'rgba(15, 23, 42, 0.1)',
                    color: index === 0 ? '#ffffff' : 'text.secondary',
                    fontFeatureSettings: '"tnum"',
                  }}
                >
                  {index + 1}
                </Box>
                <Typography variant="caption" sx={{ fontWeight: 700, color: index === 0 ? 'primary.main' : 'text.secondary', letterSpacing: '0.04em' }}>
                  STAGE 0{index + 1}
                </Typography>
              </Stack>
              <Typography variant="body2" fontWeight={700} sx={{ color: index === 0 ? 'primary.main' : 'text.primary' }}>
                {stage}
              </Typography>
            </Box>
          ))}
        </Box>
      </Paper>
    </Box>
  );
}

function ResearchResult({ detail, demoDetail, automation, forecast, demoForecast }: { detail: ResearchHistoryDetail; demoDetail: ResearchHistoryDetail | null; automation: TradeAutomationDetail | null; forecast: ResearchForecast | null; demoForecast: ResearchForecast | null }) {
  return <Stack spacing={2}><SectionTitle title="结构化研究结果" />
    <Typography variant="h3">实盘研究结论</Typography>
    {forecast && <ForecastPanel forecast={forecast} />}
    {detail.debateProtocol && <DebateProtocolPanel trace={detail.debateProtocol} />}
    {orderedResearchTurns(detail.agentTurns).map((turn) => <ResearchTurnCard key={turn.messageId} turn={turn} />)}
    {detail.quantRuns.map((quant) => <Paper key={quant.researchRunId} variant="outlined" sx={{ p: 2 }}><Typography fontWeight={700}>量化验证 · {statusLabel(quant.status)}</Typography><Typography variant="body2" color="text.secondary">{quant.strategyId} {quant.strategyVersion} · {quant.observationCount} 条观测</Typography><Typography component="pre" variant="caption" sx={{ whiteSpace: 'pre-wrap', mt: 1 }}>{prettyJson(quant.metricsJson)}</Typography></Paper>)}
    {demoDetail && <><Divider /><Typography variant="h3">模拟盘独立分析与辩论</Typography>{demoForecast && <ForecastPanel forecast={demoForecast} />}{demoDetail.debateProtocol && <DebateProtocolPanel trace={demoDetail.debateProtocol} />}{orderedResearchTurns(demoDetail.agentTurns).map((turn) => <ResearchTurnCard key={turn.messageId} turn={turn} />)}</>}
    {automation && <><Typography variant="h3">模拟盘最终执行</Typography><TradingExecutionDetail detail={automation} /></>}
  </Stack>;
}

function eventTitle(type: string, event: WorkflowEvent): string {
  if (type === 'workflow.agent.message') return String(event.roleName || 'AI 角色输出');
  if (type === 'workflow.stage.started') return `进入阶段：${String(event.stage || '')}`;
  return ({ 'workflow.accepted': '研究已受理', 'workflow.progressed': '流程进度', 'workflow.ai.text.delta': 'AI 流式输出', 'workflow.completed': '研究完成', 'workflow.failed': '研究失败' } as Record<string, string>)[type] || type;
}
function eventSummary(event: WorkflowEvent): string { return String(event.summary || event.message || event.errorMessage || event.errorCode || '状态已更新'); }
function prettyJson(value: string): string { try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
async function optionalAutomation(runId: string): Promise<TradeAutomationDetail | null> { try { return await api.tradeAutomation(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }
async function optionalForecast(runId: string): Promise<ResearchForecast | null> { try { return await api.researchForecast(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }
async function optionalResearchCase(runId: string): Promise<ResearchCase | null> { try { return await api.researchCase(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }
async function optionalResearchDetail(runId: string): Promise<ResearchHistoryDetail | null> { try { return await api.researchDetail(runId); } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error; } }
