import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { AiModel, AiProvider } from './types';

const apiMock = vi.hoisted(() => ({
  workflowDefinitions: vi.fn().mockRejectedValue(new Error('工作流契约加载失败')),
  workflowSchema: vi.fn().mockResolvedValue({}),
  configuration: vi.fn().mockResolvedValue({}),
  agentRoles: vi.fn().mockResolvedValue([]),
  researchHistory: vi.fn().mockResolvedValue([]),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { WorkflowPage, defaultAiBinding, enabledModelsForProvider } from './WorkflowPage';

it('renders a recoverable error instead of remaining in loading state', async () => {
  render(<WorkflowPage />);

  expect(await screen.findByText('工作流契约加载失败')).toBeInTheDocument();
  expect(screen.queryByText('正在加载工作流定义')).not.toBeInTheDocument();
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

const model = (modelProfileId: string, providerProfileId: string, enabled: boolean): AiModel => ({
  modelProfileId, providerProfileId, modelName: `${modelProfileId}-name`, defaultReasoningEffort: 'HIGH',
  maximumReasoningEffort: 'MAX', tokenLimitParameterStyle: 'PROTOCOL_DEFAULT', inputUsdPerMillion: 0,
  outputUsdPerMillion: 0, enabled, version: 1, updatedAt: '2026-08-04T00:00:00Z',
});

it('builds a default binding only from a matching enabled provider and model', () => {
  const providers = [provider('provider_without_model', true), provider('provider_disabled', false), provider('provider_ready', true)];
  const models = [model('model_disabled_provider', 'provider_disabled', true), model('model_disabled', 'provider_without_model', false), model('model_ready', 'provider_ready', true)];

  expect(defaultAiBinding(providers, models)).toEqual({
    providerProfileId: 'provider_ready',
    modelName: 'model_ready-name',
    reasoningEffort: 'HIGH',
  });
});

it('only exposes enabled models owned by the selected provider', () => {
  const models = [model('model_a', 'provider_a', true), model('model_a_disabled', 'provider_a', false), model('model_b', 'provider_b', true)];

  expect(enabledModelsForProvider(models, 'provider_a').map((entry) => entry.modelProfileId)).toEqual(['model_a']);
});
