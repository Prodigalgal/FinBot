import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';

const apiMock = vi.hoisted(() => ({
  apiTokens: vi.fn(),
  createApiToken: vi.fn(),
  revokeApiToken: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { ApiTokensPanel } from './ApiTokensPanel';

const existingToken = {
  tokenId: 'apitoken_existing_test',
  displayName: 'Existing automation',
  fingerprint: '0123456789abcdef',
  username: 'admin',
  status: 'ACTIVE' as const,
  expiresAt: '2026-10-19T08:00:00Z',
  lastUsedAt: null,
  revokedAt: null,
  createdAt: '2026-07-21T08:00:00Z',
  updatedAt: '2026-07-21T08:00:00Z',
  version: 0,
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it('creates a token and displays its plaintext only in the creation result', async () => {
  apiMock.apiTokens.mockResolvedValue([existingToken]);
  apiMock.createApiToken.mockResolvedValue({
    token: { ...existingToken, tokenId: 'apitoken_created_test', displayName: 'Deployment automation' },
    rawToken: `finbot_pat_${'A'.repeat(43)}`,
  });
  const user = userEvent.setup();
  render(<ApiTokensPanel />);

  expect(await screen.findByText('Existing automation')).toBeInTheDocument();
  await user.type(screen.getByLabelText('Token 名称'), 'Deployment automation');
  await user.click(screen.getByRole('button', { name: '申请 Token' }));

  await waitFor(() => expect(apiMock.createApiToken).toHaveBeenCalledWith({
    displayName: 'Deployment automation',
    expiresInDays: 90,
  }));
  expect(await screen.findByText('新 Token 仅显示一次')).toBeInTheDocument();
  expect(screen.getByLabelText('新 API Token')).toHaveValue(`finbot_pat_${'A'.repeat(43)}`);
});
