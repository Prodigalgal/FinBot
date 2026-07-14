import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, Chip, Divider, LinearProgress, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useMemo, useRef, useState } from 'react';

import { api } from './api';
import type { ResearchHistoryDetail, ResearchLaunch, TaskRecord, WorkflowEvent, WorkflowRun } from './types';
import { ErrorBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

const eventTypes = ['workflow.accepted', 'workflow.stage.started', 'workflow.progressed', 'workflow.ai.text.delta', 'workflow.agent.message', 'workflow.completed', 'workflow.failed'];

export function ResearchPage({ initialQuestion, initialLaunch }: { initialQuestion?: string; initialLaunch?: ResearchLaunch | null }) {
  const [question, setQuestion] = useState(initialQuestion || '分析当前默认自选产品的市场方向、主要证据、反方风险和可执行的模拟交易建议');
  const [launch, setLaunch] = useState<ResearchLaunch | null>(initialLaunch || null);
  const [run, setRun] = useState<WorkflowRun | null>(null);
  const [task, setTask] = useState<TaskRecord | null>(null);
  const [events, setEvents] = useState<Array<{ type: string; event: WorkflowEvent }>>([]);
  const [detail, setDetail] = useState<ResearchHistoryDetail | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => { if (initialQuestion) setQuestion(initialQuestion); }, [initialQuestion]);
  useEffect(() => { if (initialLaunch) setLaunch(initialLaunch); }, [initialLaunch]);

  const start = async () => {
    setBusy(true); setError(null); setEvents([]); setDetail(null); setRun(null); setTask(null);
    try {
      const launched = await api.instantResearch(question.trim(), null, crypto.randomUUID());
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
          setDetail(await api.researchDetail(launch.runId));
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
          <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="caption" color="text.secondary">{question.length} / 2000</Typography><Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || question.trim().length === 0} onClick={() => void start()}>{busy ? '正在受理' : '发起研究'}</Button></Stack>
        </Stack>
      </Paper>
      {error !== null && <ErrorBlock error={error} />}
      {launch && <>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between"><Box><Typography fontWeight={700}>{run?.requestSummary || question}</Typography><Typography variant="caption" color="text.secondary">{launch.runId} · 任务 {launch.taskId}</Typography></Box><Stack direction="row" spacing={1}><Chip size="small" color={statusColor(run?.status || launch.workflowStatus)} label={statusLabel(run?.status || launch.workflowStatus)} /><Chip size="small" color={statusColor(task?.status || launch.taskStatus)} label={`任务 ${statusLabel(task?.status || launch.taskStatus)}`} /></Stack></Stack>
          <LinearProgress variant="determinate" value={progress} sx={{ mt: 2 }} />
          {task?.errorMessage && <Alert severity="error" sx={{ mt: 2 }}>{task.errorCode}: {task.errorMessage}</Alert>}
        </Paper>
        <Box><SectionTitle title="实时阶段" action={<Button size="small" startIcon={<RefreshIcon />} onClick={() => launch && api.researchDetail(launch.runId).then(setDetail).catch(setError)}>刷新详情</Button>} />
          <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
            {events.map(({ type, event }, index) => <Stack key={event.sequence} direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ px: 2, py: 1.25, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}><Typography variant="caption" color="text.secondary" sx={{ width: 70 }}>#{event.sequence}</Typography><Box sx={{ flex: 1, minWidth: 0 }}><Typography fontWeight={700}>{eventTitle(type, event)}</Typography><Typography variant="body2" color="text.secondary">{eventSummary(event)}</Typography></Box><Typography variant="caption" color="text.secondary">{formatTime(event.occurredAt)}</Typography></Stack>)}
            {events.length === 0 && <Box sx={{ p: 3, color: 'text.secondary' }}>等待流水线事件</Box>}
          </Paper>
        </Box>
        {detail && <ResearchResult detail={detail} />}
      </>}
    </Stack>
  );
}

function ResearchResult({ detail }: { detail: ResearchHistoryDetail }) {
  return <Stack spacing={2}><SectionTitle title="结构化研究结果" />
    {detail.agentTurns.map((turn) => <Paper key={turn.messageId} variant="outlined" sx={{ p: 2, borderLeft: turn.messageType === 'CHAIR_VERDICT' ? '4px solid' : undefined, borderLeftColor: 'primary.main' }}><Stack direction="row" justifyContent="space-between" spacing={2}><Box><Typography fontWeight={700}>{turn.roleName}</Typography><Typography variant="caption" color="text.secondary">{turn.messageType === 'CHAIR_VERDICT' ? '主席裁决' : `第 ${turn.round} 轮`}</Typography></Box>{turn.confidence !== null && <Chip size="small" label={`置信度 ${(Number(turn.confidence) * 100).toFixed(0)}%`} />}</Stack><Typography sx={{ mt: 1 }}>{turn.summary}</Typography><Typography variant="body2" color="text.secondary" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>{turn.argument}</Typography></Paper>)}
    {detail.quantRuns.map((quant) => <Paper key={quant.researchRunId} variant="outlined" sx={{ p: 2 }}><Typography fontWeight={700}>量化验证 · {statusLabel(quant.status)}</Typography><Typography variant="body2" color="text.secondary">{quant.strategyId} {quant.strategyVersion} · {quant.observationCount} 条观测</Typography><Typography component="pre" variant="caption" sx={{ whiteSpace: 'pre-wrap', mt: 1 }}>{prettyJson(quant.metricsJson)}</Typography></Paper>)}
  </Stack>;
}

function eventTitle(type: string, event: WorkflowEvent): string {
  if (type === 'workflow.agent.message') return String(event.roleName || 'AI 角色输出');
  if (type === 'workflow.stage.started') return `进入阶段：${String(event.stage || '')}`;
  return ({ 'workflow.accepted': '研究已受理', 'workflow.progressed': '流程进度', 'workflow.ai.text.delta': 'AI 流式输出', 'workflow.completed': '研究完成', 'workflow.failed': '研究失败' } as Record<string, string>)[type] || type;
}
function eventSummary(event: WorkflowEvent): string { return String(event.summary || event.message || event.errorMessage || event.errorCode || '状态已更新'); }
function prettyJson(value: string): string { try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
