import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';

const apiMock = vi.hoisted(() => ({
  authStatus: vi.fn(),
  authChallenge: vi.fn(),
  login: vi.fn(),
}));

vi.mock('./api', async (loadOriginal) => {
  const original = await loadOriginal<typeof import('./api')>();
  return { ...original, api: apiMock };
});
vi.mock('./authPow', () => ({ solveProofOfWork: vi.fn().mockResolvedValue('pow-solution') }));

import { AuthGate } from './AuthGate';

beforeEach(() => {
  apiMock.authStatus.mockResolvedValue({
    authenticated: false,
    username: null,
    expiresAt: null,
    csrfToken: 'csrf',
  });
  apiMock.authChallenge.mockResolvedValue({
    challengeId: 'challenge-1',
    nonce: 'nonce',
    proofOfWorkAlgorithm: 'SHA-256',
    proofOfWorkDifficulty: 1,
    mathExpression: '2 + 3 = ?',
    expiresAt: '2026-07-16T12:00:00Z',
  });
  apiMock.login.mockResolvedValue({
    authenticated: true,
    username: 'admin',
    expiresAt: '2026-07-16T12:00:00Z',
    csrfToken: 'csrf-next',
  });
});

it('moves from unauthenticated challenge to the protected application', async () => {
  const user = userEvent.setup();
  render(<AuthGate><div>受保护控制台</div></AuthGate>);

  expect(await screen.findByText('2 + 3 = ?')).toBeInTheDocument();
  await user.type(screen.getByLabelText(/^密码/), 'admin-password');
  await user.type(screen.getByLabelText(/^验证码答案/), '5');
  await user.click(screen.getByRole('button', { name: '登录' }));

  expect(await screen.findByText('受保护控制台')).toBeInTheDocument();
  expect(apiMock.login).toHaveBeenCalledWith({
    username: 'admin',
    password: 'admin-password',
    challengeId: 'challenge-1',
    proofOfWorkSolution: 'pow-solution',
    mathAnswer: 5,
  });
});
