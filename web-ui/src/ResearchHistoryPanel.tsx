import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import HistoryIcon from '@mui/icons-material/History';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import RestoreIcon from '@mui/icons-material/Restore';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import { DecisionList } from './OperationsPanels';
import { autonomousStepText, decisionReadinessText, durationText, shortId, triggerTypeText } from './humanReadable';
import type { ResearchHistoryComparison, ResearchHistoryList, ResearchHistoryRunDetail, ResearchHistoryRunSummary, ResearchTimelineEvent } from './types';
import { formatTime, statusColor, statusText } from './utils';

type HistoryView = 'detail' | 'compare';
type ConfirmAction = 'replay' | 'resume' | null;

export function ResearchHistoryPanel() {
  const [history, setHistory] = useState<ResearchHistoryList | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ResearchHistoryRunDetail | null>(null);
  const [leftId, setLeftId] = useState('');
  const [rightId, setRightId] = useState('');
  const [comparison, setComparison] = useState<ResearchHistoryComparison | null>(null);
  const [view, setView] = useState<HistoryView>('detail');
  const [confirmAction, setConfirmAction] = useState<ConfirmAction>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const loadHistory = useCallback(async () => {
    setLoading(true);
    try {
      const payload = await api.researchHistory(50);
      setHistory(payload);
      const nextSelected = selectedId && payload.items.some((item) => item.loop_run_id === selectedId)
        ? selectedId
        : payload.items[0]?.loop_run_id || null;
      setSelectedId(nextSelected);
      setLeftId((current) => current || payload.items[0]?.loop_run_id || '');
      setRightId((current) => current || payload.items[1]?.loop_run_id || '');
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [selectedId]);

  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    let active = true;
    api.researchHistoryRun(selectedId)
      .then((payload) => {
        if (active) setDetail(payload);
      })
      .catch((err) => {
        if (active) setError(errorText(err));
      });
    return () => { active = false; };
  }, [selectedId]);

  const compare = async () => {
    if (!leftId || !rightId || leftId === rightId) return;
    setSubmitting(true);
    try {
      setComparison(await api.compareResearchRuns(leftId, rightId));
      setView('compare');
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setSubmitting(false);
    }
  };

  const runAction = async () => {
    if (!detail || !confirmAction) return;
    setSubmitting(true);
    try {
      if (confirmAction === 'replay') {
        const response = await api.replayResearchRun(detail.loop_run_id);
        setMessage(`Replay 已进入 Worker 队列：${shortId(String(response.request.request_id || ''), 12)}`);
      } else {
        const failedStep = detail.research_pipeline?.steps.find((step) => step.status === 'failed')?.step_name;
        const response = await api.resumeResearchRun(detail.loop_run_id, failedStep);
        setMessage(`续跑任务已提交：${shortId(response.job.job_id, 12)}`);
      }
      setConfirmAction(null);
      setError(null);
      await loadHistory();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setSubmitting(false);
    }
  };

  const canResume = Boolean(detail?.research_pipeline?.steps.some((step) => step.status === 'failed'));

  return (
    <Stack spacing={2}>
      <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', px: { xs: 1.5, md: 2 }, py: 1.25 }}>
        <Stack direction={{ xs: 'column', xl: 'row' }} spacing={1.25} alignItems={{ xs: 'stretch', xl: 'center' }}>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ minWidth: 180 }}>
            <HistoryIcon color="primary" />
            <Box>
              <Typography variant="h3">研究运行历史</Typography>
              <Typography variant="caption" color="text.secondary">{history?.count || 0} 次运行</Typography>
            </Box>
          </Stack>
          <Tabs value={view} onChange={(_, value: HistoryView) => setView(value)} sx={{ minWidth: 190 }}>
            <Tab value="detail" label="运行详情" />
            <Tab value="compare" label="版本对比" />
          </Tabs>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ flexGrow: 1 }}>
            <RunSelect label="基准运行" value={leftId} items={history?.items || []} onChange={setLeftId} />
            <RunSelect label="对比运行" value={rightId} items={history?.items || []} onChange={setRightId} />
            <Button variant="outlined" startIcon={submitting ? <CircularProgress size={16} /> : <CompareArrowsIcon />} disabled={submitting || !leftId || !rightId || leftId === rightId} onClick={() => void compare()}>
              对比
            </Button>
          </Stack>
        </Stack>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}
      {message && <Alert severity="success" onClose={() => setMessage(null)}>{message}</Alert>}

      {view === 'compare' && comparison ? (
        <ComparisonView comparison={comparison} />
      ) : (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '330px minmax(0, 1fr)' }, gap: 2, alignItems: 'start' }}>
          <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
            {loading && !history ? (
              <Stack alignItems="center" py={6}><CircularProgress size={26} /></Stack>
            ) : history?.items.length ? (
              <Stack divider={<Divider />} spacing={0}>
                {history.items.map((run) => (
                  <HistoryRow key={run.loop_run_id} run={run} selected={run.loop_run_id === selectedId} onSelect={() => { setSelectedId(run.loop_run_id); setView('detail'); }} />
                ))}
              </Stack>
            ) : (
              <Box sx={{ py: 7, textAlign: 'center' }}><Typography variant="body2" color="text.secondary">暂无研究运行历史。</Typography></Box>
            )}
          </Box>

          <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', p: { xs: 1.5, md: 2 }, minWidth: 0 }}>
            {!detail ? (
              <Stack alignItems="center" py={6}><CircularProgress size={24} /></Stack>
            ) : (
              <RunDetail
                detail={detail}
                submitting={submitting}
                canResume={canResume}
                onReplay={() => setConfirmAction('replay')}
                onResume={() => setConfirmAction('resume')}
              />
            )}
          </Box>
        </Box>
      )}

      <Dialog open={Boolean(confirmAction)} onClose={() => !submitting && setConfirmAction(null)} fullWidth maxWidth="sm">
        <DialogTitle>{confirmAction === 'replay' ? '确认重放运行' : '确认续跑采集与处理任务'}</DialogTitle>
        <DialogContent dividers>
          <Typography variant="body2">
            {confirmAction === 'replay'
              ? '将创建一个新的 Worker 请求，使用原运行的研究范围与当前 AI/网络配置；模拟订单提交会被强制关闭。'
              : '将从第一个失败步骤继续同一个 research pipeline，已成功步骤会复用而不重复生成产物。'}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmAction(null)} disabled={submitting}>取消</Button>
          <Button variant="contained" onClick={() => void runAction()} disabled={submitting} startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : confirmAction === 'replay' ? <PlayCircleOutlineIcon /> : <RestoreIcon />}>
            确认
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

function RunDetail({ detail, submitting, canResume, onReplay, onResume }: { detail: ResearchHistoryRunDetail; submitting: boolean; canResume: boolean; onReplay: () => void; onResume: () => void }) {
  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ xs: 'flex-start', sm: 'center' }}>
        <Box>
          <Typography variant="h3">{triggerTypeText(detail.trigger_type)} · {shortId(detail.loop_run_id, 14)}</Typography>
          <Typography variant="caption" color="text.secondary">{formatTime(detail.started_at)} - {formatTime(detail.finished_at)}</Typography>
        </Box>
        <Stack direction="row" spacing={0.75}>
          <Chip size="small" color={statusColor(detail.run_status)} label={statusText(detail.run_status)} />
          <Chip size="small" variant="outlined" color={statusColor(detail.decision_readiness?.status || '')} label={decisionReadinessText(detail.decision_readiness?.status)} />
        </Stack>
      </Stack>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1 }}>
        <Fact label="总耗时" value={durationText(detail.total_duration_ms)} />
        <Fact label="AI 决策" value={String(detail.decision_count)} />
        <Fact label="方向建议" value={String(detail.directional_count)} />
        <Fact label="采集与处理" value={statusText(detail.research_pipeline_status)} />
      </Box>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <Button variant="outlined" startIcon={<PlayCircleOutlineIcon />} disabled={submitting || detail.run_status === 'running'} onClick={onReplay}>重放</Button>
        <Button variant="outlined" startIcon={<RestoreIcon />} disabled={submitting || !canResume} onClick={onResume}>从失败处续跑</Button>
      </Stack>

      {detail.timeline.length > 0 && (
        <>
          <Divider />
          <Box data-testid="research-run-timeline">
            <Typography variant="subtitle1" sx={{ mb: 0.75 }}>全链路时间线</Typography>
            <Stack spacing={0}>
              {detail.timeline.map((event) => <TimelineRow key={event.event_id} event={event} />)}
            </Stack>
          </Box>
        </>
      )}

      <Divider />
      <Box>
        <Typography variant="subtitle1" sx={{ mb: 0.75 }}>步骤</Typography>
        <Stack divider={<Divider />} spacing={0}>
          {detail.steps.map((step) => (
            <Box key={step.step_id} sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr auto', md: 'minmax(150px, 0.8fr) 100px 100px minmax(0, 1fr)' }, gap: 1, py: 0.8, alignItems: 'center' }}>
              <Typography variant="body2" sx={{ fontWeight: 700 }}>{autonomousStepText(step.step_name)}</Typography>
              <Chip size="small" variant="outlined" color={statusColor(step.status)} label={statusText(step.status)} />
              <Typography variant="caption" color="text.secondary">{durationText(step.duration_ms)}</Typography>
              <Typography variant="caption" color={step.error ? 'error.main' : 'text.secondary'} sx={{ gridColumn: { xs: '1 / -1', md: 'auto' } }}>{step.error || `尝试 ${step.attempt}`}</Typography>
            </Box>
          ))}
        </Stack>
      </Box>

      <Divider />
      <Box>
        <Typography variant="subtitle1" sx={{ mb: 0.75 }}>最终建议</Typography>
        <DecisionList items={detail.decisions} />
      </Box>
    </Stack>
  );
}

function TimelineRow({ event }: { event: ResearchTimelineEvent }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '135px 110px minmax(0, 1fr)' }, gap: { xs: 0.4, sm: 1 }, py: 1, pl: 1.5, borderLeft: '2px solid', borderColor: 'divider', minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary">{formatTime(event.timestamp)}</Typography>
      <Chip size="small" variant="outlined" label={timelineStageText(event.stage)} sx={{ justifySelf: 'start' }} />
      <Box sx={{ minWidth: 0 }}>
        <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{event.title}</Typography>
          <Chip size="small" color={statusColor(event.status)} label={statusText(event.status)} />
        </Stack>
        {event.detail && <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{event.detail}</Typography>}
      </Box>
    </Box>
  );
}

function timelineStageText(stage: string): string {
  const labels: Record<string, string> = {
    research: '研究',
    collection: '采集处理',
    recommendation: '建议',
    review: '复核',
    risk: '风险',
    governance: 'AI 治理',
    evaluation: '结果评估',
    execution: '模拟执行',
    order: '订单',
    position: '持仓',
  };
  return labels[stage] || stage;
}

function ComparisonView({ comparison }: { comparison: ResearchHistoryComparison }) {
  return (
    <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', p: { xs: 1.5, md: 2 } }}>
      <Stack spacing={2}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ xs: 'flex-start', sm: 'center' }}>
          <Chip size="small" label={`A ${shortId(comparison.left_loop_run_id, 12)}`} />
          <CompareArrowsIcon color="action" />
          <Chip size="small" label={`B ${shortId(comparison.right_loop_run_id, 12)}`} />
        </Stack>
        <Box>
          <Typography variant="subtitle1" sx={{ mb: 0.75 }}>运行指标</Typography>
          <Stack divider={<Divider />} spacing={0}>
            {comparison.metric_changes.map((item) => (
              <CompareRow key={item.field} label={metricLabel(item.field)} left={displayValue(item.left)} right={displayValue(item.right)} changed={item.changed} />
            ))}
          </Stack>
        </Box>
        <Box>
          <Typography variant="subtitle1" sx={{ mb: 0.75 }}>产品决策</Typography>
          <Stack divider={<Divider />} spacing={0}>
            {comparison.decision_changes.map((item) => (
              <CompareRow key={item.key} label={item.key} left={decisionValue(item.left)} right={decisionValue(item.right)} changed={item.changed} />
            ))}
          </Stack>
        </Box>
      </Stack>
    </Box>
  );
}

function CompareRow({ label, left, right, changed }: { label: string; left: string; right: string; changed: boolean }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'minmax(150px, 0.8fr) 1fr 1fr auto' }, gap: 1, py: 0.9, alignItems: 'center' }}>
      <Typography variant="body2" sx={{ fontWeight: 700 }}>{label}</Typography>
      <Typography variant="body2" color="text.secondary">{left}</Typography>
      <Typography variant="body2" color="text.secondary">{right}</Typography>
      <Chip size="small" variant="outlined" color={changed ? 'warning' : 'default'} label={changed ? '有变化' : '一致'} />
    </Box>
  );
}

function HistoryRow({ run, selected, onSelect }: { run: ResearchHistoryRunSummary; selected: boolean; onSelect: () => void }) {
  return (
    <Box component="button" type="button" onClick={onSelect} sx={{ appearance: 'none', border: 0, width: '100%', textAlign: 'left', bgcolor: selected ? 'action.selected' : 'transparent', color: 'text.primary', px: 1.5, py: 1.2, cursor: 'pointer', '&:hover': { bgcolor: 'action.hover' } }}>
      <Stack direction="row" justifyContent="space-between" spacing={1} alignItems="flex-start">
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>{triggerTypeText(run.trigger_type)}</Typography>
          <Typography variant="caption" color="text.secondary">{formatTime(run.started_at)} · {shortId(run.loop_run_id, 10)}</Typography>
        </Box>
        <Chip size="small" color={statusColor(run.run_status)} label={statusText(run.run_status)} />
      </Stack>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
        {decisionReadinessText(run.decision_readiness?.status)} · {run.decision_count} 条决策 · {durationText(run.total_duration_ms)}
      </Typography>
    </Box>
  );
}

function RunSelect({ label, value, items, onChange }: { label: string; value: string; items: ResearchHistoryRunSummary[]; onChange: (value: string) => void }) {
  return (
    <FormControl size="small" sx={{ flex: 1, minWidth: { sm: 180 } }}>
      <InputLabel>{label}</InputLabel>
      <Select value={value} label={label} onChange={(event) => onChange(event.target.value)}>
        {items.map((item) => <MenuItem key={item.loop_run_id} value={item.loop_run_id}>{formatTime(item.started_at)} · {shortId(item.loop_run_id, 9)}</MenuItem>)}
      </Select>
    </FormControl>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return <Box sx={{ border: '1px solid', borderColor: 'divider', px: 1, py: 0.8, minWidth: 0 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" sx={{ fontWeight: 800 }} noWrap>{value}</Typography></Box>;
}

function metricLabel(value: string): string {
  const labels: Record<string, string> = {
    status: '运行状态',
    trigger_type: '触发方式',
    decision_readiness: '决策就绪度',
    total_duration_ms: '总耗时',
    decision_count: '决策数量',
    directional_count: '方向建议',
    estimated_cost_usd: 'AI 成本',
  };
  return labels[value] || value;
}

function decisionValue(value?: Record<string, unknown> | null): string {
  if (!value) return '无';
  return `${String(value.action || '-')} · ${Math.round(Number(value.confidence || 0) * 100)}% · ${String(value.review_status || 'pending')}`;
}

function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-';
  return typeof value === 'number' ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value) : String(value);
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
