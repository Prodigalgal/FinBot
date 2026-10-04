import { Alert, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { HypothesisRevision, HypothesisStatus, HypothesisView, OpportunityHypothesis } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime } from './ui';

const labels: Record<HypothesisStatus, string> = { PENDING_VALIDATION: '待验证', WATCHING: '观察中', CATALYST_NEAR: '临近催化', CONFIRMED: '已确认', PAPER_VALIDATION: '模拟验证', COMPLETED: '已完成', REFUTED: '被反证', EXPIRED: '已过期' };
const nextStates: Record<HypothesisStatus, HypothesisStatus[]> = {
  PENDING_VALIDATION: ['WATCHING', 'REFUTED', 'EXPIRED'], WATCHING: ['CATALYST_NEAR', 'CONFIRMED', 'REFUTED', 'EXPIRED'],
  CATALYST_NEAR: ['WATCHING', 'CONFIRMED', 'REFUTED', 'EXPIRED'], CONFIRMED: ['PAPER_VALIDATION', 'COMPLETED', 'REFUTED', 'EXPIRED'],
  PAPER_VALIDATION: ['COMPLETED', 'REFUTED', 'EXPIRED'], COMPLETED: [], REFUTED: [], EXPIRED: [],
};
const kinds = { OBSERVATION: '待核对观测', ESTIMATE: '来源估计', PROXY: '代理指标', INFERENCE: '推断' };

export function HypothesisPanel() {
  const [hypotheses, setHypotheses] = useState<HypothesisView[] | null>(null);
  const [selected, setSelected] = useState<HypothesisView | null>(null);
  const [history, setHistory] = useState<HypothesisRevision[] | null>(null);
  const [status, setStatus] = useState<HypothesisStatus>('WATCHING');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const inspection = useRef(0);
  const refresh = async () => {
    setBusy(true);
    try { setHypotheses(await api.researchHypotheses()); setError(null); }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  };
  useEffect(() => {
    let disposed = false;
    api.researchHypotheses().then(rows => { if (!disposed) setHypotheses(rows); }).catch(failure => { if (!disposed) setError(failure); });
    return () => { disposed = true; };
  }, []);
  const inspect = async (hypothesis: HypothesisView) => {
    const request = ++inspection.current;
    setError(null);
    setSelected(hypothesis); setHistory(null); setReason(''); setStatus(nextStates[hypothesis.status][0] || hypothesis.status);
    try { const revisions = await api.hypothesisHistory(hypothesis.hypothesisId); if (request === inspection.current) setHistory(revisions); }
    catch (failure) { if (request === inspection.current) setError(failure); }
  };
  const update = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      const updated = await api.updateHypothesisStatus(selected.hypothesisId, selected.version, status, reason.trim());
      setHypotheses(rows => rows?.map(row => row.hypothesisId === updated.hypothesisId ? updated : row) || [updated]);
      await inspect(updated); setError(null);
    } catch (failure) { setError(failure); }
    finally { setBusy(false); }
  };
  return <Stack spacing={1.5}>
    <SectionTitle title="前瞻假设与条件验证" action={<Button disabled={busy} onClick={() => void refresh()}>刷新假设</Button>} />
    <Alert severity="info">新研究中的前置假设进入待验证记录，每 5 分钟归档一次；未被共识选中的方案也会保留。确认状态用于记录证据核对，交易继续经过独立审核和风控。</Alert>
    {error !== null && <ErrorBlock error={error} />}
    {!hypotheses && error === null && <LoadingBlock label="读取前瞻假设" />}
    {hypotheses?.length === 0 && <Paper variant="outlined"><EmptyBlock>暂无前瞻假设。新的指定商品研究在有证据时生成可证伪的提议。</EmptyBlock></Paper>}
    {hypotheses?.map(hypothesis => <Paper key={hypothesis.hypothesisId} variant="outlined" sx={{ p: 1.5 }}><Stack spacing={1}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}><Typography fontWeight={700}>{hypothesis.symbol} · {hypothesis.hypothesis.title}</Typography><Stack direction="row" spacing={1}><Chip size="small" label={labels[hypothesis.status]} /><Chip size="small" label={hypothesis.selected ? '共识选中' : `共识 ${hypothesis.consensusStatus}`} /></Stack></Stack>
      <Typography variant="caption" color="text.secondary">首次提出 {formatTime(hypothesis.firstSeenAt)} · 有效期至 {formatTime(hypothesis.expiresAt)} · 共同敞口 {hypothesis.hypothesis.exposureGroup}</Typography>
      <Typography variant="body2">下一次核对：{hypothesis.hypothesis.nextCheck}</Typography>
      <Button size="small" sx={{ alignSelf: 'flex-end' }} onClick={() => void inspect(hypothesis)}>核对条件与历史</Button>
    </Stack></Paper>)}
    <Dialog open={selected !== null} onClose={() => { if (!busy) setSelected(null); }} maxWidth="md" fullWidth>
      <DialogTitle>{selected?.symbol} · 前瞻假设</DialogTitle><DialogContent dividers><Stack spacing={2}>
        {error !== null && <ErrorBlock error={error} />}
        {selected && <><Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>研究 {selected.workflowRunId} · 首次信息快照 {formatTime(selected.informationCutoff)} · 归档时间 {formatTime(selected.recordedAt)}</Typography><HypothesisBody hypothesis={selected.hypothesis} />
          <Typography fontWeight={700}>最初提议 · {selected.initialHypothesis.title}</Typography><HypothesisBody hypothesis={selected.initialHypothesis} />
          <Typography fontWeight={700}>首次概率与价格预测快照</Typography><Typography component="pre" variant="caption" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{selected.initialForecastJson || '当时未提供概率快照'}</Typography>
          <Typography fontWeight={700}>修订与状态记录</Typography>{history === null ? <LoadingBlock label="读取假设历史" /> : history.map(revision => <Paper key={revision.version} variant="outlined" sx={{ p: 1.5 }}><Typography>v{revision.version} · {labels[revision.status]} · {formatTime(revision.occurredAt)}</Typography><Typography variant="body2">{revision.reason}</Typography>{revision.hypothesis && <HypothesisBody hypothesis={revision.hypothesis} />}</Paper>)}
          {nextStates[selected.status].length > 0 && <><TextField select label="核对后的状态" value={status} onChange={event => setStatus(event.target.value as HypothesisStatus)}>{nextStates[selected.status].map(next => <MenuItem key={next} value={next}>{labels[next]}</MenuItem>)}</TextField><TextField label="核对依据与可知时间" multiline minRows={3} value={reason} onChange={event => setReason(event.target.value)} inputProps={{ maxLength: 2000 }} helperText="记录实际证据来源、条件是否成立及观察时间；不能事后修改首次预测。" /></>}
        </>}
      </Stack></DialogContent><DialogActions><Button disabled={busy} onClick={() => setSelected(null)}>关闭</Button>{selected && nextStates[selected.status].length > 0 && <Button variant="contained" disabled={busy || !reason.trim()} onClick={() => void update()}>保存核对记录</Button>}</DialogActions>
    </Dialog>
  </Stack>;
}

function HypothesisBody({ hypothesis }: { hypothesis: OpportunityHypothesis }) {
  return <Stack spacing={1} sx={{ overflowWrap: 'anywhere' }}>
    {hypothesis.causalChain.map((step, index) => <Typography key={index} variant="body2">{index + 1}. [{kinds[step.kind]}] {step.statement}{step.evidenceReferences.length > 0 && <Typography component="span" variant="caption" color="text.secondary"> · {step.evidenceReferences.join('；')}</Typography>}</Typography>)}
    {[['必要条件', hypothesis.requiredConditions.join('；')], ['催化窗口', hypothesis.catalystWindow], ['已有定价', hypothesis.pricedInObservation], ['替代解释', hypothesis.alternativeScenario], ['确认条件', hypothesis.triggerCondition], ['反证条件', hypothesis.invalidationCondition], ['下一次核对', hypothesis.nextCheck], ['缺失数据', hypothesis.missingData.join('；') || '提议未列出，仍需核对来源']].map(([label, value]) => <Typography key={label} variant="body2"><strong>{label}：</strong>{value}</Typography>)}
  </Stack>;
}
