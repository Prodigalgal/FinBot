import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';

const apiMock = vi.hoisted(() => ({
  probeProviderDraft: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { ProviderCreatePanel } from './SettingsPage';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it('probes an unsaved provider and only imports discovered model names', async () => {
  apiMock.probeProviderDraft.mockResolvedValue({
    providerProfileId: 'draft',
    status: 'READY',
    models: ['grok-4.5', 'grok-4.5-fast'],
    httpStatus: 200,
    latencyMilliseconds: 42,
    errorCode: null,
    errorMessage: null,
    checkedAt: '2026-07-17T00:00:00Z',
  });
  const create = vi.fn().mockResolvedValue(true);
  const user = userEvent.setup();
  render(<ProviderCreatePanel create={create} />);

  expect(screen.queryByLabelText('首个模型')).not.toBeInTheDocument();
  await user.type(screen.getByLabelText('厂商名称'), '测试厂商');
  await user.type(screen.getByLabelText('Base URL'), 'https://provider.example/v1');
  await user.type(screen.getByLabelText('API Key'), 'test-api-key');
  await user.click(screen.getByRole('button', { name: '测活并探测模型' }));

  await waitFor(() => expect(apiMock.probeProviderDraft).toHaveBeenCalledWith({
    baseUrl: 'https://provider.example/v1',
    apiKey: 'test-api-key',
    requestTimeoutSeconds: 1800,
  }));
  expect(await screen.findByText(/探测到 2 个模型/)).toBeInTheDocument();

  await user.click(screen.getByLabelText('导入模型'));
  await user.click(await screen.findByRole('option', { name: 'grok-4.5' }));
  await user.click(screen.getByRole('button', { name: '创建并导入 1 个模型' }));

  await waitFor(() => expect(create).toHaveBeenCalledWith(
    expect.objectContaining({
      displayName: '测试厂商',
      baseUrl: 'https://provider.example/v1',
      protocol: 'RESPONSES',
    }),
    'test-api-key',
    ['grok-4.5'],
    'MAX',
  ));
});
