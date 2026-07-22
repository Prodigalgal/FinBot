import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';

import { ForecastPanel } from './ForecastPanel';
import type { ResearchForecast } from './types';

afterEach(cleanup);

it('shows the complete role-normalized direction probability distribution', () => {
  const forecast: ResearchForecast = {
    forecastId: 'forecast_probability_test',
    workflowRunId: 'run_probability_test',
    instrumentId: 'instrument_btc_usdt',
    exchange: 'GATE',
    environment: 'LIVE',
    symbol: 'BTC_USDT',
    intervalSeconds: 3600,
    horizonSeconds: 86400,
    marketReferencePrice: 100,
    direction: 'UP',
    directionProbabilities: { up: 0.6, sideways: 0.25, down: 0.15 },
    expectedLow: 97,
    expectedHigh: 112,
    invalidationPrice: 94,
    confidence: 0.6,
    thesis: '角色归一后的概率分布支持上涨方向。',
    evidenceReferences: ['evidence_market_snapshot'],
    status: 'PENDING',
    issuedAt: '2026-07-22T13:00:00Z',
    targetAt: '2026-07-23T13:00:00Z',
    actualPrice: null,
    actualReturn: null,
    shadowNotionalUsdt: 100,
    shadowPnlUsdt: null,
    directionCorrect: null,
    rangeHit: null,
    evaluatedAt: null,
  };

  render(<ForecastPanel forecast={forecast} />);

  expect(screen.getByText('上涨')).toBeInTheDocument();
  expect(screen.getByText('震荡')).toBeInTheDocument();
  expect(screen.getByText('下跌')).toBeInTheDocument();
  expect(screen.getByText('60.0%')).toBeInTheDocument();
  expect(screen.getByText('25.0%')).toBeInTheDocument();
  expect(screen.getByText('15.0%')).toBeInTheDocument();
});
