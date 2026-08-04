import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import type { AiModel, AiProvider } from './types';

const apiMock = vi.hoisted(() => ({
  createAgentRole: vi.fn().mockResolvedValue({}),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { AgentRolesPanel } from './SettingsAdvancedPanels';

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const provider = (profileId: string, enabled: boolean): AiProvider => ({
  profileId, displayName: profileId, protocol: 'RESPONSES', reasoningParameterStyle: 'NESTED',
  baseUrl: 'https://provider.example/v1', baseUrlConfigured: true, apiKeyConfigured: true,
  credentialSource: 'DATABASE_OVERRIDE', credentialFingerprint: '12345678', credentialVersion: 1,
  credentialUpdatedAt: '2026-08-04T00:00:00Z', enabled, connectTimeoutSeconds: 10,
  requestTimeoutSeconds: 1800, maximumConcurrentRequests: 5, acquireTimeoutSeconds: 1800,
  workflowNodeUsageCount: 0, roleTemplateUsageCount: 0, executionStageUsageCount: 0,
  totalUsageCount: 0, version: 1, updatedAt: '2026-08-04T00:00:00Z',
});

const model = (modelProfileId: string, providerProfileId: string, enabled: boolean, maximumReasoningEffort: AiModel['maximumReasoningEffort'] = 'MAX'): AiModel => ({
  modelProfileId, providerProfileId, modelName: `${modelProfileId}-name`, defaultReasoningEffort: 'HIGH',
  maximumReasoningEffort, tokenLimitParameterStyle: 'PROTOCOL_DEFAULT', inputUsdPerMillion: 0,
  outputUsdPerMillion: 0, enabled, version: 1, updatedAt: '2026-08-04T00:00:00Z',
});

it('keeps role provider and model bindings enabled and owned by the same provider', async () => {
  const providers = [
    provider('provider_a', true), provider('provider_b', true),
    provider('provider_disabled', false), provider('provider_without_models', true),
  ];
  const models = [
    model('model_a', 'provider_a', true), model('model_b', 'provider_b', true, 'HIGH'),
    model('model_disabled_provider', 'provider_disabled', true), model('model_disabled', 'provider_without_models', false),
  ];
  const onChanged = vi.fn().mockResolvedValue(undefined);
  const user = userEvent.setup();
  render(<AgentRolesPanel roles={[]} providers={providers} models={models} onChanged={onChanged} />);

  await user.click(screen.getByRole('button', { name: '新建角色' }));
  expect(screen.getByLabelText('默认模型')).toHaveTextContent('model_a-name');

  await user.click(screen.getByLabelText('默认厂商'));
  expect(screen.queryByRole('option', { name: 'provider_disabled' })).not.toBeInTheDocument();
  expect(screen.queryByRole('option', { name: 'provider_without_models' })).not.toBeInTheDocument();
  await user.click(screen.getByRole('option', { name: 'provider_b' }));
  expect(screen.getByLabelText('默认模型')).toHaveTextContent('model_b-name');
  await user.click(screen.getByLabelText('思考强度'));
  expect(screen.queryByRole('option', { name: 'XHIGH' })).not.toBeInTheDocument();
  expect(screen.queryByRole('option', { name: 'MAX' })).not.toBeInTheDocument();
  await user.keyboard('{Escape}');

  await user.click(screen.getByRole('button', { name: '保存' }));
  await waitFor(() => expect(apiMock.createAgentRole).toHaveBeenCalledWith(expect.objectContaining({
    defaultProviderProfileId: 'provider_b', defaultModelName: 'model_b-name', defaultReasoningEffort: 'HIGH',
  })));
  expect(onChanged).toHaveBeenCalled();
});
