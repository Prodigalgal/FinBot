import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, Divider, LinearProgress, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useMemo, useRef, useState } from 'react';

import { ApiError, api } from './api';
import { ResearchTurnCard, orderedResearchTurns } from './ResearchTurnCard';
import { TradingExecutionDetail } from './TradingExecutionDetail';
import { ForecastPanel } from './ForecastPanel';
import type { ResearchForecast, ResearchHistoryDetail, ResearchLaunch, TaskRecord, TradeAutomationDetail, WorkflowDefinitionSummary, WorkflowEvent, WorkflowRun } from './types';
import { ErrorBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

const eventTypes = ['workflow.accepted', 'workflow.stage.started', 'workflow.progressed', 'workflow.ai.text.delta', 'workflow.agent.message', 'workflow.completed', 'workflow.failed'];

export function ResearchPage({ initialQuestion, initialLaunch }: { initialQuestion?: string; initialLaunch?: ResearchLaunch | null }) {
  const [question, setQuestion] = useState(initialQuestion || '分析当前默认自选产品的市场方向、主要证据、反方风险和可执行的模拟交易建议');
  const [workflows, setWorkflows] = useState<WorkflowDefinitionSummary[]>([]);
  const [workflowVersionId, setWorkflowVersionId] = useState('');
  const [launch, setLaunch] = useState<ResearchLaunch | null>(initialLaunch || null);
  const [run, setRun] = useState<WorkflowRun | null>(null);
  const [task, setTask] = useState<TaskRecord | null>(null);
  const [events, setEvents] = useState<Array<{ type: string; event: WorkflowEvent }>>([]);
  const [detail, setDetail] = useState<ResearchHistoryDetail | null>(null);
  const [automation, setAutomation] = useState<TradeAutomationDetail | null>(null);
  const [forecast, setForecast] = useState<ResearchForecast | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const sourceRef = useRef<EventSource | null>(null);

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
    setBusy(true); setError(null); setEvents([]); setDetail(null); setAutomation(null); setForecast(null); setRun(null); setTask(null);
    try {
      const launched = await api.instantResearch(question.trim(), workflowVersionId || null, crypto.randomUUID());
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

  useEffect(() => {
    if (!launch) return;
    let active = true;
    let terminal = false;
    let timer: number | undefined;
    const refresh = async () => {
      if (terminal) return;
      try {
        const [workflow, backgroundTask] = await Promise.all([api.workflow(launch.runId), api.task(launch.taskId)]);
        if (!active) return;
        setRun(workflow); setTask(backgroundTask);
        if (['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'].includes(workflow.status)) {
          terminal = true;
          if (timer !== undefined) window.clearInterval(timer);
          sourceRef.current?.close();
          const [researchDetail, executionDetail, researchForecast] = await Promise.all([
            api.researchDetail(launch.runId), optionalAutomation(launch.runId), optionalForecast(launch.runId),
          ]);
          setDetail(researchDetail); setAutomation(executionDetail); setForecast(researchForecast);
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

  return (
    <Stack spacing={2.5}>
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Stack spacing={1.5}>
          <TextField multiline minRows={4} value={question} onChange={(event) => setQuestion(event.target.value)} label="研究问题" inputProps={{ maxLength: 2000 }} />
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1.25}><Typography variant="caption" color="text.secondary">{question.length} / 2000</Typography><TextField select size="small" label="研究工作流" value={workflowVersionId} onChange={(event) => setWorkflowVersionId(event.target.value)} sx={{ minWidth: 260 }}><MenuItem value="">系统默认工作流</MenuItem>{workflows.map((workflow) => <MenuItem key={workflow.definitionId} value={workflow.publishedVersionId || ''}>{workflow.name} · v{workflow.publishedVersionNumber}{workflow.active ? ' · 已激活' : ''}</MenuItem>)}</TextField><Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || question.trim().length === 0} onClick={() => void start()}>{busy ? '正在受理' : '发起研究'}</Button></Stack>
        </Stack>
      </Paper>
      {error !== null && <ErrorBlock error={error} />}
      {launch && <>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between"><Box><Typography fontWeight={700}>{run?.requestSummary || question}</Typography><Typography variant="caption" color="text.secondary">{launch.runId} · 任务 {launch.taskId}</Typography></Box><Stack direction="row" spacing={1}><Chip size="small" color={statusColor(run?.status || launch.workflowStatus)} label={statusLabel(run?.status || launch.workflowStatus)} /><Chip size="small" color={statusColor(task?.status || launch.taskStatus)} label={`任务 ${statusLabel(task?.status || launch.taskStatus)}`} /></Stack></Stack>
          <LinearProgress variant="determinate" value={progress} sx={{ mt: 2 }} />
          {task?.errorMessage && <Alert severity="error" sx={{ mt: 2 }}>{task.errorCode}: {task.errorMessage}</Alert>}
        </Paper>
        <Box><SectionTitle title="实时阶段" action={<Button size="small" startIcon={<RefreshIcon />} onClick={() => launch && Promise.all([api.researchDetail(launch.runId), optionalAutomation(launch.runId), optionalForecast(launch.runId)]).then(([researchDetail, executionDetail, researchForecast]) => { setDetail(researchDetail); setAutomation(executionDetail); setForecast(researchForecast); }).catch(setError)}>刷新详情</Button>} />
          <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
            {events.map(({ type, event }, index) => <Stack key={event.sequence} direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ px: 2, py: 1.25, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Typography variant="caption" color="text.secondary" sx={{ width: 70 }}>#{event.sequence}</Typography><Box sx={{ flex: 1, minWidth: 0 }}><Typography fontWeight={700}>{eventTitle(type, event)}</Typography><Typography variant="body2" color="text.secondary">{eventSummary(event)}</Typography></Box><Typography variant="caption" color="text.secondary">{formatTime(event.occurredAt)}</Typography></Stack>)}
            {events.length === 0 && <Box sx={{ p: 3, color: 'text.secondary' }}>等待流水线事件</Box>}
          </Paper>
        </Box>
        {detail && <ResearchResult detail={detail} automation={automation} forecast={forecast} />}
      </>}
    </Stack>
  );
}

function ResearchResult({ detail, automation, forecast }: { detail: ResearchHistoryDetail; automation: TradeAutomationDetail | null; forecast: ResearchForecast | null }) {
  return <Stack spacing={2}><SectionTitle title="结构化研究结果" />
    {forecast && <ForecastPanel forecast={forecast} />}
    {orderedResearchTurns(detail.agentTurns).map((turn) => <ResearchTurnCard key={turn.messageId} turn={turn} />)}
    {detail.quantRuns.map((quant) => <Paper key={quant.researchRunId} variant="outlined" sx={{ p: 2 }}><Typography fontWeight={700}>量化验证 · {statusLabel(quant.status)}</Typography><Typography variant="body2" color="text.secondary">{quant.strategyId} {quant.strategyVersion} · {quant.observationCount} 条观测</Typography><Typography component="pre" variant="caption" sx={{ whiteSpace: 'pre-wrap', mt: 1 }}>{prettyJson(quant.metricsJson)}</Typography></Paper>)}
    {automation && <TradingExecutionDetail detail={automation} />}
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
