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
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RateReviewOutlinedIcon from '@mui/icons-material/RateReviewOutlined';
import ReplayIcon from '@mui/icons-material/Replay';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { api } from './api';
import { DecisionList } from './OperationsPanels';
import { decisionReadinessText, shortId } from './humanReadable';
import type {
  DecisionReviewInbox,
  DecisionReviewItem,
  DecisionReviewStatus,
  PaperExecutionReport,
  PaperExecutionStatusPayload,
} from './types';
import { formatTime, statusColor, statusText } from './utils';

const filters: Array<{ value: DecisionReviewStatus; label: string }> = [
  { value: 'pending', label: '待复核' },
  { value: 'approved', label: '已批准' },
  { value: 'changes_requested', label: '需修改' },
  { value: 'rejected', label: '已拒绝' },
];

export function ReviewInboxPanel() {
  const [filter, setFilter] = useState<DecisionReviewStatus>('pending');
  const [inbox, setInbox] = useState<DecisionReviewInbox | null>(null);
  const [paperStatus, setPaperStatus] = useState<PaperExecutionStatusPayload | null>(null);
  const [selectedDecisionId, setSelectedDecisionId] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [executionOpen, setExecutionOpen] = useState(false);
  const [executionReport, setExecutionReport] = useState<PaperExecutionReport | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [reviewPayload, executionPayload] = await Promise.all([
        api.decisionReviews(filter),
        api.paperExecutionStatus(),
      ]);
      setInbox(reviewPayload);
      setPaperStatus(executionPayload);
      setSelectedDecisionId((current) => (
        reviewPayload.items.some((item) => item.decision.decision_id === current)
          ? current
          : reviewPayload.items[0]?.decision.decision_id || null
      ));
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => {
    void load();
  }, [load]);

  const selected = useMemo(
    () => inbox?.items.find((item) => item.decision.decision_id === selectedDecisionId) || null,
    [inbox, selectedDecisionId],
  );

  useEffect(() => {
    setNote(selected?.review.note || '');
    setExecutionReport(null);
  }, [selectedDecisionId, selected?.review.note]);

  const updateReview = async (status: DecisionReviewStatus) => {
    if (!selected || saving) return;
    setSaving(true);
    setError(null);
    try {
      await api.updateDecisionReview(selected.decision.decision_id, {
        status,
        note,
        expected_version: selected.review.version,
      });
      setFilter(status);
      await load();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  const executePaper = async () => {
    if (!selected || saving) return;
    setSaving(true);
    setError(null);
    try {
      const report = await api.executeReviewedDecision(selected.decision.decision_id, {
        adapter_ids: paperStatus?.adapters.filter((item) => item.enabled).map((item) => item.adapter_id) || [],
        confirm_simulated_execution: true,
      });
      setExecutionReport(report);
      setExecutionOpen(false);
      setPaperStatus(await api.paperExecutionStatus());
    } catch (err) {
      setError(errorText(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Stack spacing={2}>
      <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', px: { xs: 1.5, md: 2 }, py: 1.5 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ xs: 'stretch', md: 'center' }}>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ minWidth: 210 }}>
            <FactCheckOutlinedIcon color="primary" />
            <Box>
              <Typography variant="h3">决策人工复核</Typography>
              <Typography variant="caption" color="text.secondary">批准是模拟执行前的强制门禁</Typography>
            </Box>
          </Stack>
          <Tabs value={filter} onChange={(_, value: DecisionReviewStatus) => setFilter(value)} variant="scrollable" scrollButtons="auto" sx={{ flexGrow: 1 }}>
            {filters.map((item) => (
              <Tab key={item.value} value={item.value} label={`${item.label} ${inbox?.counts[item.value] || 0}`} />
            ))}
          </Tabs>
          <Chip
            size="small"
            color={paperStatus?.mode === 'submit' ? 'warning' : 'default'}
            label={paperStatus?.mode === 'submit' ? '模拟提交已开启' : '当前仅生成模拟计划'}
          />
        </Stack>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}
      {executionReport && (
        <Alert severity={executionReport.status === 'passed' ? 'success' : 'warning'}>
          模拟执行结果：{statusText(executionReport.status)}，记录 {executionReport.summary.execution_count} 笔。
          {executionReport.summary.reasons.length > 0 ? ` ${executionReport.summary.reasons.join('；')}` : ''}
        </Alert>
      )}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '340px minmax(0, 1fr)' }, gap: 2, alignItems: 'start' }}>
        <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', minWidth: 0 }}>
          {loading && !inbox ? (
            <Stack alignItems="center" py={6}><CircularProgress size={26} /></Stack>
          ) : inbox?.items.length ? (
            <Stack divider={<Divider />} spacing={0}>
              {inbox.items.map((item) => (
                <ReviewListItem
                  key={item.decision.decision_id}
                  item={item}
                  selected={item.decision.decision_id === selectedDecisionId}
                  onSelect={() => setSelectedDecisionId(item.decision.decision_id)}
                />
              ))}
            </Stack>
          ) : (
            <Box sx={{ py: 7, px: 2, textAlign: 'center' }}>
              <RateReviewOutlinedIcon color="disabled" sx={{ fontSize: 38 }} />
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>当前分类没有决策。</Typography>
            </Box>
          )}
        </Box>

        <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', minWidth: 0, p: { xs: 1.5, md: 2 } }}>
          {!selected ? (
            <Box sx={{ py: 7, textAlign: 'center' }}><Typography variant="body2" color="text.secondary">选择一条决策查看门禁与复核详情。</Typography></Box>
          ) : (
            <Stack spacing={2}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ xs: 'flex-start', sm: 'center' }}>
                <Box>
                  <Typography variant="h3">{selected.decision.symbol || selected.decision.normalized_symbol}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {selected.decision.provider} · {selected.decision.market_type} · {shortId(selected.decision.loop_run_id)}
                  </Typography>
                </Box>
                <Stack direction="row" spacing={0.75}>
                  <Chip size="small" color={statusColor(selected.review.status)} label={reviewStatusText(selected.review.status)} />
                  <Chip size="small" variant="outlined" color={statusColor(selected.decision_readiness.status)} label={decisionReadinessText(selected.decision_readiness.status)} />
                </Stack>
              </Stack>

              <DecisionList items={[selected.decision]} />

              {selected.approval_blockers.length > 0 && (
                <Alert severity="warning">
                  {selected.approval_blockers.map((blocker) => <Typography key={blocker} variant="body2">{blocker}</Typography>)}
                </Alert>
              )}

              <TextField
                label="复核意见"
                multiline
                minRows={3}
                value={note}
                onChange={(event) => setNote(event.target.value.slice(0, 2000))}
                inputProps={{ maxLength: 2000 }}
              />

              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                <Button variant="outlined" color="warning" startIcon={<ReplayIcon />} disabled={saving} onClick={() => void updateReview('changes_requested')}>
                  要求补充
                </Button>
                <Button variant="outlined" color="error" disabled={saving} onClick={() => void updateReview('rejected')}>
                  拒绝
                </Button>
                <Button variant="contained" startIcon={<CheckCircleOutlineIcon />} disabled={saving || !selected.approval_eligible} onClick={() => void updateReview('approved')}>
                  批准建议
                </Button>
                {selected.review.status === 'approved' && (
                  <Button variant="contained" color="warning" startIcon={<PlayArrowIcon />} disabled={saving} onClick={() => setExecutionOpen(true)} sx={{ ml: { sm: 'auto !important' } }}>
                    {paperStatus?.mode === 'submit' ? '执行模拟订单' : '生成模拟计划'}
                  </Button>
                )}
              </Stack>
              <Typography variant="caption" color="text.secondary">
                复核版本 {selected.review.version} · 最近更新 {formatTime(selected.review.updated_at)}
              </Typography>
            </Stack>
          )}
        </Box>
      </Box>

      <Dialog open={executionOpen} onClose={() => !saving && setExecutionOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{paperStatus?.mode === 'submit' ? '确认模拟下单' : '确认生成模拟计划'}</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={1.25}>
            <Typography variant="body2">
              仅发送至 Gate TestNet 和 Bybit Demo；每家最多 {paperStatus?.policy.max_orders_per_adapter || 1} 单，单笔最多 {paperStatus?.policy.max_notional_usdt || 100} USDT。
            </Typography>
            <Alert severity="info">真实盘、资金划转和 AI 自定义订单数量继续硬禁止。</Alert>
            <Stack direction="row" spacing={0.75} sx={{ flexWrap: 'wrap', gap: 0.75 }}>
              {paperStatus?.adapters.filter((item) => item.enabled).map((item) => (
                <Chip key={item.adapter_id} size="small" label={`${item.display_name} · ${statusText(item.status)}`} color={statusColor(item.status)} />
              ))}
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setExecutionOpen(false)} disabled={saving}>取消</Button>
          <Button variant="contained" color="warning" onClick={() => void executePaper()} disabled={saving} startIcon={saving ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />}>
            确认执行
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

function ReviewListItem({ item, selected, onSelect }: { item: DecisionReviewItem; selected: boolean; onSelect: () => void }) {
  return (
    <Box
      component="button"
      type="button"
      onClick={onSelect}
      sx={{
        appearance: 'none',
        border: 0,
        bgcolor: selected ? 'action.selected' : 'transparent',
        color: 'text.primary',
        textAlign: 'left',
        px: 1.5,
        py: 1.25,
        cursor: 'pointer',
        width: '100%',
        '&:hover': { bgcolor: 'action.hover' },
      }}
    >
      <Stack direction="row" spacing={1} justifyContent="space-between" alignItems="flex-start">
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>{item.decision.symbol || '-'}</Typography>
          <Typography variant="caption" color="text.secondary">{item.decision.provider} · {item.decision.market_type}</Typography>
        </Box>
        <Chip size="small" variant="outlined" color={item.approval_eligible ? 'success' : 'warning'} label={item.approval_eligible ? '可批准' : '门禁阻断'} />
      </Stack>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>
        {decisionReadinessText(item.decision_readiness.status)} · {Math.round((item.decision.confidence || 0) * 100)}% · {formatTime(item.decision.created_at)}
      </Typography>
    </Box>
  );
}

function reviewStatusText(status: DecisionReviewStatus): string {
  const map: Record<DecisionReviewStatus, string> = {
    pending: '待复核',
    approved: '已批准',
    rejected: '已拒绝',
    changes_requested: '需补充',
  };
  return map[status];
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
