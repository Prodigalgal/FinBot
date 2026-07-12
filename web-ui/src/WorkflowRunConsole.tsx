import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Collapse,
  Divider,
  LinearProgress,
  MenuItem,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import AccountTreeOutlinedIcon from '@mui/icons-material/AccountTreeOutlined';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import HistoryEduOutlinedIcon from '@mui/icons-material/HistoryEduOutlined';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import PsychologyAltOutlinedIcon from '@mui/icons-material/PsychologyAltOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';
import TimelineOutlinedIcon from '@mui/icons-material/TimelineOutlined';
import { useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type {
  CouncilMemoryRecord,
  WorkflowDirectorPlan,
  WorkflowLearningPayload,
  WorkflowRunDetail,
  WorkflowRunSummary,
} from './types';

type ConsoleTab = 'plan' | 'progress' | 'checkpoints' | 'reflection' | 'scores';

export function WorkflowRunConsole({
  templateId,
  templateName,
  defaultDepth,
  defaultRounds,
  workflowVersionId,
}: {
  templateId: string;
  templateName: string;
  defaultDepth: 'quick' | 'standard' | 'deep';
  defaultRounds: number;
  workflowVersionId?: string;
}) {
  const [query, setQuery] = useState('分析当前关注产品的机会、风险、证据缺口与失效条件');
  const [depth, setDepth] = useState(defaultDepth);
  const [rounds, setRounds] = useState(defaultRounds);
  const [plan, setPlan] = useState<WorkflowDirectorPlan | null>(null);
  const [run, setRun] = useState<WorkflowRunDetail | null>(null);
  const [runs, setRuns] = useState<WorkflowRunSummary[]>([]);
  const [learning, setLearning] = useState<WorkflowLearningPayload | null>(null);
  const [selectedRunId, setSelectedRunId] = useState('');
  const [tab, setTab] = useState<ConsoleTab>('plan');
  const [reviewNote, setReviewNote] = useState('人工复核通过，允许继续形成主席结论。');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState(true);

  useEffect(() => {
    setDepth(defaultDepth);
    setRounds(defaultRounds);
  }, [defaultDepth, defaultRounds, templateId]);

  const refresh = async () => {
    try {
      const [runPayload, learningPayload] = await Promise.all([
        api.workflowRuns({ template_id: templateId, limit: 30 }),
        api.workflowLearning(templateId, 100),
      ]);
      setRuns(runPayload.runs);
      setLearning(learningPayload);
      setError(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
  };

  useEffect(() => { void refresh(); }, [templateId]);

  const requestPayload = () => ({
    trigger_type: 'workflow_ui',
    query: query.trim(),
    template_id: templateId,
    depth,
    rounds,
  });

  const createPlan = async () => {
    if (!query.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const next = await api.planWorkflow(requestPayload());
      setPlan(next);
      setTab('plan');
      setExpanded(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(false);
    }
  };

  const startDryRun = async () => {
    if (!query.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const next = await api.runWorkflow({ ...requestPayload(), workflow_version_id: workflowVersionId, dry_run: true });
      setPlan(next.plan);
      setRun(next);
      setSelectedRunId(next.workflow_run_id);
      setTab('progress');
      setExpanded(true);
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(false);
    }
  };

  const loadRun = async (runId: string) => {
    setSelectedRunId(runId);
    if (!runId) return;
    setBusy(true);
    setError(null);
    try {
      const next = await api.workflowRun(runId);
      setRun(next);
      setPlan(next.plan);
      setTab('progress');
      setExpanded(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(false);
    }
  };

  const resumeRun = async () => {
    if (!run) return;
    const waitingNodes = run.checkpoints.filter((checkpoint) => checkpoint.status === 'waiting_human');
    if (!waitingNodes.length) return;
    const nodeOutputs = Object.fromEntries(waitingNodes.map((checkpoint) => [checkpoint.node_id, { approved: true, review_note: reviewNote }]));
    setBusy(true);
    setError(null);
    try {
      const next = await api.resumeWorkflowRun(run.workflow_run_id, nodeOutputs);
      setRun(next);
      setTab('progress');
      await refresh();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setBusy(false);
    }
  };

  const progress = useMemo(() => run ? calculateProgress(run) : { completed: 0, total: 0, percent: 0 }, [run]);
  const waitingHuman = run?.status === 'waiting_human';

  return (
    <Box sx={{ borderTop: 1, borderColor: 'divider', bgcolor: 'background.paper' }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(260px, 1fr) 150px 120px auto' }, gap: 1, alignItems: 'center', px: 1.5, py: 1.25 }}>
        <TextField aria-label="研究目标" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="输入本次研究目标" fullWidth />
        <TextField select aria-label="研究深度" value={depth} onChange={(event) => setDepth(event.target.value as typeof depth)}>
          <MenuItem value="quick">快速</MenuItem><MenuItem value="standard">标准</MenuItem><MenuItem value="deep">深度</MenuItem>
        </TextField>
        <TextField type="number" label="辩论轮次" value={rounds} onChange={(event) => setRounds(Math.max(1, Math.min(32, Number.parseInt(event.target.value, 10) || 1)))} />
        <Stack direction="row" spacing={0.75}>
          <Button variant="outlined" startIcon={<AccountTreeOutlinedIcon />} onClick={() => void createPlan()} disabled={busy || !query.trim()}>Director 规划</Button>
          <Button variant="contained" startIcon={<PlayCircleOutlineIcon />} onClick={() => void startDryRun()} disabled={busy || !query.trim()}>Dry-run</Button>
        </Stack>
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 1.5, py: 0.75, borderTop: 1, borderColor: 'divider', bgcolor: '#f8fafc' }}>
        <Typography variant="caption" fontWeight={700}>{templateName}</Typography>
        <Chip size="small" variant="outlined" label="可审计过程" />
        <Typography variant="caption" color="text.secondary" sx={{ flexGrow: 1 }}>显示角色输出、证据、状态与反思，不展示隐藏思维链。</Typography>
        <TextField select aria-label="历史运行" value={selectedRunId} onChange={(event) => void loadRun(event.target.value)} sx={{ minWidth: 220 }}>
          <MenuItem value="">选择历史运行</MenuItem>
          {runs.map((item) => <MenuItem key={item.workflow_run_id} value={item.workflow_run_id}>{formatTime(item.created_at)} · {statusText(item.status)}</MenuItem>)}
        </TextField>
        <Button size="small" startIcon={<RefreshIcon />} onClick={() => void refresh()} disabled={busy}>刷新</Button>
        <Button size="small" onClick={() => setExpanded((current) => !current)}>{expanded ? '收起' : '展开'}</Button>
      </Box>
      {busy && <LinearProgress />}
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
      <Collapse in={expanded}>
        <Tabs value={tab} onChange={(_, value: ConsoleTab) => setTab(value)} variant="scrollable" scrollButtons="auto" sx={{ px: 1, borderTop: 1, borderBottom: 1, borderColor: 'divider' }}>
          <Tab value="plan" icon={<AccountTreeOutlinedIcon fontSize="small" />} iconPosition="start" label="计划" />
          <Tab value="progress" icon={<TimelineOutlinedIcon fontSize="small" />} iconPosition="start" label="执行进度" />
          <Tab value="checkpoints" icon={<FactCheckOutlinedIcon fontSize="small" />} iconPosition="start" label={`检查点${run ? ` ${run.checkpoints.length}` : ''}`} />
          <Tab value="reflection" icon={<HistoryEduOutlinedIcon fontSize="small" />} iconPosition="start" label={`反思记忆${learning ? ` ${learning.memory_count}` : ''}`} />
          <Tab value="scores" icon={<PsychologyAltOutlinedIcon fontSize="small" />} iconPosition="start" label="角色评分" />
        </Tabs>
        <Box sx={{ minHeight: 210, maxHeight: 330, overflow: 'auto', px: 1.5, py: 1.25 }}>
          {tab === 'plan' && <PlanView plan={plan} />}
          {tab === 'progress' && <ProgressView run={run} progress={progress} waitingHuman={waitingHuman} reviewNote={reviewNote} setReviewNote={setReviewNote} onResume={resumeRun} busy={busy} />}
          {tab === 'checkpoints' && <CheckpointView run={run} />}
          {tab === 'reflection' && <ReflectionView learning={learning} />}
          {tab === 'scores' && <ScoreView learning={learning} />}
        </Box>
      </Collapse>
    </Box>
  );
}

function PlanView({ plan }: { plan: WorkflowDirectorPlan | null }) {
  if (!plan) return <EmptyState text="输入研究目标后由 Director 生成目标、事实、假设、缺口、步骤和预算。" />;
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1.4fr) minmax(260px, 1fr)' }, gap: 2 }}>
      <Stack spacing={1}>
        <Typography variant="subtitle1">{plan.objective}</Typography>
        <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap><Chip size="small" label={plan.template_name || plan.template_id} /><Chip size="small" color="primary" label={`${plan.rounds} 轮`} /><Chip size="small" variant="outlined" label={plan.depth} /></Stack>
        <PlanList title="已知事实" values={plan.facts} empty="尚无可确认事实" />
        <PlanList title="待验证假设" values={plan.assumptions} empty="无显式假设" />
        <PlanList title="信息缺口" values={plan.gaps} empty="未发现显式缺口" />
      </Stack>
      <Stack spacing={1}>
        <Typography variant="subtitle1">执行步骤</Typography>
        {plan.steps.map((step, index) => <Box key={String(step.node_id || index)} sx={{ borderLeft: 3, borderColor: 'primary.light', pl: 1.25, py: 0.35 }}><Typography variant="body2" fontWeight={700}>{index + 1}. {String(step.role_id || step.operation || step.node_id || '步骤')}</Typography><Typography variant="caption" color="text.secondary">{String(step.node_type || '')}{step.operation ? ` · ${String(step.operation)}` : ''}</Typography></Box>)}
        <Divider />
        <Typography variant="caption" color="text.secondary">预算上限：{formatBudget(plan.budget_policy)}</Typography>
      </Stack>
    </Box>
  );
}

function ProgressView({ run, progress, waitingHuman, reviewNote, setReviewNote, onResume, busy }: { run: WorkflowRunDetail | null; progress: { completed: number; total: number; percent: number }; waitingHuman: boolean; reviewNote: string; setReviewNote: (value: string) => void; onResume: () => Promise<void>; busy: boolean }) {
  if (!run) return <EmptyState text="执行 Dry-run 后，这里按节点显示调度顺序、状态、失败原因和循环次数。" />;
  const statuses = objectRecord(run.result.node_statuses);
  const loopCounts = objectRecord(run.result.loop_counts);
  return (
    <Stack spacing={1.25}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap><Chip color={statusColor(run.status)} label={statusText(run.status)} /><Typography variant="body2">{progress.completed}/{progress.total} 节点结束</Typography><Typography variant="caption" color="text.secondary">运行 ID {run.workflow_run_id}</Typography></Stack>
      <LinearProgress variant="determinate" value={progress.percent} sx={{ height: 7, borderRadius: 1 }} />
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>{Object.entries(statuses).map(([nodeId, status]) => <Chip key={nodeId} size="small" variant="outlined" color={statusColor(String(status))} label={`${nodeId} · ${statusText(String(status))}`} />)}</Stack>
      {Object.keys(loopCounts).length > 0 && <Alert severity="info">循环计数：{Object.entries(loopCounts).map(([id, count]) => `${id} ${String(count)} 次`).join('，')}</Alert>}
      {run.error && <Alert severity="error">{run.error}</Alert>}
      {waitingHuman && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}>
          <TextField label="人工复核意见" value={reviewNote} onChange={(event) => setReviewNote(event.target.value)} fullWidth />
          <Button variant="contained" color="success" startIcon={<CheckCircleOutlineIcon />} onClick={() => void onResume()} disabled={busy || !reviewNote.trim()} sx={{ whiteSpace: 'nowrap' }}>批准并继续</Button>
        </Stack>
      )}
    </Stack>
  );
}

function CheckpointView({ run }: { run: WorkflowRunDetail | null }) {
  if (!run) return <EmptyState text="运行后会记录每个节点的幂等检查点、尝试次数和可见输出。" />;
  return (
    <Table size="small" stickyHeader>
      <TableHead><TableRow><TableCell>节点</TableCell><TableCell>类型 / 操作</TableCell><TableCell>状态</TableCell><TableCell align="right">轮次</TableCell><TableCell align="right">尝试</TableCell><TableCell>可见输出</TableCell></TableRow></TableHead>
      <TableBody>{run.checkpoints.map((item) => <TableRow key={item.checkpoint_id} hover><TableCell><Typography variant="body2" fontWeight={700}>{item.node_id}</Typography><Typography variant="caption" color="text.secondary">{item.phase_id || '通用'}</Typography></TableCell><TableCell>{item.node_type}{item.operation ? ` · ${item.operation}` : ''}</TableCell><TableCell><Chip size="small" color={statusColor(item.status)} label={statusText(item.status)} /></TableCell><TableCell align="right">{item.iteration}</TableCell><TableCell align="right">{item.attempt}</TableCell><TableCell><OutputSummary value={item.output} error={item.error} /></TableCell></TableRow>)}</TableBody>
    </Table>
  );
}

function ReflectionView({ learning }: { learning: WorkflowLearningPayload | null }) {
  if (!learning || !learning.memories?.length) return <EmptyState text="完成真实 Council 辩论并形成可评价结果后，这里会显示结构化反思和选择性记忆。" />;
  return <Stack spacing={1}>{learning.memories.map((memory) => <MemoryRow key={memory.memory_id} memory={memory} />)}</Stack>;
}

function ScoreView({ learning }: { learning: WorkflowLearningPayload | null }) {
  if (!learning || !learning.role_scores.length) return <EmptyState text="暂无角色评分；评分会综合完成率、证据覆盖、主席采纳、人工复核和成熟结果。" />;
  return (
    <Table size="small" stickyHeader><TableHead><TableRow><TableCell>角色</TableCell><TableCell>模板</TableCell><TableCell align="right">综合分</TableCell><TableCell align="right">辩论样本</TableCell><TableCell>最近更新</TableCell></TableRow></TableHead><TableBody>{learning.role_scores.map((score) => <TableRow key={`${score.template_id}:${score.role_id}`} hover><TableCell><Typography variant="body2" fontWeight={700}>{score.role_id}</Typography></TableCell><TableCell>{score.template_id}</TableCell><TableCell align="right"><Chip size="small" color={Number(score.score || 0) >= 70 ? 'success' : Number(score.score || 0) >= 50 ? 'warning' : 'default'} label={Number(score.score || 0).toFixed(1)} /></TableCell><TableCell align="right">{String(score.debate_count || 0)}</TableCell><TableCell>{formatTime(String(score.latest_at || ''))}</TableCell></TableRow>)}</TableBody></Table>
  );
}

function MemoryRow({ memory }: { memory: CouncilMemoryRecord }) {
  const content = memory.content;
  const summary = typeof content === 'string' ? content : content && typeof content === 'object' ? summarizeObject(content) : '暂无摘要';
  return <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '150px minmax(0, 1fr) 140px' }, gap: 1, alignItems: 'start', borderBottom: 1, borderColor: 'divider', pb: 1 }}><Stack><Chip size="small" variant="outlined" label={String(memory.memory_type || 'memory')} /><Typography variant="caption" color="text.secondary">{memory.role_id || '全局反思'}</Typography></Stack><Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>{summary}</Typography><Typography variant="caption" color="text.secondary">{formatTime(String(memory.created_at || ''))}</Typography></Box>;
}

function OutputSummary({ value, error }: { value: Record<string, unknown>; error?: string | null }) {
  if (error) return <Typography variant="caption" color="error.main">{error}</Typography>;
  const summary = String(value.summary || value.status || value.route || summarizeObject(value));
  return <Typography variant="caption" color="text.secondary" sx={{ display: 'block', maxWidth: 360, overflowWrap: 'anywhere' }}>{summary}</Typography>;
}

function PlanList({ title, values, empty }: { title: string; values: Array<string | Record<string, unknown>>; empty: string }) {
  return <Box><Typography variant="caption" fontWeight={700}>{title}</Typography>{values.length ? values.map((value, index) => <Typography key={`${title}:${index}`} variant="body2" sx={{ '&::before': { content: '"•"', mr: 0.75, color: 'primary.main' } }}>{formatPlanEntry(value)}</Typography>) : <Typography variant="body2" color="text.secondary">{empty}</Typography>}</Box>;
}

function EmptyState({ text }: { text: string }) {
  return <Stack alignItems="center" justifyContent="center" spacing={1} sx={{ minHeight: 170, textAlign: 'center', color: 'text.secondary' }}><CircularProgress size={20} variant="determinate" value={100} /><Typography variant="body2" sx={{ maxWidth: 520 }}>{text}</Typography></Stack>;
}

function calculateProgress(run: WorkflowRunDetail) {
  const statuses = Object.values(objectRecord(run.result.node_statuses));
  const completed = statuses.filter((status) => ['completed', 'skipped', 'skipped_phase', 'waiting', 'failed'].includes(String(status))).length;
  return { completed, total: statuses.length, percent: statuses.length ? Math.round(completed / statuses.length * 100) : 0 };
}

function objectRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function summarizeObject(value: Record<string, unknown>): string {
  return Object.entries(value).slice(0, 5).map(([key, item]) => `${key}: ${formatValue(item)}`).join('；') || '无可见输出';
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) return value.slice(0, 4).map(String).join(', ');
  if (value && typeof value === 'object') return JSON.stringify(value).slice(0, 180);
  return String(value ?? '-');
}

function formatPlanEntry(value: string | Record<string, unknown>): string {
  if (typeof value === 'string') return value;
  if ('field' in value) {
    const labels: Record<string, string> = { query: '研究问题', product_id: '产品', symbol: '交易标的', product_type: '产品类型', market_type: '市场类型', evidence_status: '证据状态' };
    const field = String(value.field || '输入');
    return `${labels[field] || field}：${formatValue(value.value)}`;
  }
  return summarizeObject(value);
}

function formatBudget(policy: Record<string, number>): string {
  const tokens = policy.max_total_tokens ? `${policy.max_total_tokens.toLocaleString('zh-CN')} Token` : 'Token 未限制';
  const cost = policy.max_cost_usd !== undefined ? `$${Number(policy.max_cost_usd).toFixed(2)}` : '费用未限制';
  const duration = policy.max_duration_seconds ? `${policy.max_duration_seconds} 秒` : '时长未限制';
  return `${tokens} · ${cost} · ${duration}`;
}

function formatTime(value: string): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function statusText(status: string): string {
  return ({ planned: '已规划', running: '运行中', completed: '已完成', partial: '部分完成', failed: '失败', waiting: '等待人工', waiting_human: '等待人工', skipped: '已跳过', skipped_phase: '阶段跳过', pending: '等待执行' } as Record<string, string>)[status] || status;
}

function statusColor(status: string): 'default' | 'primary' | 'success' | 'warning' | 'error' {
  if (['completed'].includes(status)) return 'success';
  if (['failed'].includes(status)) return 'error';
  if (['waiting', 'waiting_human', 'partial'].includes(status)) return 'warning';
  if (['running'].includes(status)) return 'primary';
  return 'default';
}
