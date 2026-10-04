import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { LocalPaperAccount, PaperTrade } from './types';

const apiMock = vi.hoisted(() => ({ localPaperAccount: vi.fn(), activeLocalPaperTrades: vi.fn(), localPaperTrades: vi.fn(),
  updateLocalPaperAccount: vi.fn(), localPaperTrade: vi.fn(), cancelLocalPaperTrade: vi.fn(), closeLocalPaperTrade: vi.fn() }));
vi.mock('./api', () => ({ api: apiMock }));
import { LocalPaperPanel } from './LocalPaperPanel';
afterEach(() => { cleanup(); vi.resetAllMocks(); });

const account: LocalPaperAccount = { accountId: 'test', currency: 'USDT', executionMode: 'LOCAL_PAPER', initialBalance: 10000,
  cashBalance: 10000, equity: 10000, availableBalance: 10000, reservedMargin: 0, unrealizedPnl: 0, realizedPnl: 0, feesUsdt: 0,
  fundingUsdt: 0, ordersEnabled: false, pendingCount: 0, openCount: 0, acceptProjectionsAfter: '2026-10-04T00:00:00Z', lastCheckedAt: null, version: 7 };
function trade(symbol: string): PaperTrade {
  return { tradeId: `paper_${symbol.toLowerCase()}_test`, projectionId: 'projection_test001', instrumentId: 'instrument_test001', symbol,
    terms: { side: 'BUY', quantity: 1, contractSize: 1, limitPrice: 100, targetPrice: 110, stopPrice: 95, leverage: 2, feeRate: 0.001,
      slippageRate: 0.001, liquidationBufferRate: 0.005, policyVersion: 'test', expiresAt: '2026-10-05T00:00:00Z' },
    status: 'CLOSED', createdAt: '2026-10-04T00:00:00Z', enteredAt: '2026-10-04T00:00:01Z', entryPrice: 100,
    closedAt: '2026-10-04T00:00:02Z', exitPrice: 110, candleCursor: '2026-10-04T00:00:00Z', fundingCursor: '2026-10-04T00:00:00Z',
    quoteAt: '2026-10-04T00:00:02Z', markPrice: 110, feesUsdt: 0.2, fundingUsdt: 0, realizedPnlUsdt: 9.8, healthCode: 'READY', version: 2 };
}
function setup() {
  apiMock.localPaperAccount.mockResolvedValue(account); apiMock.activeLocalPaperTrades.mockResolvedValue([]);
  apiMock.localPaperTrades.mockResolvedValue({ trades: [], nextBeforeTradeId: null });
}
it('uses the account version when enabling new orders and shows a rejected update', async () => {
  setup(); apiMock.updateLocalPaperAccount.mockRejectedValue(new Error('账户已变化'));
  render(<LocalPaperPanel />);
  fireEvent.click(await screen.findByRole('checkbox', { name: '暂停新挂单' }));
  await waitFor(() => expect(apiMock.updateLocalPaperAccount).toHaveBeenCalledWith(true, 7));
  expect(await screen.findByText(/账户已变化/)).toBeInTheDocument();
});
it('keeps loaded earlier history when the account and latest trades refresh', async () => {
  setup();
  apiMock.localPaperTrades.mockImplementation(async (cursor: string | null = null) => cursor
    ? { trades: [trade('XAUUSDT')], nextBeforeTradeId: null }
    : { trades: [trade('BTCUSDT')], nextBeforeTradeId: 'paper_cursor_test' });
  render(<LocalPaperPanel />);
  fireEvent.click(await screen.findByRole('button', { name: '加载更早记录' }));
  expect(await screen.findByText('XAUUSDT')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '刷新' }));
  await waitFor(() => expect(apiMock.localPaperAccount).toHaveBeenCalledTimes(2));
  expect(screen.getByText('XAUUSDT')).toBeInTheDocument();
});
