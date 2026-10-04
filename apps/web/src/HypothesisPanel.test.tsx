import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { HypothesisView, OpportunityHypothesis } from './types';
const apiMock = vi.hoisted(() => ({ researchHypotheses: vi.fn(), hypothesisHistory: vi.fn(), updateHypothesisStatus: vi.fn() }));
vi.mock('./api', () => ({ api: apiMock }));
import { HypothesisPanel } from './HypothesisPanel';
afterEach(() => { cleanup(); vi.resetAllMocks(); });
const body: OpportunityHypothesis = { title: '条件性需求改善', causalChain: [
  { kind: 'OBSERVATION', statement: '公开公告待核对', evidenceReferences: ['source_1'] },
  { kind: 'INFERENCE', statement: '必要条件成立时的影响', evidenceReferences: [] }], requiredConditions: ['后续订单确认'], catalystWindow: '下次公告',
  pricedInObservation: '未知', alternativeScenario: '仅个别公司变化', triggerCondition: '订单确认', invalidationCondition: '需求回落',
  nextCheck: '下一份公告', missingData: ['市场一致预期'], horizonHours: 24, exposureGroup: 'semiconductor' };
const hypothesis: HypothesisView = { hypothesisId: 'hypothesis_test001', workflowRunId: 'run_test001', instrumentId: 'instrument_test001',
  symbol: 'MUUSDT', sourceArtifactId: 'debate_artifact_test001', initialHypothesis: body, hypothesis: body,
  initialForecastJson: '{"direction_probabilities":{"up":0.6,"sideways":0.3,"down":0.1}}', status: 'PENDING_VALIDATION',
  firstSeenAt: '2026-10-04T00:00:00Z', informationCutoff: '2026-10-03T23:59:00Z', recordedAt: '2026-10-04T00:05:00Z',
  expiresAt: '2026-10-05T00:00:00Z', version: 0, consensusStatus: 'TIED', selected: false };

it('preserves unselected proposals and requires an explanation before recording verification', async () => {
  apiMock.researchHypotheses.mockResolvedValue([hypothesis]); apiMock.hypothesisHistory.mockResolvedValue([]);
  apiMock.updateHypothesisStatus.mockResolvedValue({ ...hypothesis, status: 'WATCHING', version: 1 });
  render(<HypothesisPanel />);
  expect(await screen.findByText('共识 TIED')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '核对条件与历史' }));
  expect(await screen.findByRole('button', { name: '保存核对记录' })).toBeDisabled();
  fireEvent.change(screen.getByLabelText('核对依据与可知时间'), { target: { value: '已核对公告来源和公布时间，条件尚未确认' } });
  fireEvent.click(screen.getByRole('button', { name: '保存核对记录' }));
  await waitFor(() => expect(apiMock.updateHypothesisStatus).toHaveBeenCalledWith('hypothesis_test001', 0, 'WATCHING', '已核对公告来源和公布时间，条件尚未确认'));
});
it('shows lifecycle conflicts in the open review dialog', async () => {
  apiMock.researchHypotheses.mockResolvedValue([hypothesis]); apiMock.hypothesisHistory.mockResolvedValue([]);
  apiMock.updateHypothesisStatus.mockRejectedValue(new Error('假设已变化，请刷新后重试'));
  render(<HypothesisPanel />);
  fireEvent.click(await screen.findByRole('button', { name: '核对条件与历史' }));
  fireEvent.change(screen.getByLabelText('核对依据与可知时间'), { target: { value: '已核对' } });
  fireEvent.click(screen.getByRole('button', { name: '保存核对记录' }));
  expect((await screen.findAllByText(/假设已变化/)).length).toBeGreaterThan(0);
});
