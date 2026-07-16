import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';

import type { AccountsOverview } from './types';

const apiMock = vi.hoisted(() => ({
  accounts: vi.fn(),
  tradeAutomations: vi.fn(),
  positions: vi.fn(),
  setExchangeAccountEnabled: vi.fn(),
  putRuntimeSecret: vi.fn(),
  clearRuntimeSecret: vi.fn(),
  testExchangeAccount: vi.fn(),
  tradeAutomation: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { TradingAccountOverview } from './TradingAccountOverview';

const accounts: AccountsOverview = {
  range: { fromInclusive: '2026-06-16T00:00:00Z', toExclusive: '2026-07-16T00:00:00Z' },
  currency: 'USDT',
  totalEquity: 1000,
  totalAvailableBalance: 900,
  totalMarginBalance: 100,
  totalUnrealizedPnl: 5,
  totalRealizedPnl: 12,
  generatedAt: '2026-07-16T00:00:00Z',
  accounts: [{
    accountId: 'account_bybit_demo_default',
    exchange: 'BYBIT',
    environment: 'DEMO',
    displayName: 'Bybit Demo',
    proxyRoute: 'exchange-ipv4',
    enabled: true,
    version: 7,
    credentialConfigured: true,
    apiKeySource: 'ENVIRONMENT_FALLBACK',
    apiKeyFingerprint: 'keyfingerprint01',
    apiKeyVersion: 0,
    apiSecretSource: 'ENVIRONMENT_FALLBACK',
    apiSecretFingerprint: 'secretfingerpr01',
    apiSecretVersion: 0,
    dataStatus: 'READY',
    currency: 'USDT',
    equity: 1000,
    availableBalance: 900,
    marginBalance: 100,
    unrealizedPnl: 5,
    realizedPnl: 12,
    openPositionCount: 0,
    snapshotAt: '2026-07-16T00:00:00Z',
  }],
};

beforeEach(() => {
  apiMock.accounts.mockResolvedValue(accounts);
  apiMock.tradeAutomations.mockResolvedValue([]);
  apiMock.positions.mockResolvedValue([]);
  apiMock.setExchangeAccountEnabled.mockResolvedValue({
    accountId: 'account_bybit_demo_default', enabled: false, version: 8,
  });
});

it('submits the account optimistic version when disabling an exchange', async () => {
  const user = userEvent.setup();
  render(<TradingAccountOverview initialAccounts={accounts} onAccountsChanged={vi.fn()} />);
  const enabledSwitch = await screen.findByRole('checkbox', { name: '启用' });

  await user.click(enabledSwitch);

  await waitFor(() => expect(apiMock.setExchangeAccountEnabled).toHaveBeenCalledWith(
    'account_bybit_demo_default',
    false,
    7,
  ));
});
